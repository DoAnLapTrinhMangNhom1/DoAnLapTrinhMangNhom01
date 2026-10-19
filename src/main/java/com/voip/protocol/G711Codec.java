package com.voip.protocol;

/** G.711 mu-law codec for signed 16-bit, big-endian PCM samples. */
public final class G711Codec {
    private static final int BIAS = 0x84;
    private static final int CLIP = 32635;
    private static final int[] SEGMENT_END = {
            0xFF, 0x1FF, 0x3FF, 0x7FF, 0xFFF, 0x1FFF, 0x3FFF, 0x7FFF
    };

    private G711Codec() {
    }

    public static byte[] encode(byte[] pcm) {
        if (pcm == null || (pcm.length & 1) != 0) {
            throw new IllegalArgumentException("PCM 16-bit phai co so byte chan.");
        }
        // Moi mau PCM 16-bit duoc nen thanh mot byte mu-law.
        byte[] encoded = new byte[pcm.length / 2];
        for (int i = 0; i < encoded.length; i++) {
            int sample = (short) (((pcm[i * 2] & 0xFF) << 8) | (pcm[i * 2 + 1] & 0xFF));
            encoded[i] = encodeSample(sample);
        }
        return encoded;
    }

    public static byte[] decode(byte[] ulaw) {
        byte[] pcm = new byte[ulaw.length * 2];
        for (int i = 0; i < ulaw.length; i++) {
            int sample = decodeSample(ulaw[i]);
            pcm[i * 2] = (byte) (sample >>> 8);
            pcm[i * 2 + 1] = (byte) sample;
        }
        return pcm;
    }

    private static byte encodeSample(int sample) {
        int mask;
        if (sample < 0) {
            sample = -sample;
            mask = 0x7F;
        } else {
            mask = 0xFF;
        }
        sample = Math.min(sample, CLIP) + BIAS;
        // Segment bieu dien bien do; 4 bit con lai giu phan dinh tri cua mau.
        int segment = 0;
        while (segment < SEGMENT_END.length && sample > SEGMENT_END[segment]) {
            segment++;
        }
        int encoded = segment >= SEGMENT_END.length
                ? 0x7F
                : (segment << 4) | ((sample >> (segment + 3)) & 0x0F);
        return (byte) (encoded ^ mask);
    }

    private static int decodeSample(byte encoded) {
        int value = (~encoded) & 0xFF;
        int sample = ((value & 0x0F) << 3) + BIAS;
        sample <<= (value & 0x70) >> 4;
        return (value & 0x80) != 0 ? BIAS - sample : sample - BIAS;
    }
}
