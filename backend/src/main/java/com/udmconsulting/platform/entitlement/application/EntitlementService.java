package com.udmconsulting.platform.entitlement.application;

import com.udmconsulting.platform.entitlement.domain.TenantEntitlement;
import com.udmconsulting.platform.module.domain.ProductModule;
import com.udmconsulting.platform.tenant.domain.TenantId;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public final class EntitlementService {

    private final EntitlementStore entitlementStore;

    public EntitlementService(EntitlementStore entitlementStore) {
        this.entitlementStore = Objects.requireNonNull(
                entitlementStore, "entitlementStore must not be null");
    }

    public void enable(TenantId tenantId, ProductModule productModule) {
        TenantEntitlement entitlement = entitlement(tenantId, productModule);
        entitlementStore.enable(entitlement.tenantId(), entitlement.productModule());
    }

    public void disable(TenantId tenantId, ProductModule productModule) {
        TenantEntitlement entitlement = entitlement(tenantId, productModule);
        entitlementStore.disable(entitlement.tenantId(), entitlement.productModule());
    }

    public boolean isEnabled(TenantId tenantId, ProductModule productModule) {
        TenantEntitlement entitlement = entitlement(tenantId, productModule);
        return entitlementStore.isEnabled(entitlement.tenantId(), entitlement.productModule());
    }

    private static TenantEntitlement entitlement(
            TenantId tenantId, ProductModule productModule) {
        return new TenantEntitlement(tenantId, productModule);
    }
}
