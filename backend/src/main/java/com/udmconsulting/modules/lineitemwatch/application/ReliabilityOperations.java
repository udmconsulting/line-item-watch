package com.udmconsulting.modules.lineitemwatch.application;

import com.udmconsulting.modules.lineitemwatch.application.ReliabilityOperationStore.OperationType;
import com.udmconsulting.modules.lineitemwatch.application.ReliabilityOperationStore.Request;
import com.udmconsulting.modules.lineitemwatch.application.ReliabilityOperationStore.ScopeType;
import com.udmconsulting.modules.lineitemwatch.domain.ProviderObjectId;
import com.udmconsulting.platform.activity.application.ActivityContext;
import com.udmconsulting.platform.activity.application.ApplicationActivityAudit;
import com.udmconsulting.platform.activity.domain.ActivityAction;
import com.udmconsulting.platform.activity.domain.ActivityResourceType;
import com.udmconsulting.platform.connection.application.PlatformConnectionService;
import com.udmconsulting.platform.connection.domain.ConnectionStatus;
import com.udmconsulting.platform.connection.domain.PlatformConnection;
import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.connection.domain.Provider;
import com.udmconsulting.platform.entitlement.application.EntitlementService;
import com.udmconsulting.platform.module.domain.ProductModule;
import com.udmconsulting.platform.supportability.OperationalErrorCode;
import com.udmconsulting.platform.tenant.domain.TenantId;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReliabilityOperations {

    private final PlatformConnectionService connections;
    private final EntitlementService entitlements;
    private final ReliabilityOperationStore store;
    private final ApplicationActivityAudit activityAudit;
    private final Clock clock;

    public ReliabilityOperations(
            PlatformConnectionService connections,
            EntitlementService entitlements,
            ReliabilityOperationStore store,
            ApplicationActivityAudit activityAudit,
            Clock clock) {
        this.connections = Objects.requireNonNull(connections);
        this.entitlements = Objects.requireNonNull(entitlements);
        this.store = Objects.requireNonNull(store);
        this.activityAudit = Objects.requireNonNull(activityAudit);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional
    public UUID reconcileTenant(
            TenantId tenantId, PlatformConnectionId connectionId, ActivityContext actor) {
        requireReconciliationAccess(tenantId, connectionId);
        return request(new Request(tenantId, connectionId, OperationType.RECONCILE,
                ScopeType.TENANT, null, null), actor,
                ActivityAction.LINE_ITEM_RECONCILIATION_REQUESTED);
    }

    @Transactional
    public UUID reconcileDeal(
            TenantId tenantId,
            PlatformConnectionId connectionId,
            ProviderObjectId dealId,
            ActivityContext actor) {
        requireReconciliationAccess(tenantId, connectionId);
        return request(new Request(tenantId, connectionId, OperationType.RECONCILE,
                ScopeType.DEAL, null, Objects.requireNonNull(dealId)), actor,
                ActivityAction.LINE_ITEM_RECONCILIATION_REQUESTED);
    }

    @Transactional
    public UUID reconcileLineItem(
            TenantId tenantId,
            PlatformConnectionId connectionId,
            UUID lineItemId,
            ActivityContext actor) {
        requireReconciliationAccess(tenantId, connectionId);
        return request(new Request(tenantId, connectionId, OperationType.RECONCILE,
                ScopeType.LINE_ITEM, Objects.requireNonNull(lineItemId), null), actor,
                ActivityAction.LINE_ITEM_RECONCILIATION_REQUESTED);
    }

    @Transactional
    public UUID replayLineItem(
            TenantId tenantId,
            PlatformConnectionId connectionId,
            UUID lineItemId,
            ActivityContext actor) {
        requireOwnedConnection(tenantId, connectionId);
        return request(new Request(tenantId, connectionId, OperationType.REPLAY,
                ScopeType.LINE_ITEM, Objects.requireNonNull(lineItemId), null), actor,
                ActivityAction.LINE_ITEM_REPLAY_REQUESTED);
    }

    @Transactional
    public UUID rebuildTenant(
            TenantId tenantId, PlatformConnectionId connectionId, ActivityContext actor) {
        requireOwnedConnection(tenantId, connectionId);
        return request(new Request(tenantId, connectionId, OperationType.REBUILD,
                ScopeType.TENANT, null, null), actor,
                ActivityAction.LINE_ITEM_REPLAY_REQUESTED);
    }

    private UUID request(
            Request request, ActivityContext actor, ActivityAction action) {
        Objects.requireNonNull(actor, "actor must not be null");
        UUID operationId = store.request(request, clock.instant());
        activityAudit.record(request.tenantId(), request.connectionId(), actor, action,
                ActivityResourceType.LINE_ITEM_WATCH_OPERATION, operationId.toString(),
                "REQUESTED", "PENDING");
        return operationId;
    }

    private void requireReconciliationAccess(
            TenantId tenantId, PlatformConnectionId connectionId) {
        PlatformConnection connection = requireOwnedConnection(tenantId, connectionId);
        if (connection.status() != ConnectionStatus.ACTIVE
                || !entitlements.isEnabled(tenantId, ProductModule.LINE_ITEM_WATCH)) {
            throw new ReliabilityOperationException(
                    OperationalErrorCode.RECONCILIATION_UNAVAILABLE);
        }
    }

    private PlatformConnection requireOwnedConnection(
            TenantId tenantId, PlatformConnectionId connectionId) {
        PlatformConnection connection = connections.findForTenant(
                        Objects.requireNonNull(tenantId), Objects.requireNonNull(connectionId))
                .orElseThrow(() -> new ReliabilityOperationException(
                        OperationalErrorCode.INVALID_RECOVERY_OPERATION));
        if (connection.provider() != Provider.HUBSPOT) {
            throw new ReliabilityOperationException(
                    OperationalErrorCode.INVALID_RECOVERY_OPERATION);
        }
        return connection;
    }
}
