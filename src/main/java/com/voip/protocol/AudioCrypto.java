package com.voip.protocol;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;

/** Optional AES-CTR payload encryption. The same non-empty voip.key is required at both ends. */
public final class AudioCrypto {
    private static final byte[] IV_DOMAIN = "VOIP-CTR".getBytes(StandardCharsets.US_ASCII);

    private AudioCrypto() {
    }

    public static byte[] transform(byte[] data, String passphrase, int sequence, int timestamp)
            throws GeneralSecurityException {
        if (passphrase == null || passphrase.isBlank()) {
            return data.clone();
        }
        byte[] key = MessageDigest.getInstance("SHA-256")
                .digest(passphrase.getBytes(StandardCharsets.UTF_8));
        ByteBuffer iv = ByteBuffer.allocate(16);
        iv.putInt(timestamp);
        iv.putShort((short) (sequence & 0xFFFF));
        iv.put(IV_DOMAIN);
        iv.putShort((short) 1);
        Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                new IvParameterSpec(iv.array()));
        return cipher.doFinal(data);
    }
}
