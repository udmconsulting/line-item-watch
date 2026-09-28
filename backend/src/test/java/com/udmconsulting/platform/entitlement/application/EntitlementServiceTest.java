package com.udmconsulting.platform.entitlement.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.udmconsulting.platform.module.domain.ProductModule;
import com.udmconsulting.platform.activity.application.ActivityContext;
import com.udmconsulting.platform.tenant.domain.TenantId;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EntitlementServiceTest {

    @Test
    void enableAndDisableAreIdempotentAndTenantScoped() {
        InMemoryEntitlementStore store = new InMemoryEntitlementStore();
        EntitlementService service = new EntitlementService(store);
        TenantId enabledTenant = TenantId.newId();
        TenantId otherTenant = TenantId.newId();

        service.enable(enabledTenant, ProductModule.LINE_ITEM_WATCH);
        service.enable(enabledTenant, ProductModule.LINE_ITEM_WATCH);

        assertThat(service.isEnabled(enabledTenant, ProductModule.LINE_ITEM_WATCH)).isTrue();
        assertThat(service.isEnabled(otherTenant, ProductModule.LINE_ITEM_WATCH)).isFalse();
        assertThat(store.entitlements).hasSize(1);

        service.disable(enabledTenant, ProductModule.LINE_ITEM_WATCH);
        service.disable(enabledTenant, ProductModule.LINE_ITEM_WATCH);

        assertThat(service.isEnabled(enabledTenant, ProductModule.LINE_ITEM_WATCH)).isFalse();
    }

    private static final class InMemoryEntitlementStore implements EntitlementStore {

        private final Set<Key> entitlements = new HashSet<>();

        @Override
        public boolean enable(
                TenantId tenantId,
                ProductModule productModule,
                ActivityContext activityContext) {
            return entitlements.add(new Key(tenantId, productModule));
        }

        @Override
        public boolean disable(
                TenantId tenantId,
                ProductModule productModule,
                ActivityContext activityContext) {
            return entitlements.remove(new Key(tenantId, productModule));
        }

        @Override
        public boolean isEnabled(TenantId tenantId, ProductModule productModule) {
            return entitlements.contains(new Key(tenantId, productModule));
        }

        @Override
        public boolean isEnabledForCommit(TenantId tenantId, ProductModule productModule) {
            return isEnabled(tenantId, productModule);
        }

        private record Key(TenantId tenantId, ProductModule productModule) {
        }
    }
}
