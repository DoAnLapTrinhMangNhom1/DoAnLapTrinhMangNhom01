package com.voip.server;

import com.voip.protocol.AudioConstants;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AudioCapture implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(AudioCapture.class.getName());
    private final AudioDataListener listener;
    private final AudioFormat audioFormat;
    private volatile boolean running;
    private volatile long captureGeneration;
    private TargetDataLine targetDataLine;
    private AudioInputStream audioInputStream;
    private Thread captureThread;

    public interface AudioDataListener {
        void onAudioDataCaptured(byte[] data, int length);

        default void onAudioCaptureFailed(IOException error) {
        }

        default void onAudioSourceEnded() {
        }
    }

    public AudioCapture(AudioDataListener listener) {
        this.listener = listener;
        audioFormat = new AudioFormat(AudioConstants.SAMPLE_RATE,
                AudioConstants.SAMPLE_SIZE_BITS, AudioConstants.CHANNELS,
                AudioConstants.SIGNED, AudioConstants.BIG_ENDIAN);
    }

    public synchronized void startCapture() throws LineUnavailableException {
        startCapture((Mixer.Info) null);
    }

    public synchronized void startCapture(Mixer.Info inputDevice) throws LineUnavailableException {
        ensureStopped();
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, audioFormat);
        Mixer mixer = inputDevice == null ? null : AudioSystem.getMixer(inputDevice);
        boolean supported = mixer == null
                ? AudioSystem.isLineSupported(info)
                : mixer.isLineSupported(info);
        if (!supported) {
            throw new LineUnavailableException("Microphone khong ho tro dinh dang 16 kHz PCM mono.");
        }
        TargetDataLine line = (TargetDataLine) (mixer == null
                ? AudioSystem.getLine(info) : mixer.getLine(info));
        try {
            line.open(audioFormat);
            line.start();
            line.flush();
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
                        "Khong the chuyen WAV sang PCM 16 kHz mono: " + source.getFormat());
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
        if (running || (captureThread != null && captureThread.isAlive())) {
            throw new IllegalStateException("Nguon am thanh dang chay.");
        }
        captureThread = null;
    }

    private void startThread() {
        // Moi lan capture co ma rieng de luong cu khong anh huong phien moi khi dung/khoi dong lai.
        long generation = ++captureGeneration;
        running = true;
        TargetDataLine line = targetDataLine;
        AudioInputStream stream = audioInputStream;
        captureThread = new Thread(
                () -> capture(generation, line, stream), "Audio-Capture-Thread");
        captureThread.setDaemon(true);
        captureThread.start();
    }

    public synchronized void stopCapture() {
        // Vo hieu hoa generation truoc khi dong nguon de luong dang doc khong gui them frame.
        ++captureGeneration;
        running = false;
        if (targetDataLine != null) {
            targetDataLine.stop();
            targetDataLine.flush();
            targetDataLine.close();
            targetDataLine = null;
        }
        if (audioInputStream != null) {
            try {
                audioInputStream.close();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Khong the dong file am thanh.", e);
            }
            audioInputStream = null;
        }
        Thread thread = captureThread;
        if (thread != null) {
            thread.interrupt();
            if (thread != Thread.currentThread()) {
                try {
                    thread.join(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Bi ngat khi cho luong thu am dung.", e);
                }
            }
            if (!thread.isAlive()) {
                captureThread = null;
            } else {
                throw new IllegalStateException(
                        "Luong thu am cu chua dung sau khi dong microphone; khong the mo thiet bi moi an toan.");
            }
        }
    }

    public AudioFormat getAudioFormat() {
        return audioFormat;
    }

    public static List<Mixer.Info> getSupportedInputDevices() {
        AudioFormat format = new AudioFormat(AudioConstants.SAMPLE_RATE,
                AudioConstants.SAMPLE_SIZE_BITS, AudioConstants.CHANNELS,
                AudioConstants.SIGNED, AudioConstants.BIG_ENDIAN);
        DataLine.Info lineInfo = new DataLine.Info(TargetDataLine.class, format);
        List<Mixer.Info> devices = new ArrayList<>();
        for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
            if (AudioSystem.getMixer(mixerInfo).isLineSupported(lineInfo)) {
                devices.add(mixerInfo);
            }
        }
        return List.copyOf(devices);
    }

    @Override
    public void run() {
        capture(captureGeneration, targetDataLine, audioInputStream);
    }

    private void capture(long generation, TargetDataLine line, AudioInputStream stream) {
        // Chi gui frame hoan chinh; moi frame tuong ung 20 ms am thanh o cau hinh hien tai.
        byte[] frame = new byte[AudioConstants.PCM_FRAME_BYTES];
        long nextFrameTime = System.nanoTime();
        boolean reachedEndOfInput = false;
        try {
            while (isCaptureActive(generation)) {
                int bytesRead = line != null ? readFullFrame(line, frame, generation)
                        : readFullFrame(stream, frame, generation);
                if (bytesRead < 0) {
                    reachedEndOfInput = stream != null && isCaptureActive(generation);
                    break;
                }
                if (bytesRead == frame.length && listener != null
                        && isCaptureActive(generation)) {
                    listener.onAudioDataCaptured(Arrays.copyOf(frame, bytesRead), bytesRead);
                }
                // WAV khong co clock thiet bi, nen tu canh nhip doc theo thoi luong frame.
                nextFrameTime += AudioConstants.FRAME_MS * 1_000_000L;
                long remaining = nextFrameTime - System.nanoTime();
                if (remaining > 0 && line == null && isCaptureActive(generation)) {
                    java.util.concurrent.locks.LockSupport.parkNanos(remaining);
                } else if (remaining < -AudioConstants.FRAME_MS * 2_000_000L) {
                    nextFrameTime = System.nanoTime();
                }
            }
        } catch (IOException e) {
            if (isCaptureActive(generation)) {
                LOGGER.log(Level.SEVERE, "Loi doc nguon am thanh.", e);
                if (listener != null) {
                    listener.onAudioCaptureFailed(e);
                }
            }
        } catch (RuntimeException e) {
            if (isCaptureActive(generation)) {
                LOGGER.log(Level.SEVERE, "Loi driver khi doc nguon am thanh.", e);
                if (listener != null) {
                    listener.onAudioCaptureFailed(
                            new IOException("Loi driver am thanh: " + e.getMessage(), e));
                }
            }
        } finally {
            if (captureGeneration == generation) {
                running = false;
            }
            if (reachedEndOfInput && listener != null && captureGeneration == generation) {
                listener.onAudioSourceEnded();
            }
        }
    }

    private boolean isCaptureActive(long generation) {
        return running && captureGeneration == generation;
    }

    private int readFullFrame(AudioInputStream stream, byte[] frame, long generation)
            throws IOException {
        int offset = 0;
        while (isCaptureActive(generation) && offset < frame.length) {
            int count = stream.read(frame, offset, frame.length - offset);
            if (count < 0) {
                return -1;
            }
            offset += count;
        }
        return offset == frame.length ? offset : -1;
    }

    private int readFullFrame(TargetDataLine line, byte[] frame, long generation)
            throws IOException {
        int offset = 0;
        long noDataSince = 0;
        while (isCaptureActive(generation) && offset < frame.length) {
            int count = line.read(frame, offset, frame.length - offset);
            if (count < 0) {
                throw new IOException("Microphone da ngung cung cap du lieu.");
            }
            if (count == 0) {
                if (!isCaptureActive(generation)) {
                    return -1;
                }
                if (noDataSince == 0) {
                    noDataSince = System.nanoTime();
                } else if (System.nanoTime() - noDataSince >= 2_000_000_000L) {
                    throw new IOException(
                            "Microphone khong cung cap du lieu trong 2 giay.");
                }
                LockSupport.parkNanos(1_000_000L);
                continue;
            }
            noDataSince = 0;
            offset += count;
        }
        return offset == frame.length ? offset : -1;
    }
}
