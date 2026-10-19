package com.voip.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Lệnh điều khiển nhét trong {@link RTPPacket#TYPE_CONTROL}.
 *
 * <pre>
 * 1 byte command: JOIN=1, LEAVE=2, PING=3, PONG=4
 * 4 byte sequence (dành riêng, hiện luôn = 0)
 * </pre>
 */
public class ControlMessage {

    public static final byte JOIN = 1;
    public static final byte LEAVE = 2;
    public static final byte PING = 3;
    public static final byte PONG = 4;

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

    public static ControlMessage ping() {
        return new ControlMessage(PING, 0);
    }

    public static ControlMessage pong() {
        return new ControlMessage(PONG, 0);
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
        if (command < JOIN || command > PONG) {
            return null;
        }
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
