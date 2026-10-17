package com.voip.server;

import com.voip.protocol.AudioConstants;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;
import java.util.Arrays;

public class AudioCapture implements Runnable {
    private TargetDataLine targetDataLine;
    private AudioFormat audioFormat;
    private volatile boolean running = false;
    private AudioDataListener listener;

    public interface AudioDataListener {
        void onAudioDataCaptured(byte[] data, int length);
    }

    public AudioCapture(AudioDataListener listener) {
        this.listener = listener;
        this.audioFormat = new AudioFormat(
                AudioConstants.SAMPLE_RATE,
                AudioConstants.SAMPLE_SIZE_BITS,
                AudioConstants.CHANNELS,
                AudioConstants.SIGNED,
                AudioConstants.BIG_ENDIAN
        );
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

    public AudioFormat getAudioFormat() {
        return audioFormat;
    }

    @Override
    public void run() {
        byte[] buffer = new byte[AudioConstants.PCM_FRAME_BYTES];
        while (running) {
            int bytesRead = targetDataLine.read(buffer, 0, buffer.length);
            if (bytesRead > 0 && listener != null) {
                byte[] frame = Arrays.copyOf(buffer, bytesRead);
                listener.onAudioDataCaptured(frame, bytesRead);
            }
        }
    }
}
