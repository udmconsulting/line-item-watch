package com.udmconsulting.platform.credential.infrastructure.crypto;

import com.udmconsulting.platform.credential.application.SecretContext;
import com.udmconsulting.platform.credential.application.SecretProtector;
import com.udmconsulting.platform.credential.domain.EncryptedSecret;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class AesGcmSecretProtector implements SecretProtector {

    static final short CIPHER_VERSION = 1;
    private static final int NONCE_LENGTH_BYTES = 12;
    private static final int AUTHENTICATION_TAG_BITS = 128;

    private final String keyId;
    private final SecretKeySpec key;
    private final SecureRandom secureRandom;

    public AesGcmSecretProtector(String keyId, byte[] keyBytes) {
        this(keyId, keyBytes, new SecureRandom());
    }

    AesGcmSecretProtector(String keyId, byte[] keyBytes, SecureRandom secureRandom) {
        this.keyId = Objects.requireNonNull(keyId, "keyId must not be null");
        if (keyId.isBlank()) {
            throw new IllegalArgumentException("keyId must not be blank");
        }
        Objects.requireNonNull(keyBytes, "keyBytes must not be null");
        if (keyBytes.length != 32) {
            throw new IllegalArgumentException("AES-256 key must contain exactly 32 bytes");
        }
        this.key = new SecretKeySpec(keyBytes.clone(), "AES");
        this.secureRandom = Objects.requireNonNull(secureRandom);
    }

    @Override
    public EncryptedSecret protect(String plaintext, SecretContext context) {
        Objects.requireNonNull(plaintext, "plaintext must not be null");
        byte[] nonce = new byte[NONCE_LENGTH_BYTES];
        secureRandom.nextBytes(nonce);
        try {
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, nonce, context, CIPHER_VERSION, keyId);
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return new EncryptedSecret(CIPHER_VERSION, keyId, nonce, ciphertext);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Could not encrypt connection credential", exception);
        }
    }

    @Override
    public String reveal(EncryptedSecret encryptedSecret, SecretContext context) {
        Objects.requireNonNull(encryptedSecret, "encryptedSecret must not be null");
        if (encryptedSecret.cipherVersion() != CIPHER_VERSION || !keyId.equals(encryptedSecret.keyId())) {
            throw new IllegalStateException("Unsupported connection credential encryption metadata");
        }
        try {
            Cipher cipher = cipher(
                    Cipher.DECRYPT_MODE,
                    encryptedSecret.nonce(),
                    context,
                    encryptedSecret.cipherVersion(),
                    encryptedSecret.keyId());
            return new String(cipher.doFinal(encryptedSecret.ciphertext()), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Could not decrypt connection credential", exception);
        }
    }

    private Cipher cipher(
            int mode, byte[] nonce, SecretContext context, short cipherVersion, String activeKeyId)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(AUTHENTICATION_TAG_BITS, nonce));
        cipher.updateAAD(aad(context, cipherVersion, activeKeyId));
        return cipher;
    }

    private static byte[] aad(SecretContext context, short cipherVersion, String keyId) {
        String value = cipherVersion + "|" + keyId + "|" + context.provider().name()
                + "|" + context.connectionId().value();
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
