package com.udmconsulting.platform.connection.domain;

import com.udmconsulting.platform.tenant.domain.TenantId;
import java.util.Objects;

public record PlatformConnection(
        PlatformConnectionId id,
        TenantId tenantId,
        Provider provider,
        ExternalAccountId externalAccountId,
        ConnectionStatus status) {

    public PlatformConnection {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(externalAccountId, "externalAccountId must not be null");
        Objects.requireNonNull(status, "status must not be null");
    }
}
