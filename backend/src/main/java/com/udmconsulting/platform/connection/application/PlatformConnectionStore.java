package com.udmconsulting.platform.connection.application;

import com.udmconsulting.platform.connection.domain.ExternalAccountId;
import com.udmconsulting.platform.connection.domain.PlatformConnection;
import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.connection.domain.Provider;
import com.udmconsulting.platform.tenant.domain.TenantId;
import java.util.Optional;

public interface PlatformConnectionStore {

    PlatformConnection save(PlatformConnection connection);

    Optional<PlatformConnection> findByProviderAndExternalAccountId(
            Provider provider, ExternalAccountId externalAccountId);

    Optional<PlatformConnection> findByTenantIdAndId(
            TenantId tenantId, PlatformConnectionId connectionId);

    /**
     * Loads the Tenant-owned connection while holding a database shared row lock for the
     * caller's transaction. Callers use this to prevent lifecycle updates until commit.
     */
    Optional<PlatformConnection> findByTenantIdAndIdForCommit(
            TenantId tenantId, PlatformConnectionId connectionId);
}
