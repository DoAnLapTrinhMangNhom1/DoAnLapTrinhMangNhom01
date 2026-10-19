package com.voip.client;

import com.voip.buffer.JitterBuffer;
import com.voip.protocol.AudioConstants;
import com.voip.protocol.AudioCrypto;
import com.voip.protocol.ControlMessage;
import com.voip.protocol.G711Codec;
import com.voip.protocol.RTPPacket;

import javax.sound.sampled.LineUnavailableException;
import javax.crypto.AEADBadTagException;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.security.GeneralSecurityException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AudioClient implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(AudioClient.class.getName());
    private static final int SEQUENCE_WINDOW = 2048;
    private final InetSocketAddress serverAddress;
    private final JitterBuffer jitterBuffer;
    private final AudioPlayback audioPlayback;
    private final String codec;
    private final int payloadType;
    private final AudioCrypto audioCrypto;
    private final SequenceTracker sequenceTracker = new SequenceTracker();
    private final AtomicLong bytesReceived = new AtomicLong();
    private final AtomicLong totalRttNanos = new AtomicLong();
    private final AtomicLong rttSamples = new AtomicLong();
    private final AtomicLong authenticationFailures = new AtomicLong();
    private volatile DatagramSocket socket;
    private volatile boolean running;
    private volatile boolean connectionAccepted;
    private volatile Thread clientThread;
    private volatile ScheduledExecutorService heartbeatExecutor;
    private volatile double currentJitter;
    private volatile double previousArrivalTicks = Double.NaN;
    private volatile int previousTimestamp;
    private volatile long lastServerResponseTime;
    private volatile long currentSsrc = -1;
    private volatile String connectionMessage = "Đang chờ phản hồi từ server.";
    private volatile int lastSequenceNumber = -1;

    public AudioClient(String host, int serverPort, JitterBuffer jitterBuffer,
                       AudioPlayback audioPlayback) throws IOException {
        if (serverPort < 1 || serverPort > 65535) {
            throw new IllegalArgumentException("Cổng server phải từ 1 đến 65535.");
        }
        serverAddress = new InetSocketAddress(InetAddress.getByName(host), serverPort);
        this.jitterBuffer = jitterBuffer;
        this.audioPlayback = audioPlayback;
        codec = System.getProperty(AudioConstants.CODEC_PROPERTY, "pcm").trim().toLowerCase();
        if (!codec.equals("pcm") && !codec.equals("ulaw")) {
            throw new IllegalArgumentException("Codec không hỗ trợ: " + codec);
        }
        payloadType = codec.equals("ulaw")
                ? RTPPacket.PAYLOAD_TYPE_ULAW : RTPPacket.PAYLOAD_TYPE_PCM;
        String passphrase = System.getProperty(AudioConstants.ENCRYPTION_KEY_PROPERTY, "");
        try {
            audioCrypto = new AudioCrypto(passphrase);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Không thể khởi tạo bảo vệ âm thanh.", e);
        }
    }

    public AudioClient(int serverPort, JitterBuffer jitterBuffer, AudioPlayback audioPlayback)
            throws IOException {
        this("127.0.0.1", serverPort, jitterBuffer, audioPlayback);
    }

    public synchronized void startClient() throws IOException, LineUnavailableException {
        if (running) {
            return;
        }
        DatagramSocket clientSocket = new DatagramSocket();
        try {
            clientSocket.setReceiveBufferSize(256 * 1024);
            clientSocket.setSoTimeout(5000);
            if (audioPlayback != null) {
                audioPlayback.startPlayback();
            }
            socket = clientSocket;
            lastServerResponseTime = 0;
            connectionAccepted = false;
            connectionMessage = "Đang thương lượng cấu hình với server.";
            running = true;
            sendControl(ControlMessage.join(payloadType, audioCrypto.isEnabled()));
            heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "Audio-Client-Heartbeat");
                thread.setDaemon(true);
                return thread;
            });
            heartbeatExecutor.scheduleAtFixedRate(this::sendHeartbeat, 1, 5, TimeUnit.SECONDS);
            clientThread = new Thread(this, "Audio-UDP-Receiver");
            clientThread.setDaemon(true);
            clientThread.start();
        } catch (IOException | LineUnavailableException | RuntimeException e) {
            running = false;
            ScheduledExecutorService heartbeat = heartbeatExecutor;
            heartbeatExecutor = null;
            if (heartbeat != null) {
                heartbeat.shutdownNow();
            }
            socket = null;
            clientSocket.close();
            if (audioPlayback != null) {
                audioPlayback.stopPlayback();
            }
            throw e;
        }
    }

    public synchronized void stopClient() {
        if (!running) {
            return;
        }
        try {
            sendControl(ControlMessage.leave());
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Không gửi được lệnh LEAVE.", e);
        }
        running = false;
        connectionAccepted = false;
        ScheduledExecutorService heartbeat = heartbeatExecutor;
        heartbeatExecutor = null;
        if (heartbeat != null) {
            heartbeat.shutdownNow();
        }
        DatagramSocket currentSocket = socket;
        socket = null;
        if (currentSocket != null) {
            currentSocket.close();
        }
        if (clientThread != null) {
            clientThread.interrupt();
            clientThread = null;
        }
        if (audioPlayback != null) {
            audioPlayback.stopPlayback();
        }
        if (jitterBuffer != null) {
            jitterBuffer.clear();
        }
    }

    private void sendControl(ControlMessage control) throws IOException {
        DatagramSocket currentSocket = socket;
        if (currentSocket == null || currentSocket.isClosed()) {
            throw new SocketException("Client UDP chưa được khởi động.");
        }
        byte[] bytes = control.encode();
        currentSocket.send(new DatagramPacket(bytes, bytes.length, serverAddress));
    }

    private void sendHeartbeat() {
        if (!running) {
            return;
        }
        try {
            long lastResponse = lastServerResponseTime;
            if (connectionAccepted && lastResponse > 0
                    && System.currentTimeMillis() - lastResponse < 15_000) {
                sendControl(ControlMessage.ping(System.nanoTime()));
            } else {
                connectionAccepted = false;
                connectionMessage = "Đang kết nối lại với server.";
                sendControl(ControlMessage.join(payloadType, audioCrypto.isEnabled()));
            }
        } catch (IOException e) {
            if (running) {
                LOGGER.log(Level.WARNING, "Không thể gửi heartbeat tới server.", e);
            }
        }
    }

    public void resetStats() {
        sequenceTracker.reset();
        bytesReceived.set(0);
        totalRttNanos.set(0);
        rttSamples.set(0);
        authenticationFailures.set(0);
        currentJitter = 0;
        previousArrivalTicks = Double.NaN;
        previousTimestamp = 0;
        lastSequenceNumber = -1;
    }

    @Override
    public void run() {
        byte[] buffer = new byte[AudioConstants.MAX_UDP_PACKET_SIZE];
        while (running) {
            try {
                DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
                socket.receive(datagram);
                if (!serverAddress.getAddress().equals(datagram.getAddress())
                        || serverAddress.getPort() != datagram.getPort()) {
                    continue;
                }
                ControlMessage control = ControlMessage.decode(
                        datagram.getData(), datagram.getLength());
                if (control != null) {
                    processControl(control);
                    continue;
                }
                RTPPacket packet = RTPPacket.decode(datagram.getData(), datagram.getLength());
                if (packet == null || !connectionAccepted || packet.getPayloadType() != payloadType) {
                    continue;
                }
                processPacket(packet, datagram.getLength());
            } catch (SocketTimeoutException e) {
                if (running) {
                    sendHeartbeat();
                }
            } catch (SocketException e) {
                if (running) {
                    LOGGER.log(Level.SEVERE, "Socket client gặp lỗi.", e);
                }
                break;
            } catch (IOException | GeneralSecurityException e) {
                if (running) {
                    LOGGER.log(Level.SEVERE, "Không thể nhận/giải mã gói âm thanh.", e);
                }
            }
        }
    }

    private void processControl(ControlMessage control) {
        lastServerResponseTime = System.currentTimeMillis();
        switch (control.getCommand()) {
            case ControlMessage.ACCEPT -> {
                if (control.getPayloadType() == payloadType
                        && control.isEncrypted() == audioCrypto.isEnabled()) {
                    connectionAccepted = true;
                    connectionMessage = "Đã kết nối";
                } else {
                    connectionAccepted = false;
                    connectionMessage = "Server phản hồi cấu hình không khớp.";
                }
            }
            case ControlMessage.REJECT -> {
                connectionAccepted = false;
                connectionMessage = control.getMessage();
            }
            case ControlMessage.PONG -> {
                long now = System.nanoTime();
                long rtt = now - control.getToken();
                if (rtt >= 0 && rtt < TimeUnit.SECONDS.toNanos(30)) {
                    totalRttNanos.addAndGet(rtt);
                    rttSamples.incrementAndGet();
                }
            }
            default -> LOGGER.fine("Bỏ qua lệnh điều khiển không mong đợi.");
        }
    }

    private void processPacket(RTPPacket packet, int wireLength) throws GeneralSecurityException {
        byte[] payload;
        try {
            payload = audioCrypto.decrypt(packet.getPayload(), packet.getHeaderBytes());
        } catch (AEADBadTagException e) {
            authenticationFailures.incrementAndGet();
            return;
        }
        int expectedLength = payloadType == RTPPacket.PAYLOAD_TYPE_ULAW
                ? AudioConstants.PCM_FRAME_BYTES / 2 : AudioConstants.PCM_FRAME_BYTES;
        if (payload.length != expectedLength) {
            return;
        }
        lastServerResponseTime = System.currentTimeMillis();
        if (currentSsrc != packet.getSsrc()) {
            currentSsrc = packet.getSsrc();
            sequenceTracker.reset();
            if (jitterBuffer != null) {
                jitterBuffer.clear();
            }
            previousArrivalTicks = Double.NaN;
        }
        if (!sequenceTracker.observe(packet.getSequence())) {
            return;
        }
        bytesReceived.addAndGet(wireLength);
        lastSequenceNumber = packet.getSequence();

        double arrivalTicks = System.nanoTime() * (AudioConstants.SAMPLE_RATE / 1_000_000_000.0);
        if (!Double.isNaN(previousArrivalTicks)) {
            long mediaDelta = packet.getTimestamp() - previousTimestamp;
            double difference = Math.abs((arrivalTicks - previousArrivalTicks) - mediaDelta);
            currentJitter += (difference - currentJitter) / 16.0;
        }
        previousArrivalTicks = arrivalTicks;
        previousTimestamp = packet.getTimestamp();

        if (payloadType == RTPPacket.PAYLOAD_TYPE_ULAW) {
            payload = G711Codec.decode(payload);
        }
        if (jitterBuffer != null) {
            jitterBuffer.addPacket(new RTPPacket(payloadType, packet.getSequence(),
                    packet.getTimestamp(), packet.getSsrc(), payload));
        }
    }

    public long getPacketsReceived() {
        return sequenceTracker.getReceived();
    }

    public long getPacketsLost() {
        return sequenceTracker.getLost();
    }

    public long getBytesReceived() {
        return bytesReceived.get();
    }

    /** Approximate one-way network latency, calculated as half the measured control RTT. */
    public double getEstimatedOneWayLatencyMs() {
        long samples = rttSamples.get();
        return samples > 0 ? totalRttNanos.get() / (double) samples / 2_000_000.0 : Double.NaN;
    }

    public double getCurrentJitter() {
        return currentJitter * 1000.0 / AudioConstants.SAMPLE_RATE;
    }

    public double getPacketLossRate() {
        long received = getPacketsReceived();
        long lost = getPacketsLost();
        return received + lost > 0 ? (double) lost * 100.0 / (received + lost) : 0.0;
    }

    public long getKilobytesReceived() {
        return bytesReceived.get() / 1024;
    }

    public long getAuthenticationFailures() {
        return authenticationFailures.get();
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isConnectionAccepted() {
        return connectionAccepted;
    }

    public String getConnectionMessage() {
        return connectionMessage;
    }

    public boolean isServerReachable() {
        long lastResponse = lastServerResponseTime;
        return lastResponse > 0 && System.currentTimeMillis() - lastResponse < 15_000;
    }

    public int getPort() {
        DatagramSocket current = socket;
        return current == null ? -1 : current.getLocalPort();
    }

    public int getLastSequenceNumber() {
        return lastSequenceNumber;
    }

    public InetSocketAddress getServerAddress() {
        return serverAddress;
    }

    public String getCodec() {
        return codec;
    }

    private static final class SequenceTracker {
        private final Set<Long> receivedPackets = new HashSet<>();
        private final Set<Long> missingPackets = new HashSet<>();
        private long highestExtended = -1;
        private long receivedCount;
        private long finalizedLost;

        synchronized boolean observe(int sequence) {
            if (highestExtended < 0) {
                highestExtended = sequence;
                receivedPackets.add(highestExtended);
                receivedCount++;
                return true;
            }
            int difference = (short) ((sequence - (highestExtended & 0xFFFF)) & 0xFFFF);
            long candidate = highestExtended + difference;
            if (difference > 0) {
                for (long missing = highestExtended + 1; missing < candidate; missing++) {
                    missingPackets.add(missing);
                }
                highestExtended = candidate;
                receivedPackets.add(candidate);
                receivedCount++;
                expireOldPackets();
                return true;
            }
            if (receivedPackets.contains(candidate)) {
                return false;
            }
            if (missingPackets.remove(candidate)) {
                receivedPackets.add(candidate);
                receivedCount++;
                return true;
            }
            return false;
        }

        private void expireOldPackets() {
            long oldestRetained = highestExtended - SEQUENCE_WINDOW;
            receivedPackets.removeIf(sequence -> sequence < oldestRetained);
            missingPackets.removeIf(sequence -> {
                if (sequence < oldestRetained) {
                    finalizedLost++;
                    return true;
                }
                return false;
            });
        }

        synchronized void reset() {
            receivedPackets.clear();
            missingPackets.clear();
            highestExtended = -1;
            receivedCount = 0;
            finalizedLost = 0;
        }

        synchronized long getReceived() {
            return receivedCount;
        }

        synchronized long getLost() {
            return finalizedLost + missingPackets.size();
        }
    }
}
