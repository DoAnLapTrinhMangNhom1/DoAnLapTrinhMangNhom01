package com.voip.client;

import buffer.JitterBuffer;
import protocol.RTPPacket;
import com.voip.protocol.AudioConstants;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AudioPlayback implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(AudioPlayback.class.getName());

    private JitterBuffer jitterBuffer;
    private SourceDataLine speakers;
    private volatile boolean playing = false;
    private Thread playbackThread;

    // Định dạng âm thanh chuẩn cho VoIP (Phải khớp với cấu hình bên Server AudioCapture)
    private AudioFormat format = new AudioFormat(
            AudioConstants.SAMPLE_RATE,
            AudioConstants.SAMPLE_SIZE_BITS,
            AudioConstants.CHANNELS,
            AudioConstants.SIGNED,
            AudioConstants.BIG_ENDIAN
    );

    public AudioPlayback(JitterBuffer jitterBuffer) {
        this.jitterBuffer = jitterBuffer;
    }

    public void initLine() throws Exception {
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        if (!AudioSystem.isLineSupported(info)) {
            throw new IllegalArgumentException("Audio format không được hỗ trợ bởi hệ thống.");
        }
        speakers = (SourceDataLine) AudioSystem.getLine(info);
        speakers.open(format);
        speakers.start();
    }

    public void startPlayback() {
        if (playing) return;
        try {
            if (speakers == null || !speakers.isOpen()) {
                initLine();
            }
            playing = true;
            playbackThread = new Thread(this);
            playbackThread.start();
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Không thể khởi động AudioPlayback", e);
        }
    }

    public void pausePlayback() {
        playing = false;
        if (speakers != null) {
            speakers.stop();
        }
    }

    public void resumePlayback() {
        if (!playing) {
            playing = true;
            if (speakers != null) {
                speakers.start();
            }
            if (playbackThread == null || !playbackThread.isAlive()) {
                playbackThread = new Thread(this);
                playbackThread.start();
            }
        }
    }

    public void stopPlayback() {
        playing = false;
        if (playbackThread != null) {
            playbackThread.interrupt();
        }
        if (speakers != null) {
            speakers.drain();
            speakers.stop();
            speakers.close();
        }
    }

    @Override
    public void run() {
        try {
            while (playing) {
                // Lấy gói tin tiếp theo từ Jitter Buffer
                RTPPacket packet = jitterBuffer.getNextPacket();
                if (packet != null && packet.getPayload() != null) {
                    byte[] audioData = packet.getPayload();
                    speakers.write(audioData, 0, audioData.length);
                } else {
                    // Nếu buffer trống (underrun), tạm nghỉ ngắn để tránh chiếm CPU
                    Thread.sleep(5);
                }
            }
        } catch (InterruptedException e) {
            // Luồng bị ngắt khi dừng
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Lỗi trong luồng phát âm thanh", e);
        }
    }

    public boolean isPlaying() {
        return playing;
    }
}
