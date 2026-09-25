package com.udmconsulting.modules.lineitemwatch.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public record LineItemObservation(
        ProviderObjectId lineItemId,
        String name,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal unitDiscount,
        BigDecimal discountPercentage,
        String billingFrequency,
        BillingStart billingStart,
        RecurringPeriod recurringPeriod,
        Instant providerCreatedAt,
        Instant providerUpdatedAt,
        Instant observedAt,
        Set<ProviderObjectId> associatedDealIds) {

    public LineItemObservation {
        Objects.requireNonNull(lineItemId, "lineItemId must not be null");
        name = normalizeOptionalText(name, "name");
        billingFrequency = normalizeOptionalText(billingFrequency, "billingFrequency");
        Objects.requireNonNull(billingStart, "billingStart must not be null");
        Objects.requireNonNull(providerCreatedAt, "providerCreatedAt must not be null");
        Objects.requireNonNull(providerUpdatedAt, "providerUpdatedAt must not be null");
        Objects.requireNonNull(observedAt, "observedAt must not be null");
        Objects.requireNonNull(associatedDealIds, "associatedDealIds must not be null");
        if (associatedDealIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("associatedDealIds must not contain null");
        }
        associatedDealIds = Set.copyOf(new LinkedHashSet<>(associatedDealIds));
    }

    private static String normalizeOptionalText(String value, String name) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > 65_535) {
            throw new IllegalArgumentException(name + " is too long");
        }
        return normalized;
    }
}
