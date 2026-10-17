package com.voip.buffer;

/**
 * StreamStats holds QoS and transmission metrics measured by JitterBuffer.
 * UI/Client queries getStats() periodically to display or plot these metrics.
 */
public class StreamStats {
    public final long packetsReceived;
    public final long packetsLost;
    public final double lossRate;      // in percentage, e.g. 5.0 for 5%
    public final long bytesReceived;
    public final double latencyMs;
    public final double jitterMs;
    public final long packetsDropped;  // late packets dropped
    public final int bufferSize;       // current queued packets in buffer

    public StreamStats(long packetsReceived, long packetsLost, double lossRate,
                       long bytesReceived, double latencyMs, double jitterMs,
                       long packetsDropped, int bufferSize) {
        this.packetsReceived = packetsReceived;
        this.packetsLost = packetsLost;
        this.lossRate = lossRate;
        this.bytesReceived = bytesReceived;
        this.latencyMs = latencyMs;
        this.jitterMs = jitterMs;
        this.packetsDropped = packetsDropped;
        this.bufferSize = bufferSize;
    }

    public long getPacketsReceived() {
        return packetsReceived;
    }

    public long getReceivedPackets() {
        return packetsReceived;
    }

    public long getPacketsLost() {
        return packetsLost;
    }

    public long getLostPackets() {
        return packetsLost;
    }

    public double getLossRate() {
        return lossRate;
    }

    public double getLossPercentage() {
        return lossRate;
    }

    public double getLossPercent() {
        return lossRate;
    }

    public long getBytesReceived() {
        return bytesReceived;
    }

    public long getTotalBytes() {
        return bytesReceived;
    }

    public double getLatencyMs() {
        return latencyMs;
    }

    public double getLatency() {
        return latencyMs;
    }

    public double getJitterMs() {
        return jitterMs;
    }

    public double getJitter() {
        return jitterMs;
    }

    public long getPacketsDropped() {
        return packetsDropped;
    }

    public long getDroppedPackets() {
        return packetsDropped;
    }

    public int getBufferSize() {
        return bufferSize;
    }

    public int getCurrentBufferSize() {
        return bufferSize;
    }

    @Override
    public String toString() {
        return String.format(
            "StreamStats[Received=%d pkts, Lost=%d pkts (%.2f%%), Dropped=%d pkts, " +
            "Bytes=%d B, Latency=%.2f ms, Jitter=%.2f ms, BufferQueue=%d pkts]",
            packetsReceived, packetsLost, lossRate, packetsDropped,
            bytesReceived, latencyMs, jitterMs, bufferSize
        );
    }
}
