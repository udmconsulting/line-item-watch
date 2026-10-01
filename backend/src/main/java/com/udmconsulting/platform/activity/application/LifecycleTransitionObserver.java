package com.udmconsulting.platform.activity.application;

import com.udmconsulting.platform.connection.domain.ConnectionStatus;
import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.module.domain.ProductModule;
import com.udmconsulting.platform.tenant.domain.TenantId;
import java.time.Instant;

/**
 * Platform-owned extension point for atomically qualifying module availability when a
 * committed connection or entitlement transition changes what can be observed.
 */
public interface LifecycleTransitionObserver {

    default void connectionChanged(
            TenantId tenantId,
            PlatformConnectionId connectionId,
            ConnectionStatus previous,
            ConnectionStatus current,
            Instant occurredAt) {
    }

    default void entitlementChanged(
            TenantId tenantId,
            ProductModule productModule,
            boolean enabled,
            Instant occurredAt) {
    }
}
