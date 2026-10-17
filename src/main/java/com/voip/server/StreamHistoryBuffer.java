package com.voip.server;

import com.voip.protocol.AudioConstants;
import com.voip.protocol.RTPPacket;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Bộ đệm vòng ~30 giây các gói RTP live.
 * Dùng cho tua lại (DVR): Client SEEK theo sequence, Server gửi lại TYPE_HISTORY.
 * Không phải Jitter Buffer (Jitter chỉ 50–100 ms phía Client).
 */
public class StreamHistoryBuffer {

    private final int capacity;
    private final ArrayDeque<RTPPacket> packets = new ArrayDeque<>();

    public StreamHistoryBuffer() {
        this(AudioConstants.HISTORY_PACKET_COUNT);
    }

    public StreamHistoryBuffer(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    public synchronized void add(RTPPacket packet) {
        if (packet == null) {
            return;
        }
        packets.addLast(packet);
        while (packets.size() > capacity) {
            packets.removeFirst();
        }
    }

    /**
     * Lấy các gói từ {@code fromSeq} đến mới nhất, clone type = HISTORY.
     * Sequence 16-bit được so sánh theo wrap-around.
     */
    public synchronized List<RTPPacket> getFrom(int fromSeq) {
        List<RTPPacket> result = new ArrayList<>();
        for (RTPPacket packet : packets) {
            if (seqDiff(packet.getSequence(), fromSeq) >= 0) {
                result.add(packet.withType(RTPPacket.TYPE_HISTORY));
            }
        }
        return result;
    }

    public synchronized int oldestSeq() {
        RTPPacket first = packets.peekFirst();
        return first == null ? 0 : first.getSequence();
    }

    public synchronized int newestSeq() {
        RTPPacket last = packets.peekLast();
        return last == null ? 0 : last.getSequence();
    }

    public synchronized int size() {
        return packets.size();
    }

    public synchronized int durationMs() {
        return packets.size() * AudioConstants.FRAME_MS;
    }

    public synchronized void clear() {
        packets.clear();
    }

    /** Hiệu signed 16-bit: dương nghĩa a nằm sau b trên vòng sequence. */
    static int seqDiff(int a, int b) {
        return (short) ((a & 0xFFFF) - (b & 0xFFFF));
    }
}
