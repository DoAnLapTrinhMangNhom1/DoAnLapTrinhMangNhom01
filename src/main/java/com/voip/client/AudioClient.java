package com.voip.client;


import buffer.JitterBuffer;
import protocol.RTPPacket;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AudioClient implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(AudioClient.class.getName());

    private int port;
    private DatagramSocket socket;
    private volatile boolean running = false;
    private Thread clientThread;

    private JitterBuffer jitterBuffer;
    private AudioPlayback audioPlayback;

    // Thống kê mạng
    private long packetsReceived = 0;
    private long packetsLost = 0;
    private long bytesReceived = 0;
    private int lastSequenceNumber = -1;

    private long totalLatency = 0;
    private long latencyCount = 0;
    private long lastJitterCalcTime = 0;
    private double currentJitter = 0.0;

    public AudioClient(int port, JitterBuffer jitterBuffer, AudioPlayback audioPlayback) {
        this.port = port;
        this.jitterBuffer = jitterBuffer;
        this.audioPlayback = audioPlayback;
    }

    public void startClient() {
        if (running) return;
        running = true;
        clientThread = new Thread(this);
        clientThread.start();
    }

    public void stopClient() {
        running = false;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        if (clientThread != null) {
            clientThread.interrupt();
        }
    }

    public void resetStats() {
        packetsReceived = 0;
        packetsLost = 0;
        bytesReceived = 0;
        lastSequenceNumber = -1;
        totalLatency = 0;
        latencyCount = 0;
        currentJitter = 0.0;
    }

    @Override
    public void run() {
        try {
            socket = new DatagramSocket(port);
            byte[] buffer = new byte[1024]; // Đủ chứa header RTP + payload âm thanh

            // Khởi động AudioPlayback khi client bắt đầu nhận dữ liệu
            if (audioPlayback != null) {
                audioPlayback.startPlayback();
            }

            while (running) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet); // Nhận gói tin từ server

                bytesReceived += packet.getLength();
                packetsReceived++;

                // Parse gói RTP từ dữ liệu nhận được
                RTPPacket rtpPacket = RTPPacket.decode(packet.getData(), packet.getLength());
                int seqNum = rtpPacket.getSequenceNumber();
                long timestamp = rtpPacket.getTimestamp();

                // Kiểm tra gói tin bị mất (Gap trong Sequence Number)
                if (lastSequenceNumber != -1) {
                    int expectedSeq = lastSequenceNumber + 1;
                    if (seqNum > expectedSeq) {
                        long lost = seqNum - expectedSeq;
                        packetsLost += lost;
                    } else if (seqNum < lastSequenceNumber && (lastSequenceNumber - seqNum) > 1000) {
                        // Xử lý trường hợp tràn số Sequence Number (nếu có) hoặc reset
                        lastSequenceNumber = seqNum;
                    }
                }
                lastSequenceNumber = seqNum;

                // Tính toán Latency giả định dựa trên thời gian hiện tại và RTP timestamp
                long currentTime = System.currentTimeMillis();
                long transit = currentTime - timestamp; 
                if (transit > 0 && transit < 5000) { // Lọc nhiễu thời gian bất thường
                    totalLatency += transit;
                    latencyCount++;
                }

                // Đưa payload vào Jitter Buffer
                if (jitterBuffer != null) {
                    jitterBuffer.addPacket(rtpPacket);
                }
            }
        } catch (Exception e) {
            if (running) {
                LOGGER.log(Level.SEVERE, "Lỗi trong quá trình nhận dữ liệu AudioClient", e);
            }
        }
    }

    // Getter methods cho UI pull data
    public long getPacketsReceived() {
        return packetsReceived;
    }

    public long getPacketsLost() {
        return packetsLost;
    }

    public long getBytesReceived() {
        return bytesReceived;
    }

    public long getAvgLatency() {
        return (latencyCount > 0) ? (totalLatency / latencyCount) : 0;
    }

    public double getCurrentJitter() {
        return currentJitter;
    }

    public double getPacketLossRate() {
        long totalPackets = packetsReceived + packetsLost;
        return (totalPackets > 0) ? ((double) packetsLost / totalPackets) * 100.0 : 0.0;
    }

    public long getKilobytesReceived() {
        return bytesReceived / 1024;
    }

    public boolean isRunning() {
        return running;
    }

    public int getPort() {
        return port;
    }
}
