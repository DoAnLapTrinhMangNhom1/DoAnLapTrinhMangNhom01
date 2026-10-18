package com.voip.buffer;

import com.voip.protocol.RTPPacket;
import com.voip.protocol.AudioConstants;

import java.util.TreeMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Jitter Buffer để làm mượt luồng audio bằng cách:
 * 1. Reorder gói theo sequence number
 * 2. Buffer gói trong một khoảng thời gian (50ms, 75ms, 100ms)
 * 3. Xử lý gói bị mất (gap)
 */
public class JitterBuffer {
    private TreeMap<Integer, RTPPacket> buffer;
    private int nextExpectedSequence;
    private int bufferSizeMs;
    private int maxPackets;
    private final ReentrantLock lock;

    /**
     * Constructor với buffer size mặc định 50ms
     */
    public JitterBuffer() {
        this(50);
    }

    /**
     * Constructor với buffer size tùy chỉnh
     * @param bufferSizeMs Thời gian buffer (ms): 50, 75, 100
     */
    public JitterBuffer(int bufferSizeMs) {
        this.buffer = new TreeMap<>();
        this.nextExpectedSequence = 0;
        this.bufferSizeMs = bufferSizeMs;
        this.maxPackets = bufferSizeMs / AudioConstants.FRAME_MS;
        this.lock = new ReentrantLock();
    }

    /**
     * Thêm gói RTP vào buffer
     * @param packet Gói RTP cần thêm
     */
    public void addPacket(RTPPacket packet) {
        if (packet == null) {
            return;
        }

        lock.lock();
        try {
            int seq = packet.getSequence();

            // Xử lý trường hợp sequence number tràn (0xFFFF -> 0)
            if (seq < nextExpectedSequence && (nextExpectedSequence - seq) > 1000) {
                // Reset khi sequence quay lại từ 0
                nextExpectedSequence = seq;
            }

            // Nếu gói quá cũ (trước nextExpectedSequence quá nhiều), bỏ qua
            if (seq < nextExpectedSequence && (nextExpectedSequence - seq) > 100) {
                return;
            }

            // Thêm vào buffer
            buffer.put(seq, packet);

            // Giới hạn kích thước buffer
            while (buffer.size() > maxPackets) {
                buffer.pollFirstEntry();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Lấy gói tiếp theo để phát (theo thứ tự sequence)
     * @return Gói RTP tiếp theo, hoặc null nếu buffer trống
     */
    public RTPPacket getNextPacket() {
        lock.lock();
        try {
            // Nếu buffer trống, trả về null
            if (buffer.isEmpty()) {
                return null;
            }

            // Kiểm tra gói tiếp theo có trong buffer không
            RTPPacket packet = buffer.get(nextExpectedSequence);

            if (packet != null) {
                // Có gói mong muốn, lấy ra và cập nhật nextExpectedSequence
                buffer.remove(nextExpectedSequence);
                nextExpectedSequence = (nextExpectedSequence + 1) & 0xFFFF;
                return packet;
            } else {
                // Không có gói mong muốn, kiểm tra có gói nào khác không
                Map.Entry<Integer, RTPPacket> firstEntry = buffer.firstEntry();

                if (firstEntry != null) {
                    int firstSeq = firstEntry.getKey();

                    // Nếu gói đầu tiên lệch quá xa so với mong muốn, skip
                    if ((firstSeq - nextExpectedSequence) > 10) {
                        // Skip gap, cập nhật nextExpectedSequence
                        nextExpectedSequence = firstSeq;
                        buffer.remove(firstSeq);
                        nextExpectedSequence = (nextExpectedSequence + 1) & 0xFFFF;
                        return firstEntry.getValue();
                    }

                    // Chờ thêm (gap nhỏ)
                    return null;
                }
            }

            return null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Đặt lại buffer (xóa tất cả gói, reset sequence)
     */
    public void clear() {
        lock.lock();
        try {
            buffer.clear();
            nextExpectedSequence = 0;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Cập nhật kích thước buffer
     * @param bufferSizeMs Thời gian buffer mới (ms)
     */
    public void setBufferSizeMs(int bufferSizeMs) {
        lock.lock();
        try {
            this.bufferSizeMs = bufferSizeMs;
            this.maxPackets = bufferSizeMs / AudioConstants.FRAME_MS;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Lấy kích thước buffer hiện tại (ms)
     */
    public int getBufferSizeMs() {
        return bufferSizeMs;
    }

    /**
     * Lấy số gói đang có trong buffer
     */
    public int size() {
        lock.lock();
        try {
            return buffer.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Kiểm tra buffer có trống không
     */
    public boolean isEmpty() {
        lock.lock();
        try {
            return buffer.isEmpty();
        } finally {
            lock.unlock();
        }
    }
}
