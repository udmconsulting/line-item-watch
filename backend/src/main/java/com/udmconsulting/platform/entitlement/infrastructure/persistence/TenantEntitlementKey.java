package com.udmconsulting.platform.entitlement.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
class TenantEntitlementKey implements Serializable {

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "product_module", nullable = false, updatable = false, length = 64)
    private String productModule;

    protected TenantEntitlementKey() {
    }

    TenantEntitlementKey(UUID tenantId, String productModule) {
        this.tenantId = tenantId;
        this.productModule = productModule;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TenantEntitlementKey that)) {
            return false;
        }
        return Objects.equals(tenantId, that.tenantId)
                && Objects.equals(productModule, that.productModule);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tenantId, productModule);
    }
}
