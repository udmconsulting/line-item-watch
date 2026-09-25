package com.udmconsulting.platform.entitlement.application;

import com.udmconsulting.platform.module.domain.ProductModule;
import com.udmconsulting.platform.tenant.domain.TenantId;

public interface EntitlementStore {

    void enable(TenantId tenantId, ProductModule productModule);

    void disable(TenantId tenantId, ProductModule productModule);

    boolean isEnabled(TenantId tenantId, ProductModule productModule);

    /**
     * Tests row-presence entitlement while holding a database shared row lock for the caller's
     * transaction, preventing a concurrent disable from deleting the row before commit.
     */
    boolean isEnabledForCommit(TenantId tenantId, ProductModule productModule);
}
