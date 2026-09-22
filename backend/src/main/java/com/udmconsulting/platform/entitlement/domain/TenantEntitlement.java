package com.udmconsulting.platform.entitlement.domain;

import com.udmconsulting.platform.module.domain.ProductModule;
import com.udmconsulting.platform.tenant.domain.TenantId;
import java.util.Objects;

public record TenantEntitlement(TenantId tenantId, ProductModule productModule) {

    public TenantEntitlement {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(productModule, "productModule must not be null");
    }
}
