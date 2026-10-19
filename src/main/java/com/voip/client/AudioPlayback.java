package com.voip.client;

import com.voip.buffer.JitterBuffer;
import com.voip.protocol.AudioConstants;
import com.voip.protocol.AudioLevel;
import com.voip.protocol.RTPPacket;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import java.util.ArrayList;
import java.util.List;
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
    private volatile Mixer.Info selectedMixer;
    private volatile DeviceChangeListener deviceChangeListener;
    private volatile int currentVolumePercent = 100;
    private volatile int outputLevelPercent;
    private volatile long lastAudioWriteNanos;
    private List<Mixer.Info> lastKnownSpeakers = new ArrayList<>();
    private Thread playbackThread;

    public interface DeviceChangeListener {
        void onDeviceRemoved(Mixer.Info oldDevice);
        void onDeviceAdded(List<Mixer.Info> newDevices);
    }

    public AudioPlayback(JitterBuffer jitterBuffer) {
        this.jitterBuffer = jitterBuffer;
        this.lastKnownSpeakers = getAvailableSpeakers();
    }

    public AudioPlayback(JitterBuffer jitterBuffer, Mixer.Info speaker) {
        this(jitterBuffer);
        this.selectedMixer = speaker;
    }

    public void setDeviceChangeListener(DeviceChangeListener listener) {
        this.deviceChangeListener = listener;
    }

    public static List<Mixer.Info> getAvailableSpeakers() {
        List<Mixer.Info> speakers = new ArrayList<>();
        Mixer.Info[] mixers = AudioSystem.getMixerInfo();
        AudioFormat format = new AudioFormat(AudioConstants.SAMPLE_RATE,
                AudioConstants.SAMPLE_SIZE_BITS, AudioConstants.CHANNELS,
                AudioConstants.SIGNED, AudioConstants.BIG_ENDIAN);

        for (Mixer.Info mixer : mixers) {
            try {
                Mixer m = AudioSystem.getMixer(mixer);
                Line.Info[] sourceLines = m.getSourceLineInfo();

                for (Line.Info line : sourceLines) {
                    if (line.getLineClass() == SourceDataLine.class) {
                        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
                        if (m.isLineSupported(info)) {
                            speakers.add(mixer);
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                // Bỏ qua mixer lỗi
            }
        }
        return speakers;
    }

    public synchronized void setSpeaker(Mixer.Info speaker) {
        this.selectedMixer = speaker;
    }

    public synchronized Mixer.Info getSelectedSpeaker() {
        return selectedMixer;
    }

    public synchronized void restartPlayback() throws LineUnavailableException {
        boolean wasPlaying = playing;
        stopPlayback();

        // Clear jitter buffer để tránh old audio data conflicts với device mới
        jitterBuffer.clear();

        try {
            Thread.sleep(200); // Tăng delay từ 100ms → 200ms để đảm bảo cleanup hoàn toàn
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (wasPlaying) {
            startPlayback();
        }
    }

    public synchronized boolean isCurrentDeviceAvailable() {
        if (selectedMixer == null) {
            return true;
        }
        List<Mixer.Info> available = getAvailableSpeakers();
        return available.contains(selectedMixer);
    }

    public synchronized void autoSwitchToDefault() {
        if (selectedMixer != null && !isCurrentDeviceAvailable()) {
            Mixer.Info oldDevice = selectedMixer;
            selectedMixer = null;
            LOGGER.info("Device " + oldDevice.getName() + " không còn available, chuyển sang default");

            if (deviceChangeListener != null) {
                deviceChangeListener.onDeviceRemoved(oldDevice);
            }
        }
    }

    public synchronized void checkForDeviceChanges() {
        List<Mixer.Info> currentSpeakers = getAvailableSpeakers();

        List<Mixer.Info> removedDevices = new ArrayList<>();
        for (Mixer.Info device : lastKnownSpeakers) {
            if (!currentSpeakers.contains(device)) {
                removedDevices.add(device);
            }
        }

        List<Mixer.Info> addedDevices = new ArrayList<>();
        for (Mixer.Info device : currentSpeakers) {
            if (!lastKnownSpeakers.contains(device)) {
                addedDevices.add(device);
            }
        }

        lastKnownSpeakers = currentSpeakers;

        if (!addedDevices.isEmpty() && deviceChangeListener != null) {
            deviceChangeListener.onDeviceAdded(addedDevices);
        }
    }

    public synchronized int getCurrentVolumePercent() {
        return currentVolumePercent;
    }

    public int getOutputLevelPercent() {
        if (!playing || System.nanoTime() - lastAudioWriteNanos > 500_000_000L) {
            return 0;
        }
        return outputLevelPercent;
    }

    private void initLine() throws LineUnavailableException {
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);

        SourceDataLine line;
        if (selectedMixer != null) {
            Mixer mixer = AudioSystem.getMixer(selectedMixer);
            if (!mixer.isLineSupported(info)) {
                throw new LineUnavailableException(
                    "Loa '" + selectedMixer.getName() + "' không hỗ trợ định dạng 16 kHz PCM mono.");
            }
            line = (SourceDataLine) mixer.getLine(info);
        } else {
            if (!AudioSystem.isLineSupported(info)) {
                throw new LineUnavailableException("Thiết bị phát không hỗ trợ PCM 16 kHz mono.");
            }
            line = (SourceDataLine) AudioSystem.getLine(info);
        }

        line.open(format, AudioConstants.PCM_FRAME_BYTES * 2);
        line.start();
        speakers = line;
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

        setVolumePercent(currentVolumePercent);
    }

    public synchronized void pausePlayback() {
        playing = false;
        outputLevelPercent = 0;
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
        outputLevelPercent = 0;
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
        this.currentVolumePercent = percent;
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
            while (playing) {
                // Lấy gói tin tiếp theo từ Jitter Buffer
                RTPPacket packet = jitterBuffer.getNextPacket();
                if (packet != null && packet.getPayload() != null) {
                    byte[] audioData = packet.getPayload();
                    SourceDataLine line = speakers;
                    if (line != null && line.isOpen()) {
                        int written = line.write(audioData, 0, audioData.length);
                        if (written > 0) {
                            outputLevelPercent = AudioLevel.rmsPercent(audioData, written);
                            lastAudioWriteNanos = System.nanoTime();
                        }
                    }
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
