package com.udmconsulting.platform.connection.domain;

import java.util.Objects;
import java.util.UUID;

public record PlatformConnectionId(UUID value) {

    public PlatformConnectionId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static PlatformConnectionId newId() {
        return new PlatformConnectionId(UUID.randomUUID());
    }
}
