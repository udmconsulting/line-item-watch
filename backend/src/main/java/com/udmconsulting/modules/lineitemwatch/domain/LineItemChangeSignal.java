package com.udmconsulting.modules.lineitemwatch.domain;

import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.tenant.domain.TenantId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record LineItemChangeSignal(
        UUID id,
        TenantId tenantId,
        PlatformConnectionId connectionId,
        String providerEventId,
        String providerSubscriptionId,
        ProviderDeduplicationKey providerDeduplicationKey,
        ProviderObjectId lineItemId,
        LineItemChangeSignalType type,
        Instant occurredAt,
        Instant receivedAt,
        MonitoredLineItemProperty property,
        String propertyValue,
        ProviderObjectId dealId,
        AssociationAction associationAction,
        String associationTypeId,
        String associationCategory) {

    public static final int MAX_PROVIDER_ID_LENGTH = 255;
    public static final int MAX_PROPERTY_VALUE_LENGTH = 65_535;
    public static final int MAX_ASSOCIATION_METADATA_LENGTH = 64;

    public LineItemChangeSignal {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(connectionId, "connectionId must not be null");
        providerEventId = requireBoundedText(
                providerEventId, MAX_PROVIDER_ID_LENGTH, "providerEventId");
        providerSubscriptionId = requireBoundedText(
                providerSubscriptionId, MAX_PROVIDER_ID_LENGTH, "providerSubscriptionId");
        Objects.requireNonNull(providerDeduplicationKey, "providerDeduplicationKey must not be null");
        Objects.requireNonNull(lineItemId, "lineItemId must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(receivedAt, "receivedAt must not be null");

        switch (type) {
            case CREATED, DELETED -> requireNoDetails(
                    property, propertyValue, dealId, associationAction,
                    associationTypeId, associationCategory);
            case PROPERTY_CHANGED -> {
                Objects.requireNonNull(property, "property must not be null for a property change");
                Objects.requireNonNull(propertyValue, "propertyValue must not be null for a property change");
                if (propertyValue.codePointCount(0, propertyValue.length()) > MAX_PROPERTY_VALUE_LENGTH) {
                    throw new IllegalArgumentException("propertyValue is too long");
                }
                requireNoAssociationDetails(
                        dealId, associationAction, associationTypeId, associationCategory);
            }
            case ASSOCIATION_CHANGED -> {
                if (property != null || propertyValue != null) {
                    throw new IllegalArgumentException("association changes must not contain property details");
                }
                Objects.requireNonNull(dealId, "dealId must not be null for an association change");
                Objects.requireNonNull(
                        associationAction, "associationAction must not be null for an association change");
                associationTypeId = requireBoundedText(
                        associationTypeId, MAX_ASSOCIATION_METADATA_LENGTH, "associationTypeId");
                associationCategory = requireBoundedText(
                        associationCategory, MAX_ASSOCIATION_METADATA_LENGTH, "associationCategory");
            }
        }
    }

    private static void requireNoDetails(
            MonitoredLineItemProperty property,
            String propertyValue,
            ProviderObjectId dealId,
            AssociationAction associationAction,
            String associationTypeId,
            String associationCategory) {
        if (property != null || propertyValue != null) {
            throw new IllegalArgumentException("creation/deletion signals must not contain property details");
        }
        requireNoAssociationDetails(dealId, associationAction, associationTypeId, associationCategory);
    }

    private static void requireNoAssociationDetails(
            ProviderObjectId dealId,
            AssociationAction associationAction,
            String associationTypeId,
            String associationCategory) {
        if (dealId != null || associationAction != null
                || associationTypeId != null || associationCategory != null) {
            throw new IllegalArgumentException("signal must not contain association details");
        }
    }

    private static String requireBoundedText(String value, int maximumLength, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank() || !value.equals(value.trim()) || value.length() > maximumLength) {
            throw new IllegalArgumentException(
                    name + " must be nonblank, trimmed, and at most " + maximumLength + " characters");
        }
        return value;
    }

    @Override
    public String toString() {
        return "LineItemChangeSignal[id=" + id
                + ", tenantId=" + tenantId
                + ", connectionId=" + connectionId
                + ", type=" + type
                + ", customerData=<redacted>]";
    }
}
