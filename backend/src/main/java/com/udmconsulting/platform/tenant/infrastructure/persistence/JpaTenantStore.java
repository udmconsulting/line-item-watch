package com.udmconsulting.platform.tenant.infrastructure.persistence;

import com.udmconsulting.platform.tenant.application.TenantStore;
import com.udmconsulting.platform.tenant.domain.Tenant;
import com.udmconsulting.platform.tenant.domain.TenantId;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class JpaTenantStore implements TenantStore {

    private final TenantJpaRepository repository;

    public JpaTenantStore(TenantJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Tenant save(Tenant tenant) {
        TenantEntity saved = repository.saveAndFlush(new TenantEntity(tenant.id().value()));
        return toDomain(saved);
    }

    @Override
    public Optional<Tenant> findById(TenantId tenantId) {
        return repository.findById(tenantId.value()).map(JpaTenantStore::toDomain);
    }

    private static Tenant toDomain(TenantEntity entity) {
        return new Tenant(new TenantId(entity.id()));
    }
}
