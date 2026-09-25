package com.udmconsulting.modules.lineitemwatch.domain;

import java.time.Period;
import java.time.format.DateTimeParseException;
import java.util.Objects;

public record RecurringPeriod(Period value) {

    public RecurringPeriod {
        Objects.requireNonNull(value, "value must not be null");
        if (value.isNegative()) {
            throw new IllegalArgumentException("recurring period must not be negative");
        }
    }

    public static RecurringPeriod parse(String value) {
        try {
            return new RecurringPeriod(Period.parse(value));
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("recurring period must be an ISO-8601 date period");
        }
    }

    public String canonicalValue() {
        return value.toString();
    }
}
