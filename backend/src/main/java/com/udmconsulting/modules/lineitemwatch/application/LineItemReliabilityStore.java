package com.udmconsulting.modules.lineitemwatch.application;

import com.udmconsulting.modules.lineitemwatch.application.LineItemReliability.ReconciliationOutcome;
import com.udmconsulting.platform.connection.domain.ConnectionStatus;
import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.tenant.domain.TenantId;
import java.time.Instant;

public interface LineItemReliabilityStore {

    void connectionChanged(
            TenantId tenantId,
            PlatformConnectionId connectionId,
            ConnectionStatus previous,
            ConnectionStatus current,
            Instant occurredAt);

    void entitlementChanged(TenantId tenantId, boolean enabled, Instant occurredAt);

    void signalObserved(
            TenantId tenantId, PlatformConnectionId connectionId, Instant observedAt);

    void signalProcessed(
            TenantId tenantId, PlatformConnectionId connectionId, Instant processedAt);

    void markPossibleGap(
            TenantId tenantId, PlatformConnectionId connectionId, Instant gapSince);

    void reconciled(
            TenantId tenantId,
            PlatformConnectionId connectionId,
            Instant reconciledAt,
            ReconciliationOutcome outcome);

    LineItemReliability read(TenantId tenantId, PlatformConnectionId connectionId);
}
