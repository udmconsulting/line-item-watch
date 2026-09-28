package com.udmconsulting.platform.activity.application;

import com.udmconsulting.platform.activity.domain.ActivityAction;
import com.udmconsulting.platform.activity.domain.ActivityResourceType;
import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.tenant.domain.TenantId;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public final class ApplicationActivityAudit {

    private final ApplicationActivityAuditStore store;
    private final Clock clock;

    public ApplicationActivityAudit(ApplicationActivityAuditStore store, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.clock = Objects.requireNonNull(clock);
    }

    public void record(
            TenantId tenantId,
            PlatformConnectionId connectionId,
            ActivityContext context,
            ActivityAction action,
            ActivityResourceType resourceType,
            String resourceReference,
            String previousState,
            String resultingState) {
        store.append(new ApplicationActivity(
                UUID.randomUUID(),
                tenantId,
                connectionId,
                clock.instant(),
                context.actor(),
                action,
                resourceType,
                resourceReference,
                previousState,
                resultingState,
                context.diagnosticId()));
    }
}
