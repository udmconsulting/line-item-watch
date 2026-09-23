package com.udmconsulting.platform.connection.application;

import com.udmconsulting.platform.connection.domain.ExternalAccountId;
import com.udmconsulting.platform.connection.domain.ConnectionStatus;
import com.udmconsulting.platform.connection.domain.PlatformConnection;
import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.connection.domain.Provider;
import com.udmconsulting.platform.tenant.domain.TenantId;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public final class PlatformConnectionService {

    private final PlatformConnectionStore connectionStore;

    public PlatformConnectionService(PlatformConnectionStore connectionStore) {
        this.connectionStore = Objects.requireNonNull(connectionStore, "connectionStore must not be null");
    }

    public PlatformConnection register(
            TenantId tenantId, Provider provider, ExternalAccountId externalAccountId) {
        PlatformConnection connection = new PlatformConnection(
                PlatformConnectionId.newId(),
                Objects.requireNonNull(tenantId, "tenantId must not be null"),
                Objects.requireNonNull(provider, "provider must not be null"),
                Objects.requireNonNull(externalAccountId, "externalAccountId must not be null"),
                ConnectionStatus.DISCONNECTED);
        return connectionStore.save(connection);
    }

    public Optional<PlatformConnection> resolve(
            Provider provider, ExternalAccountId externalAccountId) {
        return connectionStore.findByProviderAndExternalAccountId(
                Objects.requireNonNull(provider, "provider must not be null"),
                Objects.requireNonNull(externalAccountId, "externalAccountId must not be null"));
    }

    public Optional<PlatformConnection> findForTenant(
            TenantId tenantId, PlatformConnectionId connectionId) {
        return connectionStore.findByTenantIdAndId(
                Objects.requireNonNull(tenantId, "tenantId must not be null"),
                Objects.requireNonNull(connectionId, "connectionId must not be null"));
    }
}
