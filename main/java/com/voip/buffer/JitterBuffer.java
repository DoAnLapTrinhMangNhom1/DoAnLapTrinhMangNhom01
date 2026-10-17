package com.voip.buffer;

import java.util.Arrays;
import java.util.TreeMap;

/**
 * JitterBuffer reorders incoming UDP/RTP packets by sequence number,
 * absorbs network jitter by maintaining a configurable fixed delay (50/75/100 ms),
 * drops expired late packets, conceals missing frames with silence,
 * and computes QoS transmission statistics for UI monitoring.
 */
public class JitterBuffer {
    public static final int DEFAULT_FRAME_SIZE = 640;     // 16kHz, 16-bit mono, 20ms = 640 bytes
    public static final int FRAME_DURATION_MS = 20;       // 20ms per audio frame
    public static final int DEFAULT_DELAY_MS = 50;        // Default buffer delay

    private int delayMs;
    private int thresholdPackets;
    private boolean isBuffering;

    private int lastPlayedSeq = -1;
    private int expectedSeq = -1;

    // Buffer window storing packets sorted by RTP sequence number
    private final TreeMap<Integer, RTPPacket> bufferMap = new TreeMap<>((a, b) -> compareSequence(a, b));

    // QoS and Transmission Statistics
    private long packetsReceived = 0;
    private long packetsLost = 0;
    private long packetsDropped = 0; // Late/expired packets
    private long bytesReceived = 0;

    private int minReceivedSeq = -1;
    private int maxReceivedSeq = -1;

    // Jitter measurement based on RFC 3550
    // D(i, j) = (R_j - S_j) - (R_i - S_i)
    // J = J + (|D| - J) / 16
    private double jitter = 0.0;
    private long lastArrivalTimeMs = 0;
    private long lastSendTimestamp = 0;

    // Latency tracking
    private double latestLatencyMs = 0.0;

    /**
     * Default constructor with 50 ms target delay.
     */
    public JitterBuffer() {
        this(DEFAULT_DELAY_MS);
    }

    /**
     * Constructs a JitterBuffer with specified delay in milliseconds.
     * Common values: 50 ms (~3 packets), 75 ms (~4 packets), 100 ms (~5 packets).
     *
     * @param delayMs Target delay in milliseconds.
     */
    public JitterBuffer(int delayMs) {
        setDelayMs(delayMs);
        this.isBuffering = true;
    }

    /**
     * Thread-safe method called by UDP receiver thread.
     * Inserts decoded RTP packets into the buffer window, updating stats.
     *
     * @param packet Received RTPPacket.
     */
    public synchronized void put(RTPPacket packet) {
        if (packet == null) {
            return;
        }

        long now = System.currentTimeMillis();
        packetsReceived++;
        byte[] payload = packet.getData();
        if (payload != null) {
            bytesReceived += payload.length;
        }

        int seq = packet.getSequence();

        // Update sequence bounds
        if (minReceivedSeq == -1 || compareSequence(seq, minReceivedSeq) < 0) {
            minReceivedSeq = seq;
        }
        if (maxReceivedSeq == -1 || compareSequence(seq, maxReceivedSeq) > 0) {
            maxReceivedSeq = seq;
        }

        // Measure Jitter according to RFC 3550
        long sendTs = packet.getTimestamp();
        if (lastArrivalTimeMs > 0 && sendTs > 0 && lastSendTimestamp > 0) {
            double sendDiff = (double) (sendTs - lastSendTimestamp);
            // Handle sample-based timestamps vs millisecond timestamps
            if (sendDiff >= 300 && sendDiff <= 340) {
                // 16kHz audio: 320 samples per 20ms frame -> 16 samples per ms
                sendDiff = sendDiff / 16.0;
            } else if (sendDiff >= 150 && sendDiff <= 170) {
                // 8kHz audio: 160 samples per 20ms frame -> 8 samples per ms
                sendDiff = sendDiff / 8.0;
            }
            double arrivalDiff = (double) (now - lastArrivalTimeMs);
            double d = arrivalDiff - sendDiff;
            jitter += (Math.abs(d) - jitter) / 16.0;
        }
        lastArrivalTimeMs = now;
        lastSendTimestamp = sendTs;

        // One-way transit latency if timestamp is synchronized epoch time
        if (sendTs > 0 && now >= sendTs && (now - sendTs) < 60000) {
            this.latestLatencyMs = (double) (now - sendTs);
        }

        // Drop late/expired packets that arrive after their playback deadline
        if (lastPlayedSeq != -1 && compareSequence(seq, lastPlayedSeq) <= 0) {
            packetsDropped++;
            return;
        }

        // If duplicate sequence already in buffer, drop to prevent corrupting playback
        if (bufferMap.containsKey(seq)) {
            packetsDropped++;
            return;
        }

        bufferMap.put(seq, packet);

        // Check if pre-buffering accumulation requirement is satisfied
        if (isBuffering && bufferMap.size() >= thresholdPackets) {
            isBuffering = false;
            if (expectedSeq == -1 && !bufferMap.isEmpty()) {
                expectedSeq = bufferMap.firstKey();
            }
        }
    }

    /**
     * Polls the next 640-byte PCM frame for audio playback.
     * Called by audio speaker playback thread.
     *
     * @return 640-byte PCM array, silence array on concealment, or null if buffering/empty.
     */
    public synchronized byte[] poll() {
        return poll(DEFAULT_FRAME_SIZE);
    }

    /**
     * Polls the next PCM frame with dynamic frame size.
     *
     * @param frameSize PCM byte array length (typically 640 bytes for 20ms at 16kHz mono).
     * @return PCM byte array, silence array on concealment, or null if buffering/empty.
     */
    public synchronized byte[] poll(int frameSize) {
        if (frameSize <= 0) {
            frameSize = DEFAULT_FRAME_SIZE;
        }

        // Check if currently buffering to absorb initial jitter
        if (isBuffering) {
            if (bufferMap.size() >= thresholdPackets) {
                isBuffering = false;
                if (expectedSeq == -1 && !bufferMap.isEmpty()) {
                    expectedSeq = bufferMap.firstKey();
                }
            } else {
                return null; // Soak until target buffer level is accumulated
            }
        }

        // Check for buffer underrun
        if (bufferMap.isEmpty()) {
            isBuffering = true; // Enter re-buffering to prevent stutter
            return null;
        }

        if (expectedSeq == -1) {
            expectedSeq = bufferMap.firstKey();
        }

        // Retrieve packet matching the expected sequence
        RTPPacket packet = bufferMap.remove(expectedSeq);
        if (packet != null) {
            lastPlayedSeq = expectedSeq;
            expectedSeq = nextSequence(expectedSeq);

            // Playout latency
            long now = System.currentTimeMillis();
            if (packet.getTimestamp() > 0 && now >= packet.getTimestamp() && (now - packet.getTimestamp()) < 60000) {
                this.latestLatencyMs = (double) (now - packet.getTimestamp());
            }

            byte[] data = packet.getData();
            if (data != null && data.length == frameSize) {
                return data;
            } else if (data != null) {
                return Arrays.copyOf(data, frameSize);
            } else {
                return new byte[frameSize];
            }
        }

        // Packet is missing from the buffer window
        int firstAvailable = bufferMap.firstKey();
        int gap = compareSequence(firstAvailable, expectedSeq);

        // If the gap is excessively large (> 50 frames = 1s), resync directly
        if (gap > 50 || gap < 0) {
            expectedSeq = firstAvailable;
            packet = bufferMap.remove(expectedSeq);
            lastPlayedSeq = expectedSeq;
            expectedSeq = nextSequence(expectedSeq);
            byte[] data = packet.getData();
            return data != null ? Arrays.copyOf(data, frameSize) : new byte[frameSize];
        }

        // Packet loss concealment: advance expected sequence and return silence (zero array)
        packetsLost++;
        lastPlayedSeq = expectedSeq;
        expectedSeq = nextSequence(expectedSeq);

        return new byte[frameSize];
    }

    /**
     * Updates target delay during runtime (e.g. from ListenForm combo box 50/75/100 ms).
     *
     * @param delayMs Target delay in milliseconds.
     */
    public synchronized void setDelayMs(int delayMs) {
        this.delayMs = delayMs;
        this.thresholdPackets = calculateThresholdPackets(delayMs);
        if (!isBuffering && bufferMap.size() < thresholdPackets) {
            // Keep current playback flow unless buffer is drained
        }
    }

    /**
     * Resets jitter buffer state and statistics (e.g. when changing rooms or stopping playback).
     */
    public synchronized void reset() {
        bufferMap.clear();
        isBuffering = true;
        lastPlayedSeq = -1;
        expectedSeq = -1;
        minReceivedSeq = -1;
        maxReceivedSeq = -1;
        packetsReceived = 0;
        packetsLost = 0;
        packetsDropped = 0;
        bytesReceived = 0;
        lastArrivalTimeMs = 0;
        lastSendTimestamp = 0;
        jitter = 0.0;
        latestLatencyMs = 0.0;
    }

    /**
     * Returns a snapshot of QoS stream statistics for UI visualization.
     *
     * @return StreamStats object.
     */
    public synchronized StreamStats getStats() {
        long effectiveLost = packetsLost;
        if (minReceivedSeq != -1 && maxReceivedSeq != -1) {
            long seqRange = compareSequence(maxReceivedSeq, minReceivedSeq) + 1;
            long seqLoss = Math.max(0, seqRange - (packetsReceived - packetsDropped));
            effectiveLost = Math.max(effectiveLost, seqLoss);
        }

        long total = (packetsReceived - packetsDropped) + effectiveLost;
        double lossRate = total > 0 ? ((double) effectiveLost / total) * 100.0 : 0.0;
        double effectiveLatency = latestLatencyMs > 0 ? latestLatencyMs : delayMs;

        return new StreamStats(
            packetsReceived,
            effectiveLost,
            lossRate,
            bytesReceived,
            effectiveLatency,
            jitter,
            packetsDropped,
            bufferMap.size()
        );
    }

    // Helper methods

    public synchronized int getDelayMs() {
        return delayMs;
    }

    public synchronized int getThresholdPackets() {
        return thresholdPackets;
    }

    public synchronized boolean isBuffering() {
        return isBuffering;
    }

    public synchronized int getLastPlayedSeq() {
        return lastPlayedSeq;
    }

    public synchronized void setLastPlayedSeq(int seq) {
        this.lastPlayedSeq = seq;
    }

    public synchronized int getExpectedSeq() {
        return expectedSeq;
    }

    public synchronized void setExpectedSeq(int seq) {
        this.expectedSeq = seq;
    }

    public synchronized int size() {
        return bufferMap.size();
    }

    public synchronized boolean isEmpty() {
        return bufferMap.isEmpty();
    }

    /**
     * Calculates packet buffer threshold based on delay:
     * 50 ms  -> 3 packets
     * 75 ms  -> 4 packets
     * 100 ms -> 5 packets
     */
    public static int calculateThresholdPackets(int delayMs) {
        if (delayMs <= 0) {
            return 0;
        }
        return (int) Math.ceil((double) delayMs / FRAME_DURATION_MS);
    }

    /**
     * Compares sequence numbers with standard RTP 16-bit wrap-around support
     * as well as standard integer monotonic numbering.
     */
    public static int compareSequence(int s1, int s2) {
        int diff = s1 - s2;
        if (diff > 32768) {
            return diff - 65536;
        } else if (diff < -32768) {
            return diff + 65536;
        }
        return diff;
    }

    /**
     * Advances sequence number respecting 16-bit RTP range.
     */
    public static int nextSequence(int seq) {
        if (seq >= 0 && seq < 65536) {
            return (seq + 1) & 0xFFFF;
        }
        return seq + 1;
    }
}
