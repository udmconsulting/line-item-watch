package com.udmconsulting.modules.lineitemwatch.domain;

import java.util.Arrays;
import java.util.Optional;

public enum MonitoredLineItemProperty {
    NAME("name"),
    QUANTITY("quantity"),
    PRICE("price"),
    DISCOUNT("discount"),
    DISCOUNT_PERCENTAGE("hs_discount_percentage"),
    BILLING_FREQUENCY("recurringbillingfrequency"),
    BILLING_START_DATE("hs_recurring_billing_start_date"),
    BILLING_START_DELAY_DAYS("hs_billing_start_delay_days"),
    BILLING_START_DELAY_MONTHS("hs_billing_start_delay_months"),
    RECURRING_BILLING_PERIOD("hs_recurring_billing_period");

    private final String providerName;

    MonitoredLineItemProperty(String providerName) {
        this.providerName = providerName;
    }

    public String providerName() {
        return providerName;
    }

    public static Optional<MonitoredLineItemProperty> fromProviderName(String value) {
        return Arrays.stream(values())
                .filter(property -> property.providerName.equals(value))
                .findFirst();
    }
}
