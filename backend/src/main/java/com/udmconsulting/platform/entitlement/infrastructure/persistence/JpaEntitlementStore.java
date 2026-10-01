package com.udmconsulting.platform.entitlement.infrastructure.persistence;

import com.udmconsulting.platform.activity.application.ActivityContext;
import com.udmconsulting.platform.activity.application.ApplicationActivityAudit;
import com.udmconsulting.platform.activity.application.LifecycleTransitionObserver;
import com.udmconsulting.platform.activity.domain.ActivityAction;
import com.udmconsulting.platform.activity.domain.ActivityResourceType;
import com.udmconsulting.platform.entitlement.application.EntitlementStore;
import com.udmconsulting.platform.module.domain.ProductModule;
import com.udmconsulting.platform.tenant.domain.TenantId;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JpaEntitlementStore implements EntitlementStore {

    private final TenantEntitlementJpaRepository repository;
    private final ApplicationActivityAudit activityAudit;
    private final List<LifecycleTransitionObserver> lifecycleObservers;
    private final Clock clock;

    public JpaEntitlementStore(
            TenantEntitlementJpaRepository repository,
            ApplicationActivityAudit activityAudit,
            List<LifecycleTransitionObserver> lifecycleObservers,
            Clock clock) {
        this.repository = repository;
        this.activityAudit = activityAudit;
        this.lifecycleObservers = List.copyOf(lifecycleObservers);
        this.clock = clock;
    }

    @Override
    @Transactional
    public boolean enable(
            TenantId tenantId,
            ProductModule productModule,
            ActivityContext activityContext) {
        if (repository.enable(tenantId.value(), productModule.name()) == 0) {
            return false;
        }
        activityAudit.record(
                tenantId,
                null,
                activityContext,
                ActivityAction.ENTITLEMENT_ACTIVATED,
                ActivityResourceType.ENTITLEMENT,
                productModule.name(),
                "DISABLED",
                "ENABLED");
        lifecycleObservers.forEach(observer -> observer.entitlementChanged(
                tenantId, productModule, true, clock.instant()));
        return true;
    }

    @Override
    @Transactional
    public boolean disable(
            TenantId tenantId,
            ProductModule productModule,
            ActivityContext activityContext) {
        if (repository.disable(tenantId.value(), productModule.name()) == 0) {
            return false;
        }
        activityAudit.record(
                tenantId,
                null,
                activityContext,
                ActivityAction.ENTITLEMENT_DEACTIVATED,
                ActivityResourceType.ENTITLEMENT,
                productModule.name(),
                "ENABLED",
                "DISABLED");
        lifecycleObservers.forEach(observer -> observer.entitlementChanged(
                tenantId, productModule, false, clock.instant()));
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isEnabled(TenantId tenantId, ProductModule productModule) {
        return repository.existsById(new TenantEntitlementKey(
                tenantId.value(), productModule.name()));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isEnabledForCommit(TenantId tenantId, ProductModule productModule) {
        return repository.findByIdForCommit(new TenantEntitlementKey(
                tenantId.value(), productModule.name())).isPresent();
    }
}
