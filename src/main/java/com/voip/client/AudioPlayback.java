package com.voip.client;

import com.voip.buffer.JitterBuffer;
import com.voip.protocol.AudioConstants;
import com.voip.protocol.RTPPacket;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AudioPlayback implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(AudioPlayback.class.getName());
    private final JitterBuffer jitterBuffer;
    private final AudioFormat format = new AudioFormat(AudioConstants.SAMPLE_RATE,
            AudioConstants.SAMPLE_SIZE_BITS, AudioConstants.CHANNELS,
            AudioConstants.SIGNED, AudioConstants.BIG_ENDIAN);
    private volatile SourceDataLine speakers;
    private volatile boolean playing;
    private volatile boolean stopped;
    private Thread playbackThread;

    public AudioPlayback(JitterBuffer jitterBuffer) {
        this.jitterBuffer = jitterBuffer;
    }

    private void initLine() throws LineUnavailableException {
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        if (!AudioSystem.isLineSupported(info)) {
            throw new LineUnavailableException("Thiết bị phát không hỗ trợ PCM 16 kHz mono.");
        }
        speakers = (SourceDataLine) AudioSystem.getLine(info);
        speakers.open(format, AudioConstants.PCM_FRAME_BYTES * 2);
        speakers.start();
    }

    public synchronized void startPlayback() throws LineUnavailableException {
        if (playing) {
            return;
        }
        stopped = false;
        if (speakers == null || !speakers.isOpen()) {
            initLine();
        }
        playing = true;
        playbackThread = new Thread(this, "Audio-Playback-Thread");
        playbackThread.setDaemon(true);
        playbackThread.start();
    }

    public synchronized void pausePlayback() {
        playing = false;
        jitterBuffer.clear();
        if (speakers != null) {
            speakers.stop();
            speakers.flush();
        }
    }

    public synchronized void resumePlayback() throws LineUnavailableException {
        if (stopped || playing) {
            return;
        }
        if (speakers == null || !speakers.isOpen()) {
            initLine();
        }
        speakers.start();
        playing = true;
        if (playbackThread == null || !playbackThread.isAlive()) {
            playbackThread = new Thread(this, "Audio-Playback-Thread");
            playbackThread.setDaemon(true);
            playbackThread.start();
        }
    }

    public synchronized void stopPlayback() {
        playing = false;
        stopped = true;
        SourceDataLine line = speakers;
        speakers = null;
        if (line != null) {
            line.stop();
            line.flush();
            line.close();
        }
        if (playbackThread != null) {
            playbackThread.interrupt();
            playbackThread = null;
        }
    }

    public void setVolumePercent(int percent) {
        SourceDataLine line = speakers;
        if (line == null || !line.isOpen() || !line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            return;
        }
        FloatControl gain = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
        if (percent <= 0) {
            gain.setValue(gain.getMinimum());
        } else {
            float decibels = (float) (20.0 * Math.log10(percent / 100.0));
            gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), decibels)));
        }
    }

    @Override
    public void run() {
        try {
            while (!stopped) {
                if (!playing) {
                    Thread.sleep(10);
                    continue;
                }
                RTPPacket packet = jitterBuffer.getNextPacket();
                SourceDataLine line = speakers;
                if (packet != null && line != null && line.isOpen()) {
                    byte[] audioData = packet.getPayload();
                    if (audioData.length > 0) {
                        line.write(audioData, 0, audioData.length);
                    }
                } else {
                    Thread.sleep(2);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            LOGGER.log(Level.SEVERE, "Lỗi trong luồng phát âm thanh.", e);
        }
    }

    public boolean isPlaying() {
        return playing;
    }
}
