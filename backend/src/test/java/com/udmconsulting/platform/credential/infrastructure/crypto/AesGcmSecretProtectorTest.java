package com.udmconsulting.platform.credential.infrastructure.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.connection.domain.Provider;
import com.udmconsulting.platform.credential.application.SecretContext;
import com.udmconsulting.platform.credential.domain.EncryptedSecret;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AesGcmSecretProtectorTest {

    private final AesGcmSecretProtector protector =
            new AesGcmSecretProtector("key-1", new byte[32]);

    @Test
    void encryptsWithFreshNoncesAndRoundTripsWithoutPlaintextPersistence() {
        SecretContext context = new SecretContext(Provider.HUBSPOT, PlatformConnectionId.newId());

        EncryptedSecret first = protector.protect("refresh-secret", context);
        EncryptedSecret second = protector.protect("refresh-secret", context);

        assertThat(first.nonce()).hasSize(12).isNotEqualTo(second.nonce());
        assertThat(new String(first.ciphertext(), StandardCharsets.UTF_8))
                .doesNotContain("refresh-secret");
        assertThat(protector.reveal(first, context)).isEqualTo("refresh-secret");
    }

    @Test
    void authenticatedContextPreventsCrossConnectionDecryption() {
        SecretContext owner = new SecretContext(Provider.HUBSPOT, PlatformConnectionId.newId());
        EncryptedSecret encrypted = protector.protect("refresh-secret", owner);

        SecretContext other = new SecretContext(Provider.HUBSPOT, PlatformConnectionId.newId());

        assertThatThrownBy(() -> protector.reveal(encrypted, other))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Could not decrypt connection credential");
    }

    @Test
    void wrongKeyCannotDecrypt() {
        SecretContext context = new SecretContext(Provider.HUBSPOT, PlatformConnectionId.newId());
        EncryptedSecret encrypted = protector.protect("refresh-secret", context);
        AesGcmSecretProtector wrongKey = new AesGcmSecretProtector("key-1", filledKey((byte) 7));

        assertThatThrownBy(() -> wrongKey.reveal(encrypted, context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Could not decrypt connection credential");
    }

    @Test
    void tamperedNonceCannotDecrypt() {
        SecretContext context = new SecretContext(Provider.HUBSPOT, PlatformConnectionId.newId());
        EncryptedSecret encrypted = protector.protect("refresh-secret", context);
        byte[] nonce = encrypted.nonce();
        nonce[0] ^= 1;
        EncryptedSecret tampered = new EncryptedSecret(
                encrypted.cipherVersion(), encrypted.keyId(), nonce, encrypted.ciphertext());

        assertThatThrownBy(() -> protector.reveal(tampered, context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Could not decrypt connection credential");
    }

    @Test
    void tamperedCiphertextOrTagCannotDecrypt() {
        SecretContext context = new SecretContext(Provider.HUBSPOT, PlatformConnectionId.newId());
        EncryptedSecret encrypted = protector.protect("refresh-secret", context);
        byte[] ciphertext = encrypted.ciphertext();
        ciphertext[ciphertext.length - 1] ^= 1;
        EncryptedSecret tampered = new EncryptedSecret(
                encrypted.cipherVersion(), encrypted.keyId(), encrypted.nonce(), ciphertext);

        assertThatThrownBy(() -> protector.reveal(tampered, context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Could not decrypt connection credential");
    }

    @Test
    void requiresExactlyA256BitKey() {
        assertThatThrownBy(() -> new AesGcmSecretProtector("key-1", new byte[31]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
    }

    private static byte[] filledKey(byte value) {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, value);
        return key;
    }
}
