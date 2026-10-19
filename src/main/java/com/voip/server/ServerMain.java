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
        // Ho tro chay server doc lap: doi so dau la cong, doi so hai la WAV tuy chon.
        int port = args.length > 0 ? Integer.parseInt(args[0]) : AudioConstants.DEFAULT_UDP_PORT;
        Path wavFile = args.length > 1 ? Path.of(args[1]) : null;
        AudioServer server = new AudioServer(port, wavFile);
        
        // Khoi dong server va bat dau thu am tu micro
        server.startServer();
        
        System.out.println("Server dang chay. Nhan phim 'Enter' tren ban phim de dung server...");
        try (Scanner scanner = new Scanner(System.in)) {
            scanner.nextLine();
        }
        
        // Dung server an toan
        server.stopServer();
    }
}