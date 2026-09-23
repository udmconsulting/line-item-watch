package com.udmconsulting.platform.credential.domain;

import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import java.util.Objects;
import java.util.Set;

public record ConnectionCredential(
        PlatformConnectionId connectionId,
        EncryptedSecret refreshCredential,
        Set<String> grantedScopes,
        long credentialGeneration) {

    public ConnectionCredential {
        Objects.requireNonNull(connectionId, "connectionId must not be null");
        Objects.requireNonNull(refreshCredential, "refreshCredential must not be null");
        grantedScopes = Set.copyOf(Objects.requireNonNull(grantedScopes, "grantedScopes must not be null"));
        if (credentialGeneration < 1) {
            throw new IllegalArgumentException("credentialGeneration must be positive");
        }
    }
}
