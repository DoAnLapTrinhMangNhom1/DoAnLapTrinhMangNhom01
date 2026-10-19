package com.voip.server;

import com.voip.protocol.AudioConstants;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AudioCapture implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(AudioCapture.class.getName());
    private final AudioDataListener listener;
    private final AudioFormat audioFormat;
    private volatile boolean running;
    private TargetDataLine targetDataLine;
    private AudioInputStream audioInputStream;
    private Thread captureThread;

    public interface AudioDataListener {
        void onAudioDataCaptured(byte[] data, int length);
    }

    public AudioCapture(AudioDataListener listener) {
        this.listener = listener;
        audioFormat = new AudioFormat(AudioConstants.SAMPLE_RATE,
                AudioConstants.SAMPLE_SIZE_BITS, AudioConstants.CHANNELS,
                AudioConstants.SIGNED, AudioConstants.BIG_ENDIAN);
    }

    public synchronized void startCapture() throws LineUnavailableException {
        ensureStopped();
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, audioFormat);
        if (!AudioSystem.isLineSupported(info)) {
            throw new LineUnavailableException("Microphone không hỗ trợ định dạng 16 kHz PCM mono.");
        }
        TargetDataLine line = (TargetDataLine) AudioSystem.getLine(info);
        try {
            line.open(audioFormat);
            line.start();
            targetDataLine = line;
            startThread();
        } catch (LineUnavailableException | RuntimeException e) {
            running = false;
            line.close();
            targetDataLine = null;
            throw e;
        }
    }

    public synchronized void startCapture(Path wavFile)
            throws IOException, UnsupportedAudioFileException {
        ensureStopped();
        AudioInputStream source = AudioSystem.getAudioInputStream(wavFile.toFile());
        try {
            if (!AudioSystem.isConversionSupported(audioFormat, source.getFormat())) {
                throw new UnsupportedAudioFileException(
                        "Không thể chuyển WAV sang PCM 16 kHz mono: " + source.getFormat());
            }
            audioInputStream = AudioSystem.getAudioInputStream(audioFormat, source);
            startThread();
        } catch (UnsupportedAudioFileException | RuntimeException e) {
            running = false;
            if (audioInputStream != null) {
                audioInputStream.close();
                audioInputStream = null;
            }
            source.close();
            throw e;
        }
    }

    private void ensureStopped() {
        if (running) {
            throw new IllegalStateException("Nguồn âm thanh đang chạy.");
        }
    }

    private void startThread() {
        running = true;
        captureThread = new Thread(this, "Audio-Capture-Thread");
        captureThread.setDaemon(true);
        captureThread.start();
    }

    public synchronized void stopCapture() {
        running = false;
        if (targetDataLine != null) {
            targetDataLine.stop();
            targetDataLine.close();
            targetDataLine = null;
        }
        if (audioInputStream != null) {
            try {
                audioInputStream.close();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Không thể đóng file âm thanh.", e);
            }
            audioInputStream = null;
        }
        if (captureThread != null) {
            captureThread.interrupt();
            captureThread = null;
        }
    }

    public AudioFormat getAudioFormat() {
        return audioFormat;
    }

    @Override
    public void run() {
        byte[] frame = new byte[AudioConstants.PCM_FRAME_BYTES];
        long nextFrameTime = System.nanoTime();
        TargetDataLine line = targetDataLine;
        AudioInputStream stream = audioInputStream;
        try {
            while (running) {
                int bytesRead = line != null ? readFullFrame(line, frame)
                        : readFullFrame(stream, frame);
                if (bytesRead < 0) {
                    break;
                }
                if (bytesRead == frame.length && listener != null) {
                    listener.onAudioDataCaptured(Arrays.copyOf(frame, bytesRead), bytesRead);
                }
                nextFrameTime += AudioConstants.FRAME_MS * 1_000_000L;
                long remaining = nextFrameTime - System.nanoTime();
                if (remaining > 0 && line == null) {
                    java.util.concurrent.locks.LockSupport.parkNanos(remaining);
                } else if (remaining < -AudioConstants.FRAME_MS * 2_000_000L) {
                    nextFrameTime = System.nanoTime();
                }
            }
        } catch (IOException e) {
            if (running) {
                LOGGER.log(Level.SEVERE, "Lỗi đọc nguồn âm thanh.", e);
            }
        } finally {
            running = false;
        }
    }

    private int readFullFrame(AudioInputStream stream, byte[] frame) throws IOException {
        int offset = 0;
        while (running && offset < frame.length) {
            int count = stream.read(frame, offset, frame.length - offset);
            if (count < 0) {
                return -1;
            }
            offset += count;
        }
        return offset == frame.length ? offset : -1;
    }

    private int readFullFrame(TargetDataLine line, byte[] frame) {
        int offset = 0;
        while (running && offset < frame.length) {
            int count = line.read(frame, offset, frame.length - offset);
            if (count <= 0) {
                return -1;
            }
            offset += count;
        }
        return offset == frame.length ? offset : -1;
    }
}
