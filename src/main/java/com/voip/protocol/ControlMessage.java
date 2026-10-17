package com.voip.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Lệnh điều khiển nhét trong {@link RTPPacket#TYPE_CONTROL}.
 *
 * <pre>
 * 1 byte command: JOIN=1, LEAVE=2, SEEK=3, GO_LIVE=4
 * 4 byte sequence (chỉ SEEK dùng; các lệnh khác = 0)
 * </pre>
 */
public class ControlMessage {

    public static final byte JOIN = 1;
    public static final byte LEAVE = 2;
    public static final byte SEEK = 3;
    public static final byte GO_LIVE = 4;

    private static final int SIZE = 5;

    private final byte command;
    private final int sequence;

    public ControlMessage(byte command, int sequence) {
        this.command = command;
        this.sequence = sequence;
    }

    public static ControlMessage join() {
        return new ControlMessage(JOIN, 0);
    }

    public static ControlMessage leave() {
        return new ControlMessage(LEAVE, 0);
    }

    public static ControlMessage seek(int fromSequence) {
        return new ControlMessage(SEEK, fromSequence);
    }

    public static ControlMessage goLive() {
        return new ControlMessage(GO_LIVE, 0);
    }

    public byte[] encode() {
        ByteBuffer buffer = ByteBuffer.allocate(SIZE).order(ByteOrder.BIG_ENDIAN);
        buffer.put(command);
        buffer.putInt(sequence);
        return buffer.array();
    }

    public static ControlMessage decode(byte[] payload) {
        if (payload == null || payload.length < SIZE) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        byte command = buffer.get();
        int sequence = buffer.getInt();
        return new ControlMessage(command, sequence);
    }

    /** Bọc thành gói UDP để Client gửi cho Server. */
    public RTPPacket toRtpPacket() {
        byte[] payload = encode();
        return new RTPPacket(RTPPacket.TYPE_CONTROL, 0, 0, payload, payload.length);
    }

    public static ControlMessage fromRtp(RTPPacket packet) {
        if (packet == null || packet.getType() != RTPPacket.TYPE_CONTROL) {
            return null;
        }
        return decode(packet.getPayload());
    }

    public byte getCommand() {
        return command;
    }

    public int getSequence() {
        return sequence;
    }
}
