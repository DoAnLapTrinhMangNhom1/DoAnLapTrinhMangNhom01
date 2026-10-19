package com.voip.server;

import com.voip.protocol.AudioConstants;
import com.voip.protocol.AudioCrypto;
import com.voip.protocol.ControlMessage;
import com.voip.protocol.G711Codec;
import com.voip.protocol.RTPPacket;

import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
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
    private final int port;
    private final AudioCapture audioCapture;
    private final Set<InetSocketAddress> clients = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<InetSocketAddress, Long> lastClientActivity =
            new ConcurrentHashMap<>();
    private final AtomicInteger sequence = new AtomicInteger();
    private final AtomicLong packetsSent = new AtomicLong();
    private final AtomicLong bytesSent = new AtomicLong();
    private final String codec;
    private final String encryptionKey;
    private volatile DatagramSocket serverSocket;
    private volatile boolean running;
    private volatile Thread listenerThread;

    public AudioServer(int port) {
        this(port, null);
    }

    public AudioServer(int port, Path wavFile) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Cổng UDP phải từ 1 đến 65535.");
        }
        this.port = port;
        codec = System.getProperty(AudioConstants.CODEC_PROPERTY, "pcm").trim().toLowerCase();
        if (!codec.equals("pcm") && !codec.equals("ulaw")) {
            throw new IllegalArgumentException("Codec không hỗ trợ: " + codec);
        }
        encryptionKey = System.getProperty(AudioConstants.ENCRYPTION_KEY_PROPERTY, "");
        audioCapture = new AudioCapture(this);
        this.wavFile = wavFile;
    }

    private final Path wavFile;

    public synchronized void startServer()
            throws IOException, LineUnavailableException, UnsupportedAudioFileException {
        if (running) {
            return;
        }
        DatagramSocket socket = new DatagramSocket(port);
        socket.setSoTimeout(5000);
        serverSocket = socket;
        sequence.set(0);
        packetsSent.set(0);
        bytesSent.set(0);
        running = true;
        try {
            if (wavFile == null) {
                audioCapture.startCapture();
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
        listenerThread = new Thread(this::listenForClientRequests, "Server-UDP-Listener");
        listenerThread.setDaemon(true);
        listenerThread.start();
        LOGGER.info("Audio server đang chạy trên UDP/" + port + " (" + codec + ").");
    }

    private void listenForClientRequests() {
        byte[] buffer = new byte[AudioConstants.MAX_UDP_PACKET_SIZE];
        while (running) {
            try {
                DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
                serverSocket.receive(datagram);
                InetSocketAddress client = new InetSocketAddress(
                        datagram.getAddress(), datagram.getPort());
                RTPPacket rtp = RTPPacket.decode(
                        Arrays.copyOf(datagram.getData(), datagram.getLength()), datagram.getLength());
                if (rtp == null || rtp.getType() != RTPPacket.TYPE_CONTROL) {
                    continue;
                }
                lastClientActivity.put(client, System.currentTimeMillis());
                handleControl(client, ControlMessage.fromRtp(rtp));
            } catch (SocketTimeoutException e) {
                removeInactiveClients();
            } catch (SocketException e) {
                if (running) {
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
        if (control == null) {
            return;
        }
        switch (control.getCommand()) {
            case ControlMessage.JOIN -> {
                registerClient(client);
                sendControlResponse(client, ControlMessage.pong());
            }
            case ControlMessage.PING -> {
                registerClient(client);
                sendControlResponse(client, ControlMessage.pong());
            }
            case ControlMessage.LEAVE -> {
                clients.remove(client);
                lastClientActivity.remove(client);
                LOGGER.info("Client rời: " + client);
            }
            default -> LOGGER.warning("Bỏ qua lệnh điều khiển không hỗ trợ.");
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
        int seq = sequence.getAndIncrement() & 0xFFFF;
        int timestamp = (int) System.currentTimeMillis();
        try {
            byte[] payload = Arrays.copyOf(data, length);
            if (codec.equals("ulaw")) {
                payload = G711Codec.encode(payload);
            }
            payload = AudioCrypto.transform(payload, encryptionKey, seq, timestamp);
            RTPPacket rtp = new RTPPacket(RTPPacket.TYPE_LIVE, seq, timestamp,
                    payload, payload.length);
            byte[] wire = rtp.encode();
            for (InetSocketAddress client : clients) {
                sendBytes(client, wire);
            }
        } catch (GeneralSecurityException e) {
            LOGGER.log(Level.SEVERE, "Không thể mã hóa gói âm thanh.", e);
        }
    }

    private void sendControlResponse(InetSocketAddress client, ControlMessage control) {
        byte[] wire = control.toRtpPacket().encode();
        sendBytes(client, wire);
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
        audioCapture.stopCapture();
        DatagramSocket socket = serverSocket;
        serverSocket = null;
        if (socket != null) {
            socket.close();
        }
        clients.clear();
        lastClientActivity.clear();
        LOGGER.info("Audio server đã dừng.");
    }

    public boolean isRunning() {
        return running;
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

    public String getCodec() {
        return codec;
    }

}
