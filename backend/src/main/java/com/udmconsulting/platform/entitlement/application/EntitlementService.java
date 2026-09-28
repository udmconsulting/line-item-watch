package com.udmconsulting.platform.entitlement.application;

import com.udmconsulting.platform.activity.application.ActivityContext;
import com.udmconsulting.platform.activity.domain.ActivityActor;
import com.udmconsulting.platform.activity.domain.ActivityActorSource;
import com.udmconsulting.platform.entitlement.domain.TenantEntitlement;
import com.udmconsulting.platform.module.domain.ProductModule;
import com.udmconsulting.platform.tenant.domain.TenantId;
import com.udmconsulting.platform.supportability.DiagnosticContext;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public final class EntitlementService {

    private final EntitlementStore entitlementStore;

    public EntitlementService(EntitlementStore entitlementStore) {
        this.entitlementStore = Objects.requireNonNull(
                entitlementStore, "entitlementStore must not be null");
    }

    public void enable(TenantId tenantId, ProductModule productModule) {
        enable(tenantId, productModule, unattributedContext());
    }

    public boolean enable(
            TenantId tenantId,
            ProductModule productModule,
            ActivityContext activityContext) {
        TenantEntitlement entitlement = entitlement(tenantId, productModule);
        return entitlementStore.enable(
                entitlement.tenantId(), entitlement.productModule(), activityContext);
    }

    public void disable(TenantId tenantId, ProductModule productModule) {
        disable(tenantId, productModule, unattributedContext());
    }

    public boolean disable(
            TenantId tenantId,
            ProductModule productModule,
            ActivityContext activityContext) {
        TenantEntitlement entitlement = entitlement(tenantId, productModule);
        return entitlementStore.disable(
                entitlement.tenantId(), entitlement.productModule(), activityContext);
    }

    public boolean isEnabled(TenantId tenantId, ProductModule productModule) {
        TenantEntitlement entitlement = entitlement(tenantId, productModule);
        return entitlementStore.isEnabled(entitlement.tenantId(), entitlement.productModule());
    }

    public boolean lockEnabledForCommit(TenantId tenantId, ProductModule productModule) {
        TenantEntitlement entitlement = entitlement(tenantId, productModule);
        return entitlementStore.isEnabledForCommit(
                entitlement.tenantId(), entitlement.productModule());
    }

    private static TenantEntitlement entitlement(
            TenantId tenantId, ProductModule productModule) {
        return new TenantEntitlement(tenantId, productModule);
    }

    private static ActivityContext unattributedContext() {
        return new ActivityContext(
                ActivityActor.unattributed(ActivityActorSource.APPLICATION),
                DiagnosticContext.currentDiagnosticIdOrNew());
    }
}
