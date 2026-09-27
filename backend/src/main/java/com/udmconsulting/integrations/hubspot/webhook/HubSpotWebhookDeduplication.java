package com.udmconsulting.integrations.hubspot.webhook;

import com.udmconsulting.modules.lineitemwatch.domain.AssociationAction;
import com.udmconsulting.modules.lineitemwatch.domain.LineItemChangeSignalType;
import com.udmconsulting.modules.lineitemwatch.domain.MonitoredLineItemProperty;
import com.udmconsulting.modules.lineitemwatch.domain.ProviderDeduplicationKey;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;

final class HubSpotWebhookDeduplication {

    private static final String FORMAT = "line-item-watch-change-signal-v1";

    private HubSpotWebhookDeduplication() {
    }

    static ProviderDeduplicationKey key(
            String providerEventId,
            String subscriptionId,
            Instant occurredAt,
            LineItemChangeSignalType type,
            String lineItemId,
            MonitoredLineItemProperty property,
            String propertyValue,
            String dealId,
            AssociationAction action,
            String associationTypeId,
            String associationCategory) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream encoded = new DataOutputStream(bytes)) {
                write(encoded, FORMAT);
                write(encoded, providerEventId);
                write(encoded, subscriptionId);
                write(encoded, Long.toString(occurredAt.toEpochMilli()));
                write(encoded, type.name());
                write(encoded, lineItemId);
                switch (type) {
                    case PROPERTY_CHANGED -> {
                        write(encoded, property.providerName());
                        write(encoded, propertyValue);
                    }
                    case ASSOCIATION_CHANGED -> {
                        write(encoded, dealId);
                        write(encoded, action.name());
                        write(encoded, associationTypeId);
                        write(encoded, associationCategory);
                    }
                    case CREATED, DELETED -> {
                        // Common identity is sufficient for these signal shapes.
                    }
                }
            }
            return new ProviderDeduplicationKey(
                    MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException exception) {
            throw new IllegalStateException("in-memory deduplication encoding failed", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void write(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }
}
