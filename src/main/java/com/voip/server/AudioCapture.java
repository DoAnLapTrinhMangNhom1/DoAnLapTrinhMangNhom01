/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.voip.server;

/**
 *
 * @author nguye
 */
import javax.sound.sampled.*;

public class AudioCapture implements Runnable {
    private TargetDataLine targetDataLine;
    private AudioFormat audioFormat;
    private volatile boolean running = false;
    private AudioDataListener listener;

    // Interface để truyền dữ liệu âm thanh thu được về cho Server xử lý
    public interface AudioDataListener {
        void onAudioDataCaptured(byte[] data, int length);
    }

    public AudioCapture(AudioDataListener listener) {
        this.listener = listener;
        // Cấu hình định dạng âm thanh chuẩn VoIP: 16kHz, 16-bit, Mono
        this.audioFormat = new AudioFormat(16000.0f, 16, 1, true, true);
    }

    public void startCapture() throws LineUnavailableException {
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, audioFormat);
        if (!AudioSystem.isLineSupported(info)) {
            throw new LineUnavailableException("Microphone không được hỗ trợ với định dạng này.");
        }
        
        targetDataLine = (TargetDataLine) AudioSystem.getLine(info);
        targetDataLine.open(audioFormat);
        targetDataLine.start();
        
        running = true;
        Thread captureThread = new Thread(this, "Audio-Capture-Thread");
        captureThread.start();
    }

    public void stopCapture() {
        running = false;
        if (targetDataLine != null) {
            targetDataLine.stop();
            targetDataLine.close();
        }
    }

    @Override
    public void run() {
        byte[] buffer = new byte[1024];
        while (running) {
            int bytesRead = targetDataLine.read(buffer, 0, buffer.length);
            if (bytesRead > 0 && listener != null) {
                listener.onAudioDataCaptured(buffer, bytesRead);
            }
        }
    }
}
