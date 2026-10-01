package com.udmconsulting.modules.lineitemwatch.application;

import com.udmconsulting.modules.lineitemwatch.application.ReliabilityMaintenanceStore.AnchorResult;
import com.udmconsulting.modules.lineitemwatch.application.ReliabilityMaintenanceStore.ExhaustedSignal;
import com.udmconsulting.modules.lineitemwatch.application.ReliabilityMaintenanceStore.Inspection;
import com.udmconsulting.modules.lineitemwatch.application.ReliabilityMaintenanceStore.RetentionCutoffs;
import com.udmconsulting.modules.lineitemwatch.application.ReliabilityMaintenanceStore.RetentionPreview;
import com.udmconsulting.modules.lineitemwatch.application.ReliabilityMaintenanceStore.RetentionResult;
import com.udmconsulting.platform.activity.application.ActivityContext;
import com.udmconsulting.platform.activity.application.ApplicationActivityAudit;
import com.udmconsulting.platform.activity.domain.ActivityAction;
import com.udmconsulting.platform.activity.domain.ActivityResourceType;
import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.supportability.OperationalErrorCode;
import com.udmconsulting.platform.tenant.domain.TenantId;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReliabilityMaintenance {

    private final ReliabilityMaintenanceStore store;
    private final ApplicationActivityAudit audit;
    private final Clock clock;

    public ReliabilityMaintenance(
            ReliabilityMaintenanceStore store,
            ApplicationActivityAudit audit,
            Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.audit = Objects.requireNonNull(audit);
        this.clock = Objects.requireNonNull(clock);
    }

    public List<ExhaustedSignal> listExhausted(
            TenantId tenantId, PlatformConnectionId connectionId, UUID after, int limit) {
        if (limit < 1 || limit > 1_000) {
            throw new ReliabilityOperationException(
                    OperationalErrorCode.INVALID_RECOVERY_OPERATION);
        }
        return store.exhaustedSignals(tenantId, connectionId, after, limit);
    }

    @Transactional
    public void requeue(
            TenantId tenantId,
            PlatformConnectionId connectionId,
            UUID signalId,
            ActivityContext actor) {
        ReliabilityMaintenanceStore.RequeueResult result = store.requeueTerminalSignal(
                tenantId, connectionId, signalId, clock.instant());
        if (result == ReliabilityMaintenanceStore.RequeueResult.INVALID) {
            throw new ReliabilityOperationException(
                    OperationalErrorCode.INVALID_RECOVERY_OPERATION);
        }
        if (result == ReliabilityMaintenanceStore.RequeueResult.ALREADY_PENDING) {
            return;
        }
        audit.record(tenantId, connectionId, actor,
                ActivityAction.LINE_ITEM_SIGNAL_REQUEUED,
                ActivityResourceType.LINE_ITEM_WATCH_OPERATION,
                signalId.toString(), "FAILED", "PENDING");
    }

    public AnchorResult anchor(
            TenantId tenantId, PlatformConnectionId connectionId, UUID lineItemId) {
        return store.createAndVerifyAnchor(
                tenantId, connectionId, lineItemId, clock.instant());
    }

    public Inspection inspect(TenantId tenantId, PlatformConnectionId connectionId) {
        return store.inspect(tenantId, connectionId);
    }

    public RetentionPreview preview(
            TenantId tenantId,
            PlatformConnectionId connectionId,
            RetentionCutoffs cutoffs) {
        return store.previewRetention(tenantId, connectionId, cutoffs);
    }

    @Transactional
    public RetentionResult retain(
            TenantId tenantId,
            PlatformConnectionId connectionId,
            RetentionCutoffs cutoffs,
            boolean confirmed,
            int batchSize,
            ActivityContext actor) {
        RetentionResult result = store.executeRetention(
                tenantId, connectionId, cutoffs, confirmed, batchSize);
        audit.record(tenantId, connectionId, actor,
                ActivityAction.LINE_ITEM_RETENTION_EXECUTED,
                ActivityResourceType.LINE_ITEM_WATCH_OPERATION,
                UUID.randomUUID().toString(), "REQUESTED", "EXECUTED");
        return result;
    }

    @Transactional
    public void acknowledge(
            TenantId tenantId,
            PlatformConnectionId connectionId,
            UUID findingId,
            ActivityContext actor) {
        if (!store.acknowledgeFinding(
                tenantId, connectionId, findingId, clock.instant())) {
            throw new ReliabilityOperationException(
                    OperationalErrorCode.INVALID_RECOVERY_OPERATION);
        }
        audit.record(tenantId, connectionId, actor,
                ActivityAction.LINE_ITEM_FINDING_ACKNOWLEDGED,
                ActivityResourceType.LINE_ITEM_WATCH_OPERATION,
                findingId.toString(), "OPEN", "ACKNOWLEDGED");
    }

    @Transactional
    public void acknowledgeGap(
            TenantId tenantId,
            PlatformConnectionId connectionId,
            ActivityContext actor) {
        if (!store.acknowledgeGap(
                tenantId, connectionId, clock.instant())) {
            throw new ReliabilityOperationException(
                    OperationalErrorCode.INVALID_RECOVERY_OPERATION);
        }
        audit.record(tenantId, connectionId, actor,
                ActivityAction.LINE_ITEM_GAP_ACKNOWLEDGED,
                ActivityResourceType.LINE_ITEM_WATCH_OPERATION,
                connectionId.value().toString(), "POSSIBLE_GAP", "ACKNOWLEDGED");
    }
}
