package com.udmconsulting.platform.credential.infrastructure.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.connection.domain.Provider;
import com.udmconsulting.platform.credential.application.SecretContext;
import com.udmconsulting.platform.credential.domain.EncryptedSecret;
import org.junit.jupiter.api.Test;

class KeyRingSecretProtectorTest {

    private final AesGcmSecretProtector active =
            new AesGcmSecretProtector("active-2", repeated((byte) 2));
    private final AesGcmSecretProtector previous =
            new AesGcmSecretProtector("previous-1", repeated((byte) 1));
    private final KeyRingSecretProtector ring = new KeyRingSecretProtector(active, previous);
    private final SecretContext context =
            new SecretContext(Provider.HUBSPOT, PlatformConnectionId.newId());

    @Test
    void decryptsPreviousKeyButEncryptsOnlyWithActiveKey() {
        EncryptedSecret old = previous.protect("credential-material", context);

        assertThat(ring.reveal(old, context)).isEqualTo("credential-material");
        assertThat(ring.protect("replacement", context).keyId()).isEqualTo("active-2");
        assertThat(ring.activeKeyId()).isEqualTo("active-2");
        assertThat(ring.previousKeyId()).contains("previous-1");
    }

    @Test
    void unknownKeyIdFailsWithoutTryingAnotherKey() {
        EncryptedSecret encrypted = previous.protect("must-not-leak", context);
        EncryptedSecret unknown = new EncryptedSecret(
                encrypted.cipherVersion(), "unknown", encrypted.nonce(), encrypted.ciphertext());

        assertThatThrownBy(() -> ring.reveal(unknown, context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Unknown connection credential encryption key ID")
                .hasMessageNotContaining("must-not-leak");
    }

    @Test
    void rejectsAmbiguousKeyIds() {
        assertThatThrownBy(() -> new KeyRingSecretProtector(
                        active, new AesGcmSecretProtector("active-2", repeated((byte) 3))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] repeated(byte value) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, value);
        return bytes;
    }
}
