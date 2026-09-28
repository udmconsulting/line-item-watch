package com.udmconsulting.modules.lineitemwatch.domain;

import java.util.Arrays;
import java.util.Optional;

public enum MonitoredLineItemProperty {
    NAME("name", "name"),
    QUANTITY("quantity", "quantity"),
    PRICE("price", "unitPrice"),
    DISCOUNT("discount", "unitDiscount"),
    DISCOUNT_PERCENTAGE("hs_discount_percentage", "discountPercentage"),
    BILLING_FREQUENCY("recurringbillingfrequency", "billingFrequency"),
    BILLING_START_DATE("hs_recurring_billing_start_date", "billingStartDate"),
    BILLING_START_DELAY_DAYS("hs_billing_start_delay_days", "billingStartDelayDays"),
    BILLING_START_DELAY_MONTHS("hs_billing_start_delay_months", "billingStartDelayMonths"),
    RECURRING_BILLING_PERIOD("hs_recurring_billing_period", "billingPeriod");

    private final String providerName;
    private final String apiName;

    MonitoredLineItemProperty(String providerName, String apiName) {
        this.providerName = providerName;
        this.apiName = apiName;
    }

    public String providerName() {
        return providerName;
    }

    public String apiName() {
        return apiName;
    }

    public static Optional<MonitoredLineItemProperty> fromProviderName(String value) {
        return Arrays.stream(values())
                .filter(property -> property.providerName.equals(value))
                .findFirst();
    }
}
