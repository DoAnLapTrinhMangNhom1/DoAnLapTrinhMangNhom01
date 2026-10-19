package com.voip.server;

/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

/**
 *
 * @author nguye
 */
import com.voip.protocol.AudioConstants;

import java.nio.file.Path;
import java.util.Scanner;

public class ServerMain {
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : AudioConstants.DEFAULT_UDP_PORT;
        Path wavFile = args.length > 1 ? Path.of(args[1]) : null;
        AudioServer server = new AudioServer(port, wavFile);
        
        // Khởi động server và bắt đầu thu âm từ micro
        server.startServer();
        
        System.out.println("Server đang chạy. Nhấn phím 'Enter' trên bàn phím để dừng server...");
        try (Scanner scanner = new Scanner(System.in)) {
            scanner.nextLine();
        }
        
        // Dừng server an toàn
        server.stopServer();
    }
}