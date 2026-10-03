package com.udmconsulting.platform.credential.infrastructure.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("hubspot.credentials")
public record CredentialEncryptionProperties(
        String keyId,
        String encryptionKey,
        String previousKeyId,
        String previousEncryptionKey) {

    @Override
    public String toString() {
        return "CredentialEncryptionProperties[keyId=" + keyId
                + ", encryptionKey=<redacted>, previousKeyId=" + previousKeyId
                + ", previousEncryptionKey=<redacted>]";
    }
}
