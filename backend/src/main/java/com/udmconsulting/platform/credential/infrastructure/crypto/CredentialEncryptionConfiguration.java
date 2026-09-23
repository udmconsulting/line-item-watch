package com.udmconsulting.platform.credential.infrastructure.crypto;

import com.udmconsulting.platform.credential.application.SecretProtector;
import java.util.Base64;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class CredentialEncryptionConfiguration {

    @Bean
    SecretProtector secretProtector(CredentialEncryptionProperties properties) {
        if (properties.keyId() == null || properties.keyId().isBlank()) {
            throw new IllegalStateException("hubspot.credentials.key-id is required");
        }
        if (properties.encryptionKey() == null || properties.encryptionKey().isBlank()) {
            throw new IllegalStateException("hubspot.credentials.encryption-key is required");
        }
        try {
            return new AesGcmSecretProtector(
                    properties.keyId(), Base64.getDecoder().decode(properties.encryptionKey()));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "hubspot.credentials.encryption-key must be a Base64-encoded 32-byte key", exception);
        }
    }
}
