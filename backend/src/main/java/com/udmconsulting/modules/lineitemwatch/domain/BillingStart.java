package com.udmconsulting.modules.lineitemwatch.domain;

import java.time.LocalDate;

public record BillingStart(LocalDate date, DelayUnit delayUnit, Integer delayCount) {

    public BillingStart {
        boolean hasDate = date != null;
        boolean hasDelay = delayUnit != null || delayCount != null;
        if (hasDate && hasDelay) {
            throw new IllegalArgumentException("billing start date and delay are mutually exclusive");
        }
        if ((delayUnit == null) != (delayCount == null)) {
            throw new IllegalArgumentException("billing start delay unit and count must be supplied together");
        }
        if (delayCount != null && delayCount < 0) {
            throw new IllegalArgumentException("billing start delay count must not be negative");
        }
    }

    public static BillingStart unspecified() {
        return new BillingStart(null, null, null);
    }

    public static BillingStart on(LocalDate date) {
        return new BillingStart(date, null, null);
    }

    public static BillingStart after(DelayUnit unit, int count) {
        return new BillingStart(null, unit, count);
    }

    public enum DelayUnit {
        DAYS,
        MONTHS
    }
}
