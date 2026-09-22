package com.udmconsulting.platform.connection.infrastructure.persistence;

import com.udmconsulting.platform.connection.domain.Provider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "platform_connection",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_platform_connection_provider_account",
                columnNames = {"provider", "external_account_id"}),
        indexes = @Index(name = "idx_platform_connection_tenant_id", columnList = "tenant_id"))
class PlatformConnectionEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, updatable = false, length = 32)
    private Provider provider;

    @Column(name = "external_account_id", nullable = false, updatable = false, length = 255)
    private String externalAccountId;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected PlatformConnectionEntity() {
    }

    PlatformConnectionEntity(
            UUID id, UUID tenantId, Provider provider, String externalAccountId) {
        this.id = id;
        this.tenantId = tenantId;
        this.provider = provider;
        this.externalAccountId = externalAccountId;
    }

    UUID id() {
        return id;
    }

    UUID tenantId() {
        return tenantId;
    }

    Provider provider() {
        return provider;
    }

    String externalAccountId() {
        return externalAccountId;
    }
}
