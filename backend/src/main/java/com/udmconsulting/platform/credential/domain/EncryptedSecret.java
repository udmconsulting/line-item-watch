package com.udmconsulting.platform.credential.domain;

import java.util.Arrays;
import java.util.Objects;

public record EncryptedSecret(short cipherVersion, String keyId, byte[] nonce, byte[] ciphertext) {

    public EncryptedSecret {
        if (cipherVersion < 1) {
            throw new IllegalArgumentException("cipherVersion must be positive");
        }
        Objects.requireNonNull(keyId, "keyId must not be null");
        if (keyId.isBlank()) {
            throw new IllegalArgumentException("keyId must not be blank");
        }
        nonce = Arrays.copyOf(Objects.requireNonNull(nonce, "nonce must not be null"), nonce.length);
        ciphertext = Arrays.copyOf(
                Objects.requireNonNull(ciphertext, "ciphertext must not be null"), ciphertext.length);
    }

    @Override
    public byte[] nonce() {
        return Arrays.copyOf(nonce, nonce.length);
    }

    @Override
    public byte[] ciphertext() {
        return Arrays.copyOf(ciphertext, ciphertext.length);
    }
}
