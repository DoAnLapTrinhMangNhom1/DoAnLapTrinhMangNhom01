package com.voip.protocol;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/** AES-GCM payload protection. A blank passphrase disables encryption. */
public final class AudioCrypto {
    private static final byte[] SALT = "voip-audio-demo-v2".getBytes(StandardCharsets.US_ASCII);
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int ITERATIONS = 120_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKeySpec key;

    public AudioCrypto(String passphrase) throws GeneralSecurityException {
        if (passphrase == null || passphrase.isBlank()) {
            key = null;
            return;
        }
        // Hai dau dung cung salt/thuat toan de dan xuat cung AES key tu passphrase.
        char[] password = passphrase.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(password, SALT, ITERATIONS, 256);
        try {
            byte[] derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
            key = new SecretKeySpec(derived, "AES");
            Arrays.fill(derived, (byte) 0);
        } finally {
            spec.clearPassword();
            Arrays.fill(password, '\0');
        }
    }

    public boolean isEnabled() {
        return key != null;
    }

    public byte[] encrypt(byte[] plaintext, byte[] authenticatedHeader) throws GeneralSecurityException {
        if (key == null) {
            return plaintext.clone();
        }
        // Nonce ngau nhien phai duy nhat theo khoa; nonce duoc gui kem ciphertext.
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(authenticatedHeader);
        byte[] ciphertext = cipher.doFinal(plaintext);
        ByteBuffer result = ByteBuffer.allocate(nonce.length + ciphertext.length);
        result.put(nonce).put(ciphertext);
        return result.array();
    }

    public byte[] decrypt(byte[] encrypted, byte[] authenticatedHeader)
            throws GeneralSecurityException {
        if (key == null) {
            return encrypted.clone();
        }
        // GCM nem AEADBadTagException neu khoa, payload hoac header xac thuc khong khop.
        if (encrypted.length < NONCE_BYTES + TAG_BITS / 8) {
            throw new AEADBadTagException("Goi ma hoa qua ngan.");
        }
        byte[] nonce = Arrays.copyOf(encrypted, NONCE_BYTES);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(authenticatedHeader);
        return cipher.doFinal(encrypted, NONCE_BYTES, encrypted.length - NONCE_BYTES);
    }
}
