package com.udmconsulting.platform.connection.infrastructure.persistence;

import com.udmconsulting.platform.connection.domain.Provider;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface PlatformConnectionJpaRepository
        extends JpaRepository<PlatformConnectionEntity, UUID> {

    Optional<PlatformConnectionEntity> findByProviderAndExternalAccountId(
            Provider provider, String externalAccountId);

    Optional<PlatformConnectionEntity> findByTenantIdAndId(UUID tenantId, UUID id);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("""
            SELECT connection
            FROM PlatformConnectionEntity connection
            WHERE connection.tenantId = :tenantId AND connection.id = :connectionId
            """)
    Optional<PlatformConnectionEntity> findByTenantIdAndIdForCommit(
            @Param("tenantId") UUID tenantId,
            @Param("connectionId") UUID connectionId);
}
