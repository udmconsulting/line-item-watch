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
        boolean previousIdPresent = properties.previousKeyId() != null
                && !properties.previousKeyId().isBlank();
        boolean previousKeyPresent = properties.previousEncryptionKey() != null
                && !properties.previousEncryptionKey().isBlank();
        if (previousIdPresent != previousKeyPresent) {
            throw new IllegalStateException(
                    "hubspot.credentials previous key ID and key must be configured together");
        }
        if (previousIdPresent && properties.keyId().equals(properties.previousKeyId())) {
            throw new IllegalStateException(
                    "hubspot.credentials active and previous key IDs must differ");
        }
        try {
            AesGcmSecretProtector active = new AesGcmSecretProtector(
                    properties.keyId(), Base64.getDecoder().decode(properties.encryptionKey()));
            AesGcmSecretProtector previous = previousIdPresent
                    ? new AesGcmSecretProtector(
                            properties.previousKeyId(),
                            Base64.getDecoder().decode(properties.previousEncryptionKey()))
                    : null;
            return new KeyRingSecretProtector(active, previous);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "hubspot.credentials keys must be Base64-encoded 32-byte keys", exception);
        }
    }
}
