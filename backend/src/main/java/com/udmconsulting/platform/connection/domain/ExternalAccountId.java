package com.udmconsulting.platform.connection.domain;

import java.util.Objects;

public record ExternalAccountId(String value) {

    public ExternalAccountId {
        Objects.requireNonNull(value, "value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
        if (!value.equals(value.strip())) {
            throw new IllegalArgumentException("value must not have leading or trailing whitespace");
        }
    }
}
