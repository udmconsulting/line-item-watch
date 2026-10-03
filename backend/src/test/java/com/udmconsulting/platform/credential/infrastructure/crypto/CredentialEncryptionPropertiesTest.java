package com.udmconsulting.platform.credential.infrastructure.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CredentialEncryptionPropertiesTest {

    @Test
    void renderingRedactsEncryptionKey() {
        CredentialEncryptionProperties properties =
                new CredentialEncryptionProperties(
                        "key-1", "do-not-render-encryption-key",
                        "key-0", "do-not-render-previous-key");

        assertThat(properties.toString())
                .contains("encryptionKey=<redacted>")
                .contains("previousEncryptionKey=<redacted>")
                .doesNotContain("do-not-render-encryption-key")
                .doesNotContain("do-not-render-previous-key");
    }
}
