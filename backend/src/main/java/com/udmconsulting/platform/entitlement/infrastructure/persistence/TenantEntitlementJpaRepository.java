package com.udmconsulting.platform.entitlement.infrastructure.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface TenantEntitlementJpaRepository
        extends JpaRepository<TenantEntitlementEntity, TenantEntitlementKey> {

    @Modifying
    @Query(value = """
            INSERT INTO tenant_entitlement (tenant_id, product_module)
            VALUES (:tenantId, :productModule)
            ON CONFLICT (tenant_id, product_module) DO NOTHING
            """, nativeQuery = true)
    int enable(@Param("tenantId") UUID tenantId, @Param("productModule") String productModule);

    @Modifying
    @Query(value = """
            DELETE FROM tenant_entitlement
            WHERE tenant_id = :tenantId AND product_module = :productModule
            """, nativeQuery = true)
    int disable(@Param("tenantId") UUID tenantId, @Param("productModule") String productModule);
}
