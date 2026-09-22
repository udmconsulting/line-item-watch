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
}
