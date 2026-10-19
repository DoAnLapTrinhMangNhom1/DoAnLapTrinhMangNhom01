/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Main.java to edit this template
 */
package com.voip.ui;

public class VoipJavaUDPStreaming {
    public static void main(String[] args) {
        // Tao Swing UI tren Event Dispatch Thread de thao tac giao dien an toan.
        javax.swing.SwingUtilities.invokeLater(() -> new HomeForm().setVisible(true));
    }
}
