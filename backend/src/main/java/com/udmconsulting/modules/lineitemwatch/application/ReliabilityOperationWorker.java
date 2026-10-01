package com.udmconsulting.modules.lineitemwatch.application;

import com.udmconsulting.modules.lineitemwatch.application.LineItemReconciliationSource.ReconciliationObservation;
import com.udmconsulting.modules.lineitemwatch.application.ReliabilityOperationStore.ClaimedOperation;
import com.udmconsulting.modules.lineitemwatch.application.ReliabilityOperationStore.ReconciliationCommit;
import com.udmconsulting.modules.lineitemwatch.domain.ProviderObjectId;
import com.udmconsulting.platform.connection.application.PlatformConnectionService;
import com.udmconsulting.platform.connection.domain.PlatformConnection;
import com.udmconsulting.platform.supportability.OperationalErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "line-item-watch.reliability", name = "enabled", havingValue = "true")
public final class ReliabilityOperationWorker {

    private final ReliabilityOperationStore store;
    private final LineItemReconciliationSource providerSource;
    private final PlatformConnectionService connections;
    private final ReliabilityProcessingProperties properties;
    private final ReliabilityMetrics metrics;
    private final Clock clock;

    public ReliabilityOperationWorker(
            ReliabilityOperationStore store,
            LineItemReconciliationSource providerSource,
            PlatformConnectionService connections,
            ReliabilityProcessingProperties properties,
            ReliabilityMetrics metrics,
            Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.providerSource = Objects.requireNonNull(providerSource);
        this.connections = Objects.requireNonNull(connections);
        this.properties = Objects.requireNonNull(properties);
        this.metrics = Objects.requireNonNull(metrics);
        this.clock = Objects.requireNonNull(clock);
    }

    @Scheduled(fixedDelayString = "${line-item-watch.reliability.poll-delay:5s}")
    public void poll() {
        for (int index = 0; index < properties.maxPerPoll(); index++) {
            Instant now = clock.instant();
            var claimed = store.claimNext(now, properties.leaseDuration());
            if (claimed.isEmpty()) {
                return;
            }
            execute(claimed.orElseThrow(), now);
        }
    }

    @Scheduled(
            fixedDelayString = "${line-item-watch.reliability.schedule-delay:1m}",
            initialDelayString = "${line-item-watch.reliability.schedule-delay:1m}")
    public void scheduleTrackedReconciliation() {
        Instant now = clock.instant();
        store.scheduleDueReconciliations(
                now.minus(properties.reconciliationInterval()),
                properties.reconciliationScheduleBatchSize(),
                now);
        ReliabilityOperationStore.MetricSnapshot snapshot = store.metricSnapshot();
        metrics.updateCounts(snapshot.suspectedGaps(), snapshot.exhaustedSignals());
        metrics.updateAge(snapshot.oldestReconciledAt(), now);
    }

    void execute(ClaimedOperation operation, Instant now) {
        try {
            switch (operation.operationType()) {
                case RECONCILE -> reconcile(operation, now);
                case REPLAY -> replay(operation, now);
                case REBUILD -> expandReplay(operation, now);
            }
        } catch (ReliabilityOperationException exception) {
            boolean insufficient = exception.errorCode()
                    == OperationalErrorCode.INSUFFICIENT_RETAINED_EVIDENCE;
            if (insufficient) {
                metrics.replayed(ReliabilityMetrics.ReplayOutcome.INSUFFICIENT_EVIDENCE);
            }
            fail(operation, exception.errorCode(), now, true, null, !insufficient);
        } catch (BaselineSyncException exception) {
            if (!exception.retryable()
                    && operation.operationType()
                            == ReliabilityOperationStore.OperationType.RECONCILE) {
                String finding = switch (exception.failure()) {
                    case PROVIDER_DEAL_NOT_FOUND -> "PROVIDER_OBJECT_ABSENT";
                    case PROVIDER_AUTHORIZATION_REJECTED -> "PROVIDER_OBJECT_INACCESSIBLE";
                    default -> "PROVIDER_STATE_CONFLICT";
                };
                store.recordReconciliationFailure(operation, finding, now);
            }
            fail(operation, exception.retryable()
                    ? OperationalErrorCode.RECONCILIATION_UNAVAILABLE
                    : OperationalErrorCode.RECONCILIATION_CONFLICT, now,
                    exception.retryable(), exception.retryAfter());
        } catch (RuntimeException exception) {
            fail(operation, operation.operationType()
                    == ReliabilityOperationStore.OperationType.RECONCILE
                    ? OperationalErrorCode.RECONCILIATION_UNAVAILABLE
                    : OperationalErrorCode.REPLAY_FAILED, now);
        }
    }

    private void reconcile(ClaimedOperation operation, Instant now) {
        if (operation.scopeType() == ReliabilityOperationStore.ScopeType.TENANT) {
            int expanded = store.expandReconciliationTenant(
                    operation, properties.expansionPageSize(), now);
            finishExpansion(operation, expanded, now);
            return;
        }
        ProviderObjectId dealId = operation.scopeType()
                == ReliabilityOperationStore.ScopeType.DEAL
                ? operation.dealId()
                : store.trackedDealForLineItem(
                        operation.tenantId(), operation.connectionId(), operation.lineItemId())
                        .orElseThrow(() -> new ReliabilityOperationException(
                                OperationalErrorCode.RECONCILIATION_CONFLICT));
        PlatformConnection connection = connections.findForTenant(
                        operation.tenantId(), operation.connectionId())
                .orElseThrow(() -> new ReliabilityOperationException(
                        OperationalErrorCode.RECONCILIATION_CONFLICT));

        // This provider call deliberately occurs before the transactional commit method.
        ReconciliationObservation observation =
                providerSource.readDealCurrent(connection, dealId);
        ReconciliationCommit result = store.reconcileDeal(operation, observation, clock.instant());
        store.succeed(operation, clock.instant());
        metrics.reconciled(result.stateConflicts() > 0
                ? LineItemReliability.ReconciliationOutcome.CONFLICT
                : result.driftRepaired()
                        ? LineItemReliability.ReconciliationOutcome.DRIFT_REPAIRED
                        : LineItemReliability.ReconciliationOutcome.SUCCEEDED, now);
    }

    private void replay(ClaimedOperation operation, Instant now) {
        store.replayLineItem(operation, now);
        store.succeed(operation, clock.instant());
        metrics.replayed(ReliabilityMetrics.ReplayOutcome.SUCCEEDED);
    }

    private void expandReplay(ClaimedOperation operation, Instant now) {
        int expanded = store.expandReplayTenant(operation, properties.expansionPageSize(), now);
        finishExpansion(operation, expanded, now);
    }

    private void finishExpansion(ClaimedOperation operation, int expanded, Instant now) {
        if (expanded == properties.expansionPageSize()) {
            store.continuePending(operation, now);
        } else {
            store.succeed(operation, now);
        }
    }

    private void fail(
            ClaimedOperation operation, OperationalErrorCode errorCode, Instant now) {
        fail(operation, errorCode, now, true);
    }

    private void fail(
            ClaimedOperation operation,
            OperationalErrorCode errorCode,
            Instant now,
            boolean retryable) {
        fail(operation, errorCode, now, retryable, null);
    }

    private void fail(
            ClaimedOperation operation,
            OperationalErrorCode errorCode,
            Instant now,
            boolean retryable,
            Duration retryAfter) {
        fail(operation, errorCode, now, retryable, retryAfter, true);
    }

    private void fail(
            ClaimedOperation operation,
            OperationalErrorCode errorCode,
            Instant now,
            boolean retryable,
            Duration retryAfter,
            boolean recordMetric) {
        Duration multiplier = properties.baseBackoff()
                .multipliedBy(1L << Math.min(20, Math.max(0, operation.attempt() - 1)));
        Duration delay = multiplier.compareTo(properties.maxBackoff()) > 0
                ? properties.maxBackoff() : multiplier;
        if (retryAfter != null && retryAfter.compareTo(delay) > 0) {
            delay = retryAfter.compareTo(properties.maxBackoff()) > 0
                    ? properties.maxBackoff() : retryAfter;
        }
        store.retry(operation, errorCode, now, delay,
                retryable ? properties.maxAttempts() : operation.attempt());
        if (!recordMetric) {
            return;
        }
        if (operation.operationType() != ReliabilityOperationStore.OperationType.RECONCILE) {
            metrics.replayed(ReliabilityMetrics.ReplayOutcome.FAILED);
        } else {
            metrics.reconciled(LineItemReliability.ReconciliationOutcome.UNAVAILABLE, now);
        }
    }
}
