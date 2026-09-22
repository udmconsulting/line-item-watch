package com.udmconsulting.platform.tenant.application;

import com.udmconsulting.platform.tenant.domain.Tenant;
import com.udmconsulting.platform.tenant.domain.TenantId;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public final class TenantService {

    private final TenantStore tenantStore;

    public TenantService(TenantStore tenantStore) {
        this.tenantStore = Objects.requireNonNull(tenantStore, "tenantStore must not be null");
    }

    public Tenant create() {
        return tenantStore.save(new Tenant(TenantId.newId()));
    }

    public Optional<Tenant> findById(TenantId tenantId) {
        return tenantStore.findById(Objects.requireNonNull(tenantId, "tenantId must not be null"));
    }
}
