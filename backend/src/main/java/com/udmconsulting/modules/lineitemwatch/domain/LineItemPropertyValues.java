package com.udmconsulting.modules.lineitemwatch.domain;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.EnumMap;
import java.util.Map;

public final class LineItemPropertyValues {

    private LineItemPropertyValues() {
    }

    public static Map<MonitoredLineItemProperty, ObservedValue> fromObservation(
            LineItemObservation observation) {
        EnumMap<MonitoredLineItemProperty, ObservedValue> values =
                new EnumMap<>(MonitoredLineItemProperty.class);
        values.put(MonitoredLineItemProperty.NAME, text(observation.name()));
        values.put(MonitoredLineItemProperty.QUANTITY, decimal(observation.quantity()));
        values.put(MonitoredLineItemProperty.PRICE, decimal(observation.unitPrice()));
        values.put(MonitoredLineItemProperty.DISCOUNT, decimal(observation.unitDiscount()));
        values.put(
                MonitoredLineItemProperty.DISCOUNT_PERCENTAGE,
                decimal(observation.discountPercentage()));
        values.put(
                MonitoredLineItemProperty.BILLING_FREQUENCY,
                text(observation.billingFrequency()));
        BillingStart start = observation.billingStart();
        values.put(
                MonitoredLineItemProperty.BILLING_START_DATE,
                start.date() == null ? ObservedValue.absent() : ObservedValue.value(start.date().toString()));
        values.put(
                MonitoredLineItemProperty.BILLING_START_DELAY_DAYS,
                start.delayUnit() == BillingStart.DelayUnit.DAYS
                        ? ObservedValue.value(Integer.toString(start.delayCount()))
                        : ObservedValue.absent());
        values.put(
                MonitoredLineItemProperty.BILLING_START_DELAY_MONTHS,
                start.delayUnit() == BillingStart.DelayUnit.MONTHS
                        ? ObservedValue.value(Integer.toString(start.delayCount()))
                        : ObservedValue.absent());
        values.put(
                MonitoredLineItemProperty.RECURRING_BILLING_PERIOD,
                observation.recurringPeriod() == null
                        ? ObservedValue.absent()
                        : ObservedValue.value(observation.recurringPeriod().canonicalValue()));
        return Map.copyOf(values);
    }

    public static ObservedValue normalize(
            MonitoredLineItemProperty property, String providerValue) {
        String normalized = providerValue.trim();
        if (normalized.isEmpty()) {
            return ObservedValue.absent();
        }
        try {
            return switch (property) {
                case NAME, BILLING_FREQUENCY -> ObservedValue.value(normalized);
                case QUANTITY, PRICE, DISCOUNT, DISCOUNT_PERCENTAGE ->
                        ObservedValue.value(canonicalDecimal(new BigDecimal(normalized)));
                case BILLING_START_DATE -> ObservedValue.value(canonicalDate(normalized));
                case BILLING_START_DELAY_DAYS, BILLING_START_DELAY_MONTHS -> {
                    int count = Integer.parseInt(normalized);
                    if (count < 0) {
                        throw new IllegalArgumentException("billing delay must not be negative");
                    }
                    yield ObservedValue.value(Integer.toString(count));
                }
                case RECURRING_BILLING_PERIOD ->
                        ObservedValue.value(RecurringPeriod.parse(normalized).canonicalValue());
            };
        } catch (ArithmeticException | DateTimeException exception) {
            throw new IllegalArgumentException("invalid monitored property value", exception);
        }
    }

    public static void apply(
            Map<MonitoredLineItemProperty, ObservedValue> state,
            MonitoredLineItemProperty property,
            ObservedValue value) {
        state.put(property, value);
        if (value.state() != ObservedValue.State.VALUE) {
            return;
        }
        switch (property) {
            case BILLING_START_DATE -> {
                state.put(MonitoredLineItemProperty.BILLING_START_DELAY_DAYS, ObservedValue.absent());
                state.put(MonitoredLineItemProperty.BILLING_START_DELAY_MONTHS, ObservedValue.absent());
            }
            case BILLING_START_DELAY_DAYS -> {
                state.put(MonitoredLineItemProperty.BILLING_START_DATE, ObservedValue.absent());
                state.put(MonitoredLineItemProperty.BILLING_START_DELAY_MONTHS, ObservedValue.absent());
            }
            case BILLING_START_DELAY_MONTHS -> {
                state.put(MonitoredLineItemProperty.BILLING_START_DATE, ObservedValue.absent());
                state.put(MonitoredLineItemProperty.BILLING_START_DELAY_DAYS, ObservedValue.absent());
            }
            default -> {
                // No cross-property normalization is required.
            }
        }
    }

    private static ObservedValue text(String value) {
        return value == null ? ObservedValue.absent() : ObservedValue.value(value);
    }

    private static ObservedValue decimal(BigDecimal value) {
        return value == null ? ObservedValue.absent() : ObservedValue.value(canonicalDecimal(value));
    }

    private static String canonicalDecimal(BigDecimal value) {
        BigDecimal canonical = value.stripTrailingZeros();
        return canonical.signum() == 0 ? "0" : canonical.toPlainString();
    }

    private static String canonicalDate(String value) {
        try {
            return LocalDate.parse(value).toString();
        } catch (DateTimeParseException exception) {
            long epochMilliseconds = Long.parseLong(value);
            return Instant.ofEpochMilli(epochMilliseconds)
                    .atZone(ZoneOffset.UTC)
                    .toLocalDate()
                    .toString();
        }
    }
}
