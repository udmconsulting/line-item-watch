package com.udmconsulting.modules.lineitemwatch.application;

import com.udmconsulting.modules.lineitemwatch.application.LineItemReconciliationSource.ReconciliationObservation;
import com.udmconsulting.modules.lineitemwatch.domain.ProviderObjectId;
import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.supportability.OperationalErrorCode;
import com.udmconsulting.platform.tenant.domain.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReliabilityOperationStore {

    UUID request(Request request, Instant now);

    Optional<ClaimedOperation> claimNext(Instant now, Duration leaseDuration);

    int scheduleDueReconciliations(Instant lastReconciledBefore, int limit, Instant now);

    MetricSnapshot metricSnapshot();

    List<ProviderObjectId> trackedDeals(
            TenantId tenantId,
            PlatformConnectionId connectionId,
            ProviderObjectId after,
            int limit);

    Optional<ProviderObjectId> trackedDealForLineItem(
            TenantId tenantId, PlatformConnectionId connectionId, UUID lineItemId);

    ReconciliationCommit reconcileDeal(
            ClaimedOperation operation,
            ReconciliationObservation observation,
            Instant committedAt);

    ReplayResult replayLineItem(ClaimedOperation operation, Instant replayedAt);

    void recordReconciliationFailure(
            ClaimedOperation operation, String findingType, Instant detectedAt);

    int expandReplayTenant(ClaimedOperation operation, int limit, Instant now);

    int expandReconciliationTenant(ClaimedOperation operation, int limit, Instant now);

    boolean succeed(ClaimedOperation operation, Instant completedAt);

    boolean continuePending(ClaimedOperation operation, Instant now);

    boolean retry(
            ClaimedOperation operation,
            OperationalErrorCode errorCode,
            Instant now,
            Duration delay,
            int maxAttempts);

    enum OperationType { RECONCILE, REPLAY, REBUILD }

    enum ScopeType { TENANT, DEAL, LINE_ITEM }

    record Request(
            TenantId tenantId,
            PlatformConnectionId connectionId,
            OperationType operationType,
            ScopeType scopeType,
            UUID lineItemId,
            ProviderObjectId dealId) {
    }

    record ClaimedOperation(
            UUID id,
            TenantId tenantId,
            PlatformConnectionId connectionId,
            OperationType operationType,
            ScopeType scopeType,
            UUID lineItemId,
            ProviderObjectId dealId,
            UUID claimToken,
            int attempt,
            UUID cursorLineItemId,
            ProviderObjectId cursorDealId) {
    }

    record ReconciliationCommit(
            int observed,
            int propertyDrift,
            int associationDrift,
            int locallyUnknown,
            int stateConflicts,
            boolean possibleHistoricalGap) {

        public boolean driftRepaired() {
            return propertyDrift + associationDrift + locallyUnknown > 0;
        }
    }

    record ReplayResult(int rebuilt, int semanticEvents) {
    }

    record MetricSnapshot(
            long suspectedGaps,
            long exhaustedSignals,
            long neverReconciledActiveConnections,
            Instant oldestReconciledAt,
            Instant latestReconciledAt) {
    }
}
