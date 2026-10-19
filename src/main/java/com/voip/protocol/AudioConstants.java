package com.voip.protocol;

/**
 * Hợp đồng âm thanh / gói tin dùng chung cho cả nhóm.
 * Không được đổi một mình — Client, Jitter Buffer và UI đều phụ thuộc các giá trị này.
 */
public final class AudioConstants {

    public static final float SAMPLE_RATE = 16000.0f;
    public static final int SAMPLE_SIZE_BITS = 16;
    public static final int CHANNELS = 1;
    public static final boolean SIGNED = true;
    public static final boolean BIG_ENDIAN = true;

    /** Mỗi gói chứa 20 ms PCM. */
    public static final int FRAME_MS = 20;

    /** 16000 Hz * 2 byte * 1 kênh * 0.02 s = 640 byte. */
    public static final int PCM_FRAME_BYTES =
            ((int) SAMPLE_RATE) * (SAMPLE_SIZE_BITS / 8) * CHANNELS * FRAME_MS / 1000;

    public static final int DEFAULT_UDP_PORT = 8888;

    /** Cửa sổ tua lại (DVR) phía Server. */
    public static final int HISTORY_MS = 30_000;

    public static final int HISTORY_PACKET_COUNT = HISTORY_MS / FRAME_MS;

    private AudioConstants() {
    }s
}
