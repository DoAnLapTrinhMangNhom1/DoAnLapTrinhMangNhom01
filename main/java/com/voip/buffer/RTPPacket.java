package com.voip.buffer;

import java.util.Arrays;

/**
 * RTPPacket represents a received and decoded RTP packet containing
 * sequence number, timestamp, and PCM audio payload.
 */
public class RTPPacket implements Comparable<RTPPacket> {
    public int sequenceNumber;
    public int sequence; // alias for sequenceNumber
    public long timestamp;
    public byte[] data;
    public byte[] payload; // alias for data

    public RTPPacket() {
    }

    public RTPPacket(int sequenceNumber, long timestamp, byte[] data) {
        this.sequenceNumber = sequenceNumber;
        this.sequence = sequenceNumber;
        this.timestamp = timestamp;
        this.data = data;
        this.payload = data;
    }

    public RTPPacket(int sequenceNumber, byte[] data) {
        this(sequenceNumber, System.currentTimeMillis(), data);
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public void setSequenceNumber(int sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
        this.sequence = sequenceNumber;
    }

    public int getSequence() {
        return sequenceNumber;
    }

    public void setSequence(int sequence) {
        this.sequenceNumber = sequence;
        this.sequence = sequence;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data) {
        this.data = data;
        this.payload = data;
    }

    public byte[] getPayload() {
        return data;
    }

    public void setPayload(byte[] payload) {
        this.data = payload;
        this.payload = payload;
    }

    @Override
    public int compareTo(RTPPacket other) {
        if (other == null) {
            return 1;
        }
        return JitterBuffer.compareSequence(this.sequenceNumber, other.sequenceNumber);
    }

    @Override
    public String toString() {
        return "RTPPacket{" +
                "seq=" + sequenceNumber +
                ", ts=" + timestamp +
                ", len=" + (data != null ? data.length : 0) +
                '}';
    }
}
