package com.udmconsulting.modules.lineitemwatch.application;

import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.tenant.domain.TenantId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ClaimedLineItemSignal(
        UUID signalId,
        TenantId tenantId,
        PlatformConnectionId connectionId,
        UUID claimToken,
        int attempt,
        Instant receivedAt,
        boolean reclaimed) {

    public ClaimedLineItemSignal {
        Objects.requireNonNull(signalId);
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(connectionId);
        Objects.requireNonNull(claimToken);
        Objects.requireNonNull(receivedAt);
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be positive");
        }
    }
}
