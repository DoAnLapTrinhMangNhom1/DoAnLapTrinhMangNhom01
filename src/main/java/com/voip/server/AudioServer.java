package com.voip.server;

import com.voip.protocol.AudioConstants;
import com.voip.protocol.AudioCrypto;
import com.voip.protocol.AudioLevel;
import com.voip.protocol.ControlMessage;
import com.voip.protocol.G711Codec;
import com.voip.protocol.RTPPacket;

import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AudioServer implements AudioCapture.AudioDataListener {
    private static final Logger LOGGER = Logger.getLogger(AudioServer.class.getName());
    private static final int SAMPLES_PER_FRAME =
            (int) (AudioConstants.SAMPLE_RATE * AudioConstants.FRAME_MS / 1000);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final int port;
    private final Path wavFile;
    private final Mixer.Info inputDevice;
    private final AudioCapture audioCapture;
    private final Set<InetSocketAddress> clients = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<InetSocketAddress, Long> lastClientActivity =
            new ConcurrentHashMap<>();
    private final AtomicInteger sequence = new AtomicInteger();
    private final AtomicInteger timestamp = new AtomicInteger(RANDOM.nextInt());
    private final AtomicLong packetsSent = new AtomicLong();
    private final AtomicLong bytesSent = new AtomicLong();
    private final AtomicLong audioFramesCaptured = new AtomicLong();
    private final AtomicInteger inputLevelPercent = new AtomicInteger();
    private volatile long lastAudioFrameNanos;
    private final String codec;
    private final int payloadType;
    private final String encryptionKey;
    private final AudioCrypto audioCrypto;
    private volatile long ssrc = Integer.toUnsignedLong(RANDOM.nextInt());
    private volatile DatagramSocket serverSocket;
    private volatile boolean running;
    private volatile boolean audioSourceEnded;
    private volatile String audioCaptureError;

    public AudioServer(int port) {
        this(port, null, null);
    }

    public AudioServer(int port, Path wavFile) {
        this(port, wavFile, null);
    }

    public AudioServer(int port, Path wavFile, Mixer.Info inputDevice) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Cổng UDP phải từ 1 đến 65535.");
        }
        this.port = port;
        this.wavFile = wavFile;
        this.inputDevice = inputDevice;
        codec = System.getProperty(AudioConstants.CODEC_PROPERTY, "pcm").trim().toLowerCase();
        if (!codec.equals("pcm") && !codec.equals("ulaw")) {
            throw new IllegalArgumentException("Codec không hỗ trợ: " + codec);
        }
        payloadType = codec.equals("ulaw")
                ? RTPPacket.PAYLOAD_TYPE_ULAW : RTPPacket.PAYLOAD_TYPE_PCM;
        encryptionKey = System.getProperty(AudioConstants.ENCRYPTION_KEY_PROPERTY, "");
        try {
            audioCrypto = new AudioCrypto(encryptionKey);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Không thể khởi tạo bảo vệ âm thanh.", e);
        }
        audioCapture = new AudioCapture(this);
    }

    public synchronized void startServer()
            throws IOException, LineUnavailableException, UnsupportedAudioFileException {
        if (running) {
            return;
        }
        DatagramSocket socket = new DatagramSocket(port);
        socket.setSoTimeout(5000);
        serverSocket = socket;
        sequence.set(0);
        timestamp.set(RANDOM.nextInt());
        ssrc = Integer.toUnsignedLong(RANDOM.nextInt());
        packetsSent.set(0);
        bytesSent.set(0);
        audioFramesCaptured.set(0);
        audioSourceEnded = false;
        audioCaptureError = null;
        running = true;
        try {
            if (wavFile == null) {
                audioCapture.startCapture(inputDevice);
            } else {
                audioCapture.startCapture(wavFile);
            }
        } catch (IOException | LineUnavailableException | UnsupportedAudioFileException
                 | RuntimeException e) {
            running = false;
            serverSocket = null;
            audioCapture.stopCapture();
            socket.close();
            throw e;
        }
        Thread listenerThread = new Thread(
                () -> listenForClientRequests(socket), "Server-UDP-Listener");
        listenerThread.setDaemon(true);
        listenerThread.start();
        LOGGER.info("Audio server đang chạy trên UDP/" + port + " (" + codec + ").");
    }

    private void listenForClientRequests(DatagramSocket listenerSocket) {
        byte[] buffer = new byte[AudioConstants.MAX_UDP_PACKET_SIZE];
        while (running && serverSocket == listenerSocket) {
            try {
                DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
                listenerSocket.receive(datagram);
                InetSocketAddress client = new InetSocketAddress(
                        datagram.getAddress(), datagram.getPort());
                ControlMessage control = ControlMessage.decode(
                        datagram.getData(), datagram.getLength());
                if (control != null) {
                    handleControl(client, control);
                }
            } catch (SocketTimeoutException e) {
                if (running && serverSocket == listenerSocket) {
                    removeInactiveClients();
                }
            } catch (SocketException e) {
                if (running && serverSocket == listenerSocket) {
                    LOGGER.log(Level.SEVERE, "Socket server gặp lỗi.", e);
                }
                break;
            } catch (IOException e) {
                if (running) {
                    LOGGER.log(Level.SEVERE, "Lỗi nhận lệnh điều khiển UDP.", e);
                }
            }
        }
    }

    private void handleControl(InetSocketAddress client, ControlMessage control) {
        switch (control.getCommand()) {
            case ControlMessage.JOIN -> {
                if (control.getPayloadType() != payloadType
                        || control.isEncrypted() != audioCrypto.isEnabled()) {
                    sendControlResponse(client, ControlMessage.reject(
                            "Codec hoặc chế độ mã hóa không khớp với server."));
                    return;
                }
                registerClient(client);
                sendControlResponse(client, ControlMessage.accept(payloadType, audioCrypto.isEnabled()));
            }
            case ControlMessage.PING -> {
                if (clients.contains(client)) {
                    lastClientActivity.put(client, System.currentTimeMillis());
                    sendControlResponse(client, ControlMessage.pong(control.getToken()));
                }
            }
            case ControlMessage.LEAVE -> {
                clients.remove(client);
                lastClientActivity.remove(client);
                LOGGER.info("Client rời: " + client);
            }
            default -> LOGGER.fine("Bỏ qua phản hồi điều khiển từ client.");
        }
    }

    private void registerClient(InetSocketAddress client) {
        lastClientActivity.put(client, System.currentTimeMillis());
        if (clients.add(client)) {
            LOGGER.info("Client tham gia: " + client);
        }
    }

    private void removeInactiveClients() {
        long inactiveBefore = System.currentTimeMillis() - 15_000;
        for (var entry : lastClientActivity.entrySet()) {
            if (entry.getValue() < inactiveBefore
                    && lastClientActivity.remove(entry.getKey(), entry.getValue())) {
                clients.remove(entry.getKey());
                LOGGER.info("Đã loại client mất heartbeat: " + entry.getKey());
            }
        }
    }

    @Override
    public void onAudioDataCaptured(byte[] data, int length) {
        if (!running || data == null || length <= 0 || length > data.length) {
            return;
        }
        audioFramesCaptured.incrementAndGet();
        inputLevelPercent.set(AudioLevel.rmsPercent(data, length));
        lastAudioFrameNanos = System.nanoTime();
        int seq = sequence.getAndIncrement() & 0xFFFF;
        int ts = timestamp.getAndAdd(SAMPLES_PER_FRAME);
        try {
            byte[] payload = Arrays.copyOf(data, length);
            if (payloadType == RTPPacket.PAYLOAD_TYPE_ULAW) {
                payload = G711Codec.encode(payload);
            }
            RTPPacket header = new RTPPacket(payloadType, seq, ts, ssrc, new byte[0]);
            payload = audioCrypto.encrypt(payload, header.getHeaderBytes());
            byte[] wire = new RTPPacket(payloadType, seq, ts, ssrc, payload).encode();
            for (InetSocketAddress client : clients) {
                sendBytes(client, wire);
            }
        } catch (GeneralSecurityException e) {
            LOGGER.log(Level.SEVERE, "Không thể bảo vệ gói âm thanh.", e);
        }
    }

    @Override
    public void onAudioCaptureFailed(IOException error) {
        audioCaptureError = error.getMessage();
    }

    @Override
    public void onAudioSourceEnded() {
        audioSourceEnded = true;
        LOGGER.info("Đã phát hết tệp WAV.");
    }

    private void sendControlResponse(InetSocketAddress client, ControlMessage control) {
        sendBytes(client, control.encode());
    }

    private void sendBytes(InetSocketAddress client, byte[] wire) {
        DatagramSocket socket = serverSocket;
        if (socket == null || socket.isClosed()) {
            return;
        }
        try {
            socket.send(new DatagramPacket(wire, wire.length,
                    client.getAddress(), client.getPort()));
            packetsSent.incrementAndGet();
            bytesSent.addAndGet(wire.length);
        } catch (IOException e) {
            if (running) {
                LOGGER.log(Level.WARNING, "Gửi UDP thất bại tới " + client, e);
            }
        }
    }

    public synchronized void stopServer() {
        running = false;
        try {
            audioCapture.stopCapture();
        } catch (IllegalStateException e) {
            LOGGER.log(Level.SEVERE, "Không thể dừng hoàn toàn luồng thu âm.", e);
        } finally {
            DatagramSocket socket = serverSocket;
            serverSocket = null;
            if (socket != null) {
                socket.close();
            }
            clients.clear();
            lastClientActivity.clear();
            LOGGER.info("Audio server đã dừng.");
        }
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isAudioSourceEnded() {
        return audioSourceEnded;
    }

    public int getPort() {
        return port;
    }

    public Set<InetSocketAddress> getClients() {
        return Collections.unmodifiableSet(clients);
    }

    public int getClientCount() {
        return clients.size();
    }

    public long getPacketsSent() {
        return packetsSent.get();
    }

    public long getBytesSent() {
        return bytesSent.get();
    }

    public long getAudioFramesCaptured() {
        return audioFramesCaptured.get();
    }

    public int getInputLevelPercent() {
        if (!running || System.nanoTime() - lastAudioFrameNanos > 500_000_000L) {
            return 0;
        }
        return inputLevelPercent.get();
    }

    public String getAudioCaptureError() {
        return audioCaptureError;
    }

    public String getCodec() {
        return codec;
    }
}
