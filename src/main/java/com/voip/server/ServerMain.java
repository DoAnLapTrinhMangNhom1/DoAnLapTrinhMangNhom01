package com.voip.server;

/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

/**
 *
 * @author nguye
 */
import java.util.Scanner;

public class ServerMain {
    public static void main(String[] args) {
        int port = 8888; // Cổng UDP mặc định cho Server
        AudioServer server = new AudioServer(port);
        
        // Khởi động server và bắt đầu thu âm từ micro
        server.startServer();
        
        System.out.println("Server đang chạy. Nhấn phím 'Enter' trên bàn phím để dừng server...");
        Scanner scanner = new Scanner(System.in);
        scanner.nextLine();
        
        // Dừng server an toàn
        server.stopServer();
    }
}