/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.voip.server;

/**
 *
 * @author nguye
 */
import javax.sound.sampled.LineUnavailableException;
import java.io.IOException;
import java.net.*;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class AudioServer implements AudioCapture.AudioDataListener {
    private DatagramSocket serverSocket;
    private int port;
    private volatile boolean running = false;
    private AudioCapture audioCapture;
    
    // Danh sách lưu trữ các Client đang kết nối (IP và Port) để phát 1-to-N
    private Set<InetSocketAddress> clients = ConcurrentHashMap.newKeySet();

    public AudioServer(int port) {
        this.port = port;
        this.audioCapture = new AudioCapture(this);
    }

    public void startServer() {
        try {
            serverSocket = new DatagramSocket(port);
            running = true;
            
            // Bắt đầu thu âm từ micro
            audioCapture.startCapture();
            
            // Chạy luồng lắng nghe các gói tin đăng ký từ phía Client
            Thread clientListenerThread = new Thread(this::listenForClientRequests, "Server-Listener-Thread");
            clientListenerThread.start();
            
            System.out.println("AudioServer đã khởi động thành công trên cổng UDP: " + port);
        } catch (SocketException | LineUnavailableException e) {
            e.printStackTrace();
        }
    }

    private void listenForClientRequests() {
        byte[] buffer = new byte[512];
        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                serverSocket.receive(packet); // Nhận gói tin tín hiệu từ client
                
                InetSocketAddress clientAddress = new InetSocketAddress(packet.getAddress(), packet.getPort());
                if (!clients.contains(clientAddress)) {
                    clients.add(clientAddress);
                    System.out.println("Đã kết nối với Client mới: " + clientAddress);
                }
            } catch (IOException e) {
                if (!running) break;
                e.printStackTrace();
            }
        }
    }

    @Override
    public void onAudioDataCaptured(byte[] data, int length) {
        if (!running || clients.isEmpty()) return;

        // Phát tán (broadcast) dữ liệu âm thanh tới toàn bộ các Client trong danh sách
        for (InetSocketAddress clientAddress : clients) {
            try {
                DatagramPacket packet = new DatagramPacket(data, length, clientAddress.getAddress(), clientAddress.getPort());
                serverSocket.send(packet);
            } catch (IOException e) {
                // Xử lý khi gửi lỗi hoặc client ngắt kết nối đột ngột có thể loại bỏ ở đây nếu cần
            }
        }
    }

    public void stopServer() {
        running = false;
        audioCapture.stopCapture();
        if (serverSocket != null && !serverSocket.isClosed()) {
            serverSocket.close();
        }
        clients.clear();
        System.out.println("AudioServer đã dừng.");
    }
}
