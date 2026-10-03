package com.udmconsulting.platform.credential.infrastructure.crypto;

import com.udmconsulting.platform.credential.application.SecretContext;
import com.udmconsulting.platform.credential.application.SecretProtector;
import com.udmconsulting.platform.credential.domain.EncryptedSecret;
import java.util.Objects;
import java.util.Optional;

/** A bounded two-key ring. Encryption always uses active; decryption uses an exact key ID. */
public final class KeyRingSecretProtector implements SecretProtector {

    private final AesGcmSecretProtector active;
    private final AesGcmSecretProtector previous;

    public KeyRingSecretProtector(
            AesGcmSecretProtector active, AesGcmSecretProtector previous) {
        this.active = Objects.requireNonNull(active);
        this.previous = previous;
        if (previous != null && active.activeKeyId().equals(previous.activeKeyId())) {
            throw new IllegalArgumentException("Active and previous key IDs must differ");
        }
    }

    @Override
    public EncryptedSecret protect(String plaintext, SecretContext context) {
        return active.protect(plaintext, context);
    }

    @Override
    public String reveal(EncryptedSecret encryptedSecret, SecretContext context) {
        Objects.requireNonNull(encryptedSecret, "encryptedSecret must not be null");
        if (active.activeKeyId().equals(encryptedSecret.keyId())) {
            return active.reveal(encryptedSecret, context);
        }
        if (previous != null && previous.activeKeyId().equals(encryptedSecret.keyId())) {
            return previous.reveal(encryptedSecret, context);
        }
        throw new IllegalStateException("Unknown connection credential encryption key ID");
    }

    @Override
    public String activeKeyId() {
        return active.activeKeyId();
    }

    @Override
    public Optional<String> previousKeyId() {
        return Optional.ofNullable(previous).map(AesGcmSecretProtector::activeKeyId);
    }
}
