package com.voip.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Gói RTP rút gọn dùng cho UDP audio.
 *
 * <pre>
 * Offset  Size  Field
 * 0       1     version = 1
 * 1       1     type    LIVE=1 | HISTORY=2 | CONTROL=3
 * 2       2     sequence (unsigned short, big-endian)
 * 4       4     timestampMs (int, big-endian)
 * 8       4     payloadLength
 * 12      N     payload
 * </pre>
 */
public class RTPPacket {

    public static final byte VERSION = 1;
    public static final byte TYPE_LIVE = 1;
    public static final byte TYPE_HISTORY = 2;
    public static final byte TYPE_CONTROL = 3;
    public static final int HEADER_SIZE = 12;

    private final byte version;
    private final byte type;
    private final int sequence;
    private final int timestampMs;
    private final byte[] payload;

    public RTPPacket(byte type, int sequence, int timestampMs, byte[] payload, int payloadLength) {
        this.version = VERSION;
        this.type = type;
        this.sequence = sequence & 0xFFFF;
        this.timestampMs = timestampMs;
        if (payload == null || payloadLength <= 0) {
            this.payload = new byte[0];
        } else {
            this.payload = Arrays.copyOf(payload, payloadLength);
        }
    }

    public byte[] encode() {
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE + payload.length).order(ByteOrder.BIG_ENDIAN);
        buffer.put(version);
        buffer.put(type);
        buffer.putShort((short) sequence);
        buffer.putInt(timestampMs);
        buffer.putInt(payload.length);
        buffer.put(payload);
        return buffer.array();
    }

    /**
     * @return packet hợp lệ, hoặc {@code null} nếu dữ liệu hỏng / không đủ header
     */
    public static RTPPacket decode(byte[] raw, int length) {
        if (raw == null || length < HEADER_SIZE) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(raw, 0, length).order(ByteOrder.BIG_ENDIAN);
        byte version = buffer.get();
        if (version != VERSION) {
            return null;
        }
        byte type = buffer.get();
        int sequence = Short.toUnsignedInt(buffer.getShort());
        int timestampMs = buffer.getInt();
        int payloadLength = buffer.getInt();
        if (payloadLength < 0 || HEADER_SIZE + payloadLength > length) {
            return null;
        }
        byte[] payload = new byte[payloadLength];
        buffer.get(payload);
        return new RTPPacket(type, sequence, timestampMs, payload, payloadLength);
    }

    public RTPPacket withType(byte newType) {
        return new RTPPacket(newType, sequence, timestampMs, payload, payload.length);
    }

    public byte getVersion() {
        return version;
    }

    public byte getType() {
        return type;
    }

    public int getSequence() {
        return sequence;
    }

    public int getTimestampMs() {
        return timestampMs;
    }

    public byte[] getPayload() {
        return payload;
    }

    public int getPayloadLength() {
        return payload.length;
    }
}
