package com.udmconsulting.modules.lineitemwatch.infrastructure.persistence;

import com.udmconsulting.modules.lineitemwatch.application.LineItemReliabilityStore;
import com.udmconsulting.platform.activity.application.LifecycleTransitionObserver;
import com.udmconsulting.platform.connection.domain.ConnectionStatus;
import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.module.domain.ProductModule;
import com.udmconsulting.platform.tenant.domain.TenantId;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public final class LineItemWatchLifecycleObserver implements LifecycleTransitionObserver {

    private final LineItemReliabilityStore store;

    public LineItemWatchLifecycleObserver(LineItemReliabilityStore store) {
        this.store = store;
    }

    @Override
    public void connectionChanged(
            TenantId tenantId,
            PlatformConnectionId connectionId,
            ConnectionStatus previous,
            ConnectionStatus current,
            Instant occurredAt) {
        store.connectionChanged(tenantId, connectionId, previous, current, occurredAt);
    }

    @Override
    public void entitlementChanged(
            TenantId tenantId,
            ProductModule productModule,
            boolean enabled,
            Instant occurredAt) {
        if (productModule == ProductModule.LINE_ITEM_WATCH) {
            store.entitlementChanged(tenantId, enabled, occurredAt);
        }
    }
}
