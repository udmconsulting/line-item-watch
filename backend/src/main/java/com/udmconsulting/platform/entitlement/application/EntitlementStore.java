package com.udmconsulting.platform.entitlement.application;

import com.udmconsulting.platform.module.domain.ProductModule;
import com.udmconsulting.platform.tenant.domain.TenantId;

public interface EntitlementStore {

    void enable(TenantId tenantId, ProductModule productModule);

    void disable(TenantId tenantId, ProductModule productModule);

    boolean isEnabled(TenantId tenantId, ProductModule productModule);
}
