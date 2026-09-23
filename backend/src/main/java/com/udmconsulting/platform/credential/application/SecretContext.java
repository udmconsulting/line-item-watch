package com.udmconsulting.platform.credential.application;

import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.connection.domain.Provider;
import java.util.Objects;

public record SecretContext(Provider provider, PlatformConnectionId connectionId) {

    public SecretContext {
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(connectionId, "connectionId must not be null");
    }
}
