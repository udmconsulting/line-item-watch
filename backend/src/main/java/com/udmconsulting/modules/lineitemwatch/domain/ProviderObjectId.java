package com.udmconsulting.modules.lineitemwatch.domain;

import java.util.Objects;

public record ProviderObjectId(String value) {

    public static final int MAX_LENGTH = 255;

    public ProviderObjectId {
        Objects.requireNonNull(value, "value must not be null");
        if (value.isBlank() || !value.equals(value.trim()) || value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "provider object ID must be nonblank, trimmed, and at most 255 characters");
        }
    }
}
