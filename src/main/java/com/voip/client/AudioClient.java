package com.voip.client;

import com.voip.buffer.JitterBuffer;
import com.voip.protocol.AudioConstants;
import com.voip.protocol.AudioCrypto;
import com.voip.protocol.ControlMessage;
import com.voip.protocol.G711Codec;
import com.voip.protocol.RTPPacket;

import javax.sound.sampled.LineUnavailableException;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.security.GeneralSecurityException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AudioClient implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(AudioClient.class.getName());
    private final InetSocketAddress serverAddress;
    private final JitterBuffer jitterBuffer;
    private final AudioPlayback audioPlayback;
    private final String codec;
    private final String encryptionKey;
    private final AtomicLong packetsReceived = new AtomicLong();
    private final AtomicLong packetsLost = new AtomicLong();
    private final AtomicLong bytesReceived = new AtomicLong();
    private final AtomicLong totalLatency = new AtomicLong();
    private final AtomicLong latencyCount = new AtomicLong();
    private volatile DatagramSocket socket;
    private volatile boolean running;
    private volatile Thread clientThread;
    private volatile ScheduledExecutorService heartbeatExecutor;
    private volatile int highestSequence = -1;
    private volatile int lastSequenceNumber = -1;
    private volatile double currentJitter;
    private volatile long previousTransit = Long.MIN_VALUE;
    private volatile long lastServerResponseTime;

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
        encryptionKey = System.getProperty(AudioConstants.ENCRYPTION_KEY_PROPERTY, "");
    }

    /** Compatibility constructor: connects to a local server on the supplied port. */
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
            running = true;
            sendControl(ControlMessage.join());
            heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "Audio-Client-Heartbeat");
                thread.setDaemon(true);
                return thread;
            });
            heartbeatExecutor.scheduleAtFixedRate(this::sendHeartbeat, 5, 5, TimeUnit.SECONDS);
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
        byte[] bytes = control.toRtpPacket().encode();
        currentSocket.send(new DatagramPacket(bytes, bytes.length, serverAddress));
    }

    private void sendHeartbeat() {
        if (!running) {
            return;
        }
        try {
            sendControl(ControlMessage.ping());
        } catch (IOException e) {
            if (running) {
                LOGGER.log(Level.WARNING, "Không thể gửi heartbeat tới server.", e);
            }
        }
    }

    public void resetStats() {
        packetsReceived.set(0);
        packetsLost.set(0);
        bytesReceived.set(0);
        totalLatency.set(0);
        latencyCount.set(0);
        currentJitter = 0;
        previousTransit = Long.MIN_VALUE;
        resetSequenceTracking();
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
                RTPPacket packet = RTPPacket.decode(datagram.getData(), datagram.getLength());
                if (packet == null) {
                    continue;
                }
                if (packet.getType() == RTPPacket.TYPE_CONTROL) {
                    ControlMessage response = ControlMessage.fromRtp(packet);
                    if (response != null && response.getCommand() == ControlMessage.PONG) {
                        lastServerResponseTime = System.currentTimeMillis();
                    }
                    continue;
                }
                if (packet.getType() != RTPPacket.TYPE_LIVE) {
                    continue;
                }
                lastServerResponseTime = System.currentTimeMillis();
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

    private void processPacket(RTPPacket packet, int wireLength) throws GeneralSecurityException {
        packetsReceived.incrementAndGet();
        bytesReceived.addAndGet(wireLength);
        updateSequenceStats(packet.getSequence());
        lastSequenceNumber = highestSequence;
        int now = (int) System.currentTimeMillis();
        long latency = (int) (now - packet.getTimestampMs());
        if (latency >= 0 && latency < 5000) {
            totalLatency.addAndGet(latency);
            latencyCount.incrementAndGet();
        }
        long transit = latency;
        if (previousTransit != Long.MIN_VALUE) {
            double difference = Math.abs(transit - previousTransit);
            currentJitter += (difference - currentJitter) / 16.0;
        }
        previousTransit = transit;

        byte[] payload = AudioCrypto.transform(packet.getPayload(), encryptionKey,
                packet.getSequence(), packet.getTimestampMs());
        if (codec.equals("ulaw")) {
            payload = G711Codec.decode(payload);
        }
        RTPPacket decoded = new RTPPacket(packet.getType(), packet.getSequence(),
                packet.getTimestampMs(), payload, payload.length);
        if (jitterBuffer != null) {
            jitterBuffer.addPacket(decoded);
        }
    }

    private void updateSequenceStats(int sequence) {
        if (highestSequence < 0) {
            highestSequence = sequence;
            return;
        }
        int difference = (short) ((sequence - highestSequence) & 0xFFFF);
        if (difference > 1) {
            packetsLost.addAndGet(difference - 1L);
        }
        if (difference > 0) {
            highestSequence = sequence;
        }
    }

    private void resetSequenceTracking() {
        highestSequence = -1;
        lastSequenceNumber = -1;
    }

    public long getPacketsReceived() {
        return packetsReceived.get();
    }

    public long getPacketsLost() {
        return packetsLost.get();
    }

    public long getBytesReceived() {
        return bytesReceived.get();
    }

    public long getAvgLatency() {
        long count = latencyCount.get();
        return count > 0 ? totalLatency.get() / count : 0;
    }

    public double getCurrentJitter() {
        return currentJitter;
    }

    public double getPacketLossRate() {
        long received = packetsReceived.get();
        long lost = packetsLost.get();
        return received + lost > 0 ? (double) lost * 100.0 / (received + lost) : 0.0;
    }

    public long getKilobytesReceived() {
        return bytesReceived.get() / 1024;
    }

    public boolean isRunning() {
        return running;
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
}
