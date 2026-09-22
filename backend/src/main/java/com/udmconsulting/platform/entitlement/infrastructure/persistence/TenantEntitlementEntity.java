package com.udmconsulting.platform.entitlement.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "tenant_entitlement")
class TenantEntitlementEntity {

    @EmbeddedId
    private TenantEntitlementKey id;

    @Column(name = "enabled_at", nullable = false, insertable = false, updatable = false)
    private Instant enabledAt;

    protected TenantEntitlementEntity() {
    }
}
