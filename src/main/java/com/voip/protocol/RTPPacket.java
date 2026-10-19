package com.voip.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** RTP version 2 packet with a fixed 12-byte header and no CSRC list or extensions. */
public final class RTPPacket {
    public static final int HEADER_SIZE = 12;
    public static final int PAYLOAD_TYPE_ULAW = 0;
    public static final int PAYLOAD_TYPE_PCM = 96;
    private static final int VERSION = 2;

    private final int payloadType;
    private final int sequence;
    private final int timestamp;
    private final long ssrc;
    private final byte[] payload;
    private final byte[] headerBytes;
    private final byte[] paddingBytes;

    public RTPPacket(int payloadType, int sequence, int timestamp, long ssrc, byte[] payload) {
        if (payloadType < 0 || payloadType > 127 || payload == null) {
            throw new IllegalArgumentException("Thông tin RTP không hợp lệ.");
        }
        this.payloadType = payloadType;
        this.sequence = sequence & 0xFFFF;
        this.timestamp = timestamp;
        this.ssrc = ssrc & 0xFFFF_FFFFL;
        this.payload = payload.clone();
        this.headerBytes = createHeader(payloadType, this.sequence, timestamp, this.ssrc);
        this.paddingBytes = new byte[0];
    }

    private RTPPacket(int payloadType, int sequence, int timestamp, long ssrc,
                      byte[] payload, byte[] headerBytes, byte[] paddingBytes) {
        this.payloadType = payloadType;
        this.sequence = sequence;
        this.timestamp = timestamp;
        this.ssrc = ssrc;
        this.payload = payload;
        this.headerBytes = headerBytes;
        this.paddingBytes = paddingBytes;
    }

    public byte[] encode() {
        byte[] packet = new byte[headerBytes.length + payload.length + paddingBytes.length];
        ByteBuffer buffer = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN);
        buffer.put(headerBytes);
        buffer.put(payload);
        buffer.put(paddingBytes);
        return packet;
    }

    public byte[] getHeaderBytes() {
        return headerBytes.clone();
    }

    private static byte[] createHeader(int payloadType, int sequence, int timestamp, long ssrc) {
        ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
        header.put((byte) (VERSION << 6));
        header.put((byte) payloadType);
        header.putShort((short) sequence);
        header.putInt(timestamp);
        header.putInt((int) ssrc);
        return header.array();
    }

    public static RTPPacket decode(byte[] raw, int length) {
        if (raw == null || length < HEADER_SIZE || length > raw.length) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(raw, 0, length).order(ByteOrder.BIG_ENDIAN);
        int first = Byte.toUnsignedInt(buffer.get());
        int version = first >>> 6;
        boolean hasPadding = (first & 0x20) != 0;
        boolean hasExtension = (first & 0x10) != 0;
        int csrcCount = first & 0x0F;
        if (version != VERSION || length < HEADER_SIZE + csrcCount * Integer.BYTES) {
            return null;
        }
        int payloadType = Byte.toUnsignedInt(buffer.get()) & 0x7F;
        int sequence = Short.toUnsignedInt(buffer.getShort());
        int timestamp = buffer.getInt();
        long ssrc = Integer.toUnsignedLong(buffer.getInt());
        int headerLength = HEADER_SIZE + csrcCount * Integer.BYTES;
        if (hasExtension) {
            if (length < headerLength + 4) {
                return null;
            }
            int extensionWords = Short.toUnsignedInt(
                    ByteBuffer.wrap(raw, headerLength + 2, 2).order(ByteOrder.BIG_ENDIAN).getShort());
            long extendedHeaderLength = (long) headerLength + 4L + (long) extensionWords * Integer.BYTES;
            if (extendedHeaderLength > length) {
                return null;
            }
            headerLength = (int) extendedHeaderLength;
        }
        int payloadEnd = length;
        if (hasPadding) {
            int paddingLength = Byte.toUnsignedInt(raw[length - 1]);
            if (paddingLength == 0 || paddingLength > length - headerLength) {
                return null;
            }
            payloadEnd -= paddingLength;
        }
        buffer.position(headerLength);
        byte[] payload = new byte[payloadEnd - headerLength];
        buffer.get(payload);
        return new RTPPacket(payloadType, sequence, timestamp, ssrc, payload,
                Arrays.copyOf(raw, headerLength), Arrays.copyOfRange(raw, payloadEnd, length));
    }

    public int getPayloadType() {
        return payloadType;
    }

    public int getSequence() {
        return sequence;
    }

    public int getTimestamp() {
        return timestamp;
    }

    public long getSsrc() {
        return ssrc;
    }

    public byte[] getPayload() {
        return Arrays.copyOf(payload, payload.length);
    }

    public int getPayloadLength() {
        return payload.length;
    }
}
