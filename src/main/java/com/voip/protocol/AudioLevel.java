package com.voip.protocol;

public final class AudioLevel {
    private AudioLevel() {
    }

    public static int rmsPercent(byte[] pcm, int length) {
        if (pcm == null || length < 2 || length > pcm.length) {
            return 0;
        }

        long sumOfSquares = 0;
        int sampleCount = 0;
        // PCM 16-bit gom hai byte moi mau; thu tu byte lay theo cau hinh am thanh.
        for (int i = 0; i + 1 < length; i += 2) {
            int sample = AudioConstants.BIG_ENDIAN
                    ? (short) (((pcm[i] & 0xFF) << 8) | (pcm[i + 1] & 0xFF))
                    : (short) (((pcm[i + 1] & 0xFF) << 8) | (pcm[i] & 0xFF));
            sumOfSquares += (long) sample * sample;
            sampleCount++;
        }
        if (sampleCount == 0) {
            return 0;
        }

        // RMS uoc luong muc tin hieu; gia tri duoc doi sang thang hien thi 0-100%.
        double rms = Math.sqrt((double) sumOfSquares / sampleCount);
        if (rms == 0) {
            return 0;
        }
        int level = (int) Math.round(
                (20 * Math.log10(rms / Short.MAX_VALUE) + 60) * 100 / 60);
        return Math.max(0, Math.min(100, level));
    }
}
