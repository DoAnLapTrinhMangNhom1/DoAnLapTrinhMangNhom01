package com.voip.buffer;

import com.voip.protocol.AudioConstants;
import com.voip.protocol.RTPPacket;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/** Bounded, wrap-aware packet reorder buffer with a fixed playout delay. */
public class JitterBuffer {
    private static final long REORDER_GRACE_NANOS = 5_000_000L;
    private final Map<Integer, RTPPacket> packets = new HashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition packetAvailable = lock.newCondition();
    private int bufferSizeMs;
    private int capacity;
    private int nextSequence = -1;
    private boolean started;
    private int payloadSize;
    private byte[] lastPayload;
    private int consecutiveLosses;

    public JitterBuffer() {
        this(AudioConstants.JITTER_BUFFER_MS);
    }

    public JitterBuffer(int bufferSizeMs) {
        setBufferSizeMs(bufferSizeMs);
    }

    public void addPacket(RTPPacket packet) {
        if (packet == null) {
            return;
        }
        lock.lock();
        try {
            int sequence = packet.getSequence();
            if (nextSequence == -1) {
                nextSequence = sequence;
            } else {
                int distance = sequenceDifference(sequence, nextSequence);
                if (packets.containsKey(sequence)) {
                    return;
                }
                if (distance < 0) {
                    if (started || distance < -capacity * 2) {
                        return;
                    }
                    nextSequence = sequence;
                } else if (distance >= capacity * 4) {
                    return;
                }
            }
            packets.put(sequence, packet);
            payloadSize = Math.max(payloadSize, packet.getPayloadLength());
            while (packets.size() > capacity) {
                removeFarthestPacket();
            }
            packetAvailable.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Waits for the startup buffer and returns packets in order. The audio device clocks playout.
     */
    public RTPPacket getNextPacket() {
        lock.lock();
        try {
            while (!Thread.currentThread().isInterrupted()) {
                if (nextSequence < 0 || (!started && packets.size() < capacity)) {
                    packetAvailable.await();
                    continue;
                }
                if (!started) {
                    started = true;
                }

                RTPPacket packet = packets.remove(nextSequence);
                if (packet != null) {
                    return acceptPacket(packet);
                }

                int closestSequence = findClosestFutureSequence();
                if (closestSequence >= 0) {
                    long remaining = REORDER_GRACE_NANOS;
                    while (packets.get(nextSequence) == null && remaining > 0) {
                        remaining = packetAvailable.awaitNanos(remaining);
                    }
                    packet = packets.remove(nextSequence);
                    if (packet != null) {
                        return acceptPacket(packet);
                    }
                }

                int playedSequence = nextSequence;
                nextSequence = (nextSequence + 1) & 0xFFFF;
                closestSequence = findClosestFutureSequence();
                if (closestSequence >= 0
                        && sequenceDifference(closestSequence, playedSequence) > capacity * 2) {
                    packet = packets.remove(closestSequence);
                    nextSequence = (closestSequence + 1) & 0xFFFF;
                    return acceptPacket(packet);
                }
                byte[] concealment = concealLastFrame();
                return new RTPPacket(RTPPacket.PAYLOAD_TYPE_PCM, playedSequence, 0, 0, concealment);
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            lock.unlock();
        }
    }

    public void clear() {
        lock.lock();
        try {
            packets.clear();
            nextSequence = -1;
            started = false;
            payloadSize = 0;
            lastPayload = null;
            consecutiveLosses = 0;
            packetAvailable.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public void setBufferSizeMs(int bufferSizeMs) {
        if (bufferSizeMs < AudioConstants.FRAME_MS
                || bufferSizeMs > 500) {
            throw new IllegalArgumentException("Jitter buffer phải từ 20 ms đến 500 ms.");
        }
        lock.lock();
        try {
            this.bufferSizeMs = bufferSizeMs;
            capacity = Math.max(1, (bufferSizeMs + AudioConstants.FRAME_MS - 1)
                    / AudioConstants.FRAME_MS);
            while (packets.size() > capacity) {
                removeFarthestPacket();
            }
            packetAvailable.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public int getBufferSizeMs() {
        return bufferSizeMs;
    }

    public int getEffectiveBufferSizeMs() {
        lock.lock();
        try {
            return capacity * AudioConstants.FRAME_MS;
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try {
            return packets.size();
        } finally {
            lock.unlock();
        }
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    private RTPPacket acceptPacket(RTPPacket packet) {
        nextSequence = (packet.getSequence() + 1) & 0xFFFF;
        lastPayload = packet.getPayload().clone();
        consecutiveLosses = 0;
        return packet;
    }

    private int findClosestFutureSequence() {
        int closest = -1;
        int closestDistance = Integer.MAX_VALUE;
        for (int sequence : packets.keySet()) {
            int distance = sequenceDifference(sequence, nextSequence);
            if (distance > 0 && distance < closestDistance) {
                closest = sequence;
                closestDistance = distance;
            }
        }
        return closest;
    }

    private byte[] concealLastFrame() {
        consecutiveLosses++;
        if (lastPayload == null || lastPayload.length != payloadSize || consecutiveLosses > 4) {
            return new byte[payloadSize];
        }
        byte[] faded = Arrays.copyOf(lastPayload, lastPayload.length);
        double gain = Math.pow(0.65, consecutiveLosses);
        for (int i = 0; i + 1 < faded.length; i += 2) {
            short sample = (short) (((faded[i] & 0xFF) << 8) | (faded[i + 1] & 0xFF));
            int attenuated = (int) (sample * gain);
            faded[i] = (byte) (attenuated >>> 8);
            faded[i + 1] = (byte) attenuated;
        }
        return faded;
    }

    private void removeFarthestPacket() {
        int farthestSequence = -1;
        int farthestDistance = -1;
        for (int sequence : packets.keySet()) {
            int distance = sequenceDifference(sequence, nextSequence);
            if (distance > farthestDistance) {
                farthestSequence = sequence;
                farthestDistance = distance;
            }
        }
        if (farthestSequence >= 0) {
            packets.remove(farthestSequence);
        }
    }

    private static int sequenceDifference(int sequence, int reference) {
        return (short) ((sequence - reference) & 0xFFFF);
    }
}
