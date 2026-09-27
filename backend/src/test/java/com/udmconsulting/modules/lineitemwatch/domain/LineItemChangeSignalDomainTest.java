package com.udmconsulting.modules.lineitemwatch.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.tenant.domain.TenantId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LineItemChangeSignalDomainTest {

    @Test
    void deduplicationKeyIsExactlySha256SizedDefensivelyCopiedAndRedacted() {
        byte[] source = new byte[32];
        source[0] = 42;
        ProviderDeduplicationKey key = new ProviderDeduplicationKey(source);
        source[0] = 0;
        byte[] exposed = key.value();
        exposed[0] = 0;

        assertThat(key.value()[0]).isEqualTo((byte) 42);
        assertThat(key.toString()).doesNotContain("42").contains("redacted");
        assertThatThrownBy(() -> new ProviderDeduplicationKey(new byte[31]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void enforcesSignalSpecificShapesWhilePreservingAnEmptyPropertyValue() {
        LineItemChangeSignal property = signal(
                LineItemChangeSignalType.PROPERTY_CHANGED,
                MonitoredLineItemProperty.NAME,
                "",
                null,
                null,
                null,
                null);

        assertThat(property.propertyValue()).isEmpty();
        assertThat(property.toString())
                .contains("customerData=<redacted>")
                .doesNotContain("event-1", "subscription-1", "line-1");
        assertThatThrownBy(() -> signal(
                LineItemChangeSignalType.CREATED,
                MonitoredLineItemProperty.NAME,
                "value",
                null,
                null,
                null,
                null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> signal(
                LineItemChangeSignalType.PROPERTY_CHANGED,
                null,
                "value",
                null,
                null,
                null,
                null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> signal(
                LineItemChangeSignalType.ASSOCIATION_CHANGED,
                null,
                null,
                null,
                AssociationAction.ADDED,
                "20",
                "HUBSPOT_DEFINED")).isInstanceOf(NullPointerException.class);
    }

    @Test
    void allowsCompleteAssociationShape() {
        LineItemChangeSignal signal = signal(
                LineItemChangeSignalType.ASSOCIATION_CHANGED,
                null,
                null,
                new ProviderObjectId("deal-1"),
                AssociationAction.REMOVED,
                "20",
                "HUBSPOT_DEFINED");

        assertThat(signal.dealId()).isEqualTo(new ProviderObjectId("deal-1"));
        assertThat(signal.associationAction()).isEqualTo(AssociationAction.REMOVED);
    }

    private static LineItemChangeSignal signal(
            LineItemChangeSignalType type,
            MonitoredLineItemProperty property,
            String propertyValue,
            ProviderObjectId dealId,
            AssociationAction associationAction,
            String associationTypeId,
            String associationCategory) {
        return new LineItemChangeSignal(
                UUID.randomUUID(),
                TenantId.newId(),
                PlatformConnectionId.newId(),
                "event-1",
                "subscription-1",
                new ProviderDeduplicationKey(new byte[32]),
                new ProviderObjectId("line-1"),
                type,
                Instant.parse("2026-09-27T11:59:00Z"),
                Instant.parse("2026-09-27T12:00:00Z"),
                property,
                propertyValue,
                dealId,
                associationAction,
                associationTypeId,
                associationCategory);
    }
}
