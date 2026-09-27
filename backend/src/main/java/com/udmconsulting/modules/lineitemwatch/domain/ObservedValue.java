package com.udmconsulting.modules.lineitemwatch.domain;

import java.util.Objects;

public record ObservedValue(State state, String value) {

    public ObservedValue {
        Objects.requireNonNull(state, "state must not be null");
        if ((state == State.VALUE) != (value != null)) {
            throw new IllegalArgumentException("only VALUE may carry a value");
        }
    }

    public static ObservedValue unknown() {
        return new ObservedValue(State.UNKNOWN, null);
    }

    public static ObservedValue absent() {
        return new ObservedValue(State.ABSENT, null);
    }

    public static ObservedValue present() {
        return new ObservedValue(State.PRESENT, null);
    }

    public static ObservedValue value(String value) {
        return new ObservedValue(State.VALUE, Objects.requireNonNull(value));
    }

    public enum State {
        UNKNOWN,
        ABSENT,
        PRESENT,
        VALUE
    }
}
