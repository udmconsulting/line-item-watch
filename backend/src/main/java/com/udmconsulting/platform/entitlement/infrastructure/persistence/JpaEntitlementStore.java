package com.udmconsulting.platform.entitlement.infrastructure.persistence;

import com.udmconsulting.platform.entitlement.application.EntitlementStore;
import com.udmconsulting.platform.module.domain.ProductModule;
import com.udmconsulting.platform.tenant.domain.TenantId;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JpaEntitlementStore implements EntitlementStore {

    private final TenantEntitlementJpaRepository repository;

    public JpaEntitlementStore(TenantEntitlementJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void enable(TenantId tenantId, ProductModule productModule) {
        repository.enable(tenantId.value(), productModule.name());
    }

    @Override
    @Transactional
    public void disable(TenantId tenantId, ProductModule productModule) {
        repository.disable(tenantId.value(), productModule.name());
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isEnabled(TenantId tenantId, ProductModule productModule) {
        return repository.existsById(new TenantEntitlementKey(
                tenantId.value(), productModule.name()));
    }
}
