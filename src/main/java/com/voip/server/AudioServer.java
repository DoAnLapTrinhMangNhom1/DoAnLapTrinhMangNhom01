package com.voip.server;

import com.voip.protocol.AudioConstants;
import com.voip.protocol.ControlMessage;
import com.voip.protocol.RTPPacket;

import javax.sound.sampled.LineUnavailableException;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class AudioServer implements AudioCapture.AudioDataListener {
    private DatagramSocket serverSocket;
    private int port;
    private volatile boolean running = false;
    private AudioCapture audioCapture;

    private final Set<InetSocketAddress> clients = ConcurrentHashMap.newKeySet();
    private final StreamHistoryBuffer history = new StreamHistoryBuffer();
    private final ConcurrentHashMap<InetSocketAddress, AtomicInteger> replayEpoch = new ConcurrentHashMap<>();

    private final AtomicInteger sequence = new AtomicInteger();
    private final AtomicInteger packetsSent = new AtomicInteger();
    private final AtomicLong bytesSent = new AtomicLong();
    private long startTimeMs;

    public AudioServer(int port) {
        this.port = port;
        this.audioCapture = new AudioCapture(this);
    }

    public void startServer() {
        try {
            serverSocket = new DatagramSocket(port);
            running = true;
            startTimeMs = System.currentTimeMillis();
            sequence.set(0);
            packetsSent.set(0);
            bytesSent.set(0);
            history.clear();

            audioCapture.startCapture();

            Thread clientListenerThread = new Thread(this::listenForClientRequests, "Server-Listener-Thread");
            clientListenerThread.start();

            System.out.println("AudioServer đã khởi động thành công trên cổng UDP: " + port);
        } catch (SocketException | LineUnavailableException e) {
            e.printStackTrace();
        }
    }

    private void listenForClientRequests() {
        byte[] buffer = new byte[2048];
        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                serverSocket.receive(packet);

                InetSocketAddress clientAddress = new InetSocketAddress(packet.getAddress(), packet.getPort());
                byte[] copy = java.util.Arrays.copyOf(packet.getData(), packet.getLength());
                RTPPacket rtp = RTPPacket.decode(copy, copy.length);

                if (rtp != null && rtp.getType() == RTPPacket.TYPE_CONTROL) {
                    handleControl(clientAddress, ControlMessage.fromRtp(rtp));
                } else {
                    // Gói lạ / ping: vẫn đăng ký để demo khi Client chưa gửi JOIN
                    registerClient(clientAddress);
                }
            } catch (IOException e) {
                if (!running) {
                    break;
                }
                e.printStackTrace();
            }
        }
    }

    private void handleControl(InetSocketAddress clientAddress, ControlMessage control) {
        if (control == null) {
            registerClient(clientAddress);
            return;
        }
        switch (control.getCommand()) {
            case ControlMessage.JOIN -> registerClient(clientAddress);
            case ControlMessage.LEAVE -> {
                cancelReplay(clientAddress);
                clients.remove(clientAddress);
                replayEpoch.remove(clientAddress);
                System.out.println("Client rời: " + clientAddress);
            }
            case ControlMessage.SEEK -> {
                registerClient(clientAddress);
                startHistoryReplay(clientAddress, control.getSequence());
            }
            case ControlMessage.GO_LIVE -> {
                registerClient(clientAddress);
                cancelReplay(clientAddress);
                System.out.println("Client về live: " + clientAddress);
            }
            default -> registerClient(clientAddress);
        }
    }

    private void registerClient(InetSocketAddress clientAddress) {
        if (clients.add(clientAddress)) {
            System.out.println("Đã kết nối với Client mới: " + clientAddress);
        }
    }

    @Override
    public void onAudioDataCaptured(byte[] data, int length) {
        if (!running) {
            return;
        }

        int seq = sequence.getAndIncrement() & 0xFFFF;
        int timestampMs = (int) (System.currentTimeMillis() - startTimeMs);
        RTPPacket rtp = new RTPPacket(RTPPacket.TYPE_LIVE, seq, timestampMs, data, length);
        history.add(rtp);

        if (clients.isEmpty()) {
            return;
        }
        byte[] wire = rtp.encode();
        for (InetSocketAddress clientAddress : clients) {
            sendBytes(clientAddress, wire);
        }
    }

    private void startHistoryReplay(InetSocketAddress client, int fromSeq) {
        int epoch = replayEpoch.computeIfAbsent(client, key -> new AtomicInteger()).incrementAndGet();
        List<RTPPacket> packets = history.getFrom(fromSeq);
        System.out.println("SEEK " + client + " từ seq=" + (fromSeq & 0xFFFF)
                + " (" + packets.size() + " gói lịch sử)");

        Thread replayThread = new Thread(() -> {
            for (RTPPacket packet : packets) {
                if (!running) {
                    return;
                }
                AtomicInteger current = replayEpoch.get(client);
                if (current == null || current.get() != epoch) {
                    return;
                }
                sendBytes(client, packet.encode());
                try {
                    Thread.sleep(AudioConstants.FRAME_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "History-Replay-" + client.getAddress().getHostAddress());
        replayThread.setDaemon(true);
        replayThread.start();
    }

    private void cancelReplay(InetSocketAddress client) {
        replayEpoch.computeIfAbsent(client, key -> new AtomicInteger()).incrementAndGet();
    }

    private void sendBytes(InetSocketAddress clientAddress, byte[] wire) {
        if (serverSocket == null || serverSocket.isClosed()) {
            return;
        }
        try {
            DatagramPacket packet = new DatagramPacket(
                    wire, wire.length, clientAddress.getAddress(), clientAddress.getPort());
            serverSocket.send(packet);
            packetsSent.incrementAndGet();
            bytesSent.addAndGet(wire.length);
        } catch (IOException e) {
            // Client có thể đã tắt — lần LEAVE / timeout sẽ gỡ khỏi danh sách
        }
    }

    public void stopServer() {
        running = false;
        for (InetSocketAddress client : clients) {
            cancelReplay(client);
        }
        audioCapture.stopCapture();
        if (serverSocket != null && !serverSocket.isClosed()) {
            serverSocket.close();
        }
        clients.clear();
        replayEpoch.clear();
        history.clear();
        System.out.println("AudioServer đã dừng.");
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

    public int getPacketsSent() {
        return packetsSent.get();
    }

    public long getBytesSent() {
        return bytesSent.get();
    }

    public StreamHistoryBuffer getHistory() {
        return history;
    }
}
