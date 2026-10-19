package com.voip.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Small application control datagram, separate from standard RTP media packets. */
public final class ControlMessage {
    public static final byte JOIN = 1;
    public static final byte LEAVE = 2;
    public static final byte PING = 3;
    public static final byte PONG = 4;
    public static final byte ACCEPT = 5;
    public static final byte REJECT = 6;

    private static final byte[] MAGIC = {'V', 'O', 'I', 'P'};
    private static final byte VERSION = 1;
    private static final int HEADER_SIZE = 20;
    private static final int MAX_MESSAGE_BYTES = 256;

    private final byte command;
    private final int payloadType;
    private final boolean encrypted;
    private final long token;
    private final String message;

    private ControlMessage(byte command, int payloadType, boolean encrypted, long token, String message) {
        this.command = command;
        this.payloadType = payloadType;
        this.encrypted = encrypted;
        this.token = token;
        this.message = message == null ? "" : message;
    }

    public static ControlMessage join(int payloadType, boolean encrypted) {
        return new ControlMessage(JOIN, payloadType, encrypted, 0, "");
    }

    public static ControlMessage leave() {
        return new ControlMessage(LEAVE, 0, false, 0, "");
    }

    public static ControlMessage ping(long token) {
        return new ControlMessage(PING, 0, false, token, "");
    }

    public static ControlMessage pong(long token) {
        return new ControlMessage(PONG, 0, false, token, "");
    }

    public static ControlMessage accept(int payloadType, boolean encrypted) {
        return new ControlMessage(ACCEPT, payloadType, encrypted, 0, "");
    }

    public static ControlMessage reject(String message) {
        return new ControlMessage(REJECT, 0, false, 0, message);
    }

    public byte[] encode() {
        byte[] text = message.getBytes(StandardCharsets.UTF_8);
        if (text.length > MAX_MESSAGE_BYTES) {
            throw new IllegalArgumentException("Thông báo điều khiển quá dài.");
        }
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE + text.length).order(ByteOrder.BIG_ENDIAN);
        buffer.put(MAGIC);
        buffer.put(VERSION);
        buffer.put(command);
        buffer.put((byte) payloadType);
        buffer.put((byte) (encrypted ? 1 : 0));
        buffer.putLong(token);
        buffer.putShort((short) text.length);
        buffer.putShort((short) 0);
        buffer.put(text);
        return buffer.array();
    }

    public static ControlMessage decode(byte[] data, int length) {
        if (data == null || length < HEADER_SIZE || length > data.length
                || length > HEADER_SIZE + MAX_MESSAGE_BYTES) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(data, 0, length).order(ByteOrder.BIG_ENDIAN);
        for (byte expected : MAGIC) {
            if (buffer.get() != expected) {
                return null;
            }
        }
        if (buffer.get() != VERSION) {
            return null;
        }
        byte command = buffer.get();
        int payloadType = Byte.toUnsignedInt(buffer.get());
        int encryptedByte = Byte.toUnsignedInt(buffer.get());
        long token = buffer.getLong();
        int messageLength = Short.toUnsignedInt(buffer.getShort());
        buffer.getShort();
        if (command < JOIN || command > REJECT || payloadType > 127 || encryptedByte > 1
                || messageLength != length - HEADER_SIZE) {
            return null;
        }
        byte[] messageBytes = new byte[messageLength];
        buffer.get(messageBytes);
        return new ControlMessage(command, payloadType, encryptedByte == 1, token,
                new String(messageBytes, StandardCharsets.UTF_8));
    }

    public byte getCommand() {
        return command;
    }

    public int getPayloadType() {
        return payloadType;
    }

    public boolean isEncrypted() {
        return encrypted;
    }

    public long getToken() {
        return token;
    }

    public String getMessage() {
        return message;
    }
}
