package com.udmconsulting.platform.connection.infrastructure.persistence;

import com.udmconsulting.platform.connection.domain.Provider;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface PlatformConnectionJpaRepository
        extends JpaRepository<PlatformConnectionEntity, UUID> {

    Optional<PlatformConnectionEntity> findByProviderAndExternalAccountId(
            Provider provider, String externalAccountId);

    Optional<PlatformConnectionEntity> findByTenantIdAndId(UUID tenantId, UUID id);
}
