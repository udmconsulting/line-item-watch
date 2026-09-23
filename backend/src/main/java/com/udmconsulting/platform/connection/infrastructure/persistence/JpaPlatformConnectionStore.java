package com.udmconsulting.platform.connection.infrastructure.persistence;

import com.udmconsulting.platform.connection.application.PlatformConnectionStore;
import com.udmconsulting.platform.connection.domain.ExternalAccountId;
import com.udmconsulting.platform.connection.domain.PlatformConnection;
import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.connection.domain.Provider;
import com.udmconsulting.platform.tenant.domain.TenantId;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class JpaPlatformConnectionStore implements PlatformConnectionStore {

    private final PlatformConnectionJpaRepository repository;

    public JpaPlatformConnectionStore(PlatformConnectionJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public PlatformConnection save(PlatformConnection connection) {
        PlatformConnectionEntity saved = repository.saveAndFlush(new PlatformConnectionEntity(
                connection.id().value(),
                connection.tenantId().value(),
                connection.provider(),
                connection.externalAccountId().value(),
                connection.status()));
        return toDomain(saved);
    }

    @Override
    public Optional<PlatformConnection> findByProviderAndExternalAccountId(
            Provider provider, ExternalAccountId externalAccountId) {
        return repository.findByProviderAndExternalAccountId(provider, externalAccountId.value())
                .map(JpaPlatformConnectionStore::toDomain);
    }

    @Override
    public Optional<PlatformConnection> findByTenantIdAndId(
            TenantId tenantId, PlatformConnectionId connectionId) {
        return repository.findByTenantIdAndId(tenantId.value(), connectionId.value())
                .map(JpaPlatformConnectionStore::toDomain);
    }

    private static PlatformConnection toDomain(PlatformConnectionEntity entity) {
        return new PlatformConnection(
                new PlatformConnectionId(entity.id()),
                new TenantId(entity.tenantId()),
                entity.provider(),
                new ExternalAccountId(entity.externalAccountId()),
                entity.status());
    }
}
