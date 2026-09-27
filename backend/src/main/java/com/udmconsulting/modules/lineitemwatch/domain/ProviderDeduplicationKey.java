package com.udmconsulting.modules.lineitemwatch.domain;

import java.util.Arrays;
import java.util.Objects;

public final class ProviderDeduplicationKey {

    public static final int LENGTH = 32;

    private final byte[] value;

    public ProviderDeduplicationKey(byte[] value) {
        Objects.requireNonNull(value, "value must not be null");
        if (value.length != LENGTH) {
            throw new IllegalArgumentException("provider deduplication key must contain exactly 32 bytes");
        }
        this.value = value.clone();
    }

    public byte[] value() {
        return value.clone();
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || (other instanceof ProviderDeduplicationKey that
                && Arrays.equals(value, that.value));
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(value);
    }

    @Override
    public String toString() {
        return "ProviderDeduplicationKey[<redacted>]";
    }
}
