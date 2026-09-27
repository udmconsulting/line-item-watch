package com.udmconsulting.integrations.hubspot.webhook;

import com.udmconsulting.modules.lineitemwatch.domain.AssociationAction;
import com.udmconsulting.modules.lineitemwatch.domain.LineItemChangeSignal;
import com.udmconsulting.modules.lineitemwatch.domain.LineItemChangeSignalType;
import com.udmconsulting.modules.lineitemwatch.domain.MonitoredLineItemProperty;
import com.udmconsulting.modules.lineitemwatch.domain.ProviderDeduplicationKey;
import com.udmconsulting.modules.lineitemwatch.domain.ProviderObjectId;
import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.tenant.domain.TenantId;
import java.time.Instant;
import java.util.UUID;

record NormalizedHubSpotLineItemEvent(
        String portalId,
        String providerEventId,
        String providerSubscriptionId,
        String externalLineItemId,
        LineItemChangeSignalType type,
        Instant occurredAt,
        MonitoredLineItemProperty property,
        String propertyValue,
        String externalDealId,
        AssociationAction associationAction,
        String associationTypeId,
        String associationCategory,
        ProviderDeduplicationKey deduplicationKey) {

    LineItemChangeSignal routeTo(
            TenantId tenantId,
            PlatformConnectionId connectionId,
            Instant receivedAt) {
        return new LineItemChangeSignal(
                UUID.randomUUID(),
                tenantId,
                connectionId,
                providerEventId,
                providerSubscriptionId,
                deduplicationKey,
                new ProviderObjectId(externalLineItemId),
                type,
                occurredAt,
                receivedAt,
                property,
                propertyValue,
                externalDealId == null ? null : new ProviderObjectId(externalDealId),
                associationAction,
                associationTypeId,
                associationCategory);
    }

    @Override
    public String toString() {
        return "NormalizedHubSpotLineItemEvent[type=" + type
                + ", occurredAt=" + occurredAt
                + ", providerData=<redacted>]";
    }
}
