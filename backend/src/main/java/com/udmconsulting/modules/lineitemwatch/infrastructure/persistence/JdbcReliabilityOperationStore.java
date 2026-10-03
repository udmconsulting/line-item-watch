package com.udmconsulting.modules.lineitemwatch.infrastructure.persistence;

import com.udmconsulting.modules.lineitemwatch.application.LineItemReliability;
import com.udmconsulting.modules.lineitemwatch.application.LineItemReliabilityStore;
import com.udmconsulting.modules.lineitemwatch.application.LineItemReconciliationSource.ReconciliationObservation;
import com.udmconsulting.modules.lineitemwatch.application.ReliabilityOperationException;
import com.udmconsulting.modules.lineitemwatch.application.ReliabilityOperationStore;
import com.udmconsulting.modules.lineitemwatch.domain.LineItemObservation;
import com.udmconsulting.modules.lineitemwatch.domain.LineItemProjection;
import com.udmconsulting.modules.lineitemwatch.domain.ProviderObjectId;
import com.udmconsulting.modules.lineitemwatch.domain.SnapshotKind;
import com.udmconsulting.modules.lineitemwatch.infrastructure.persistence.JdbcLineItemProjectionRepository.CurrentDrift;
import com.udmconsulting.modules.lineitemwatch.infrastructure.persistence.JdbcLineItemProjectionRepository.LockedLineItem;
import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.supportability.OperationalErrorCode;
import com.udmconsulting.platform.tenant.domain.TenantId;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcReliabilityOperationStore implements ReliabilityOperationStore {

    private final JdbcTemplate jdbcTemplate;
    private final JdbcLineItemProjectionRepository projectionRepository;
    private final LineItemReliabilityStore reliabilityStore;

    public JdbcReliabilityOperationStore(
            JdbcTemplate jdbcTemplate,
            JdbcLineItemProjectionRepository projectionRepository,
            LineItemReliabilityStore reliabilityStore) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
        this.projectionRepository = Objects.requireNonNull(projectionRepository);
        this.reliabilityStore = Objects.requireNonNull(reliabilityStore);
    }

    @Override
    @Transactional
    public UUID request(Request request, Instant now) {
        requireConnectionOwner(request.tenantId(), request.connectionId(), false);
        if (request.scopeType() == ScopeType.LINE_ITEM) {
            requireLineItemOwner(request.tenantId(), request.connectionId(), request.lineItemId());
        }
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO line_item_watch_reliability_operation (
                    id, tenant_id, connection_id, operation_type, scope_type,
                    line_item_id, external_deal_id, requested_at, next_attempt_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, request.tenantId().value(), request.connectionId().value(),
                request.operationType().name(), request.scopeType().name(),
                request.lineItemId(), request.dealId() == null ? null : request.dealId().value(),
                Timestamp.from(now), Timestamp.from(now));
        return id;
    }

    @Override
    @Transactional
    public Optional<ClaimedOperation> claimNext(Instant now, Duration leaseDuration) {
        UUID token = UUID.randomUUID();
        List<ClaimedOperation> rows = jdbcTemplate.query("""
                WITH candidate AS (
                    SELECT id
                    FROM line_item_watch_reliability_operation
                    WHERE (status = 'PENDING' AND next_attempt_at <= ?)
                       OR (status = 'CLAIMED' AND lease_until <= ?)
                    ORDER BY requested_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                UPDATE line_item_watch_reliability_operation operation
                SET status = 'CLAIMED', attempt_count = attempt_count + 1,
                    claim_token = ?, claimed_at = ?, lease_until = ?, completed_at = NULL
                FROM candidate
                WHERE operation.id = candidate.id
                RETURNING operation.*
                """, (row, ignored) -> new ClaimedOperation(
                        row.getObject("id", UUID.class),
                        new TenantId(row.getObject("tenant_id", UUID.class)),
                        new PlatformConnectionId(row.getObject("connection_id", UUID.class)),
                        OperationType.valueOf(row.getString("operation_type")),
                        ScopeType.valueOf(row.getString("scope_type")),
                        row.getObject("line_item_id", UUID.class),
                        row.getString("external_deal_id") == null
                                ? null : new ProviderObjectId(row.getString("external_deal_id")),
                        row.getObject("claim_token", UUID.class),
                        row.getInt("attempt_count"),
                        row.getObject("cursor_line_item_id", UUID.class),
                        row.getString("cursor_external_deal_id") == null
                                ? null : new ProviderObjectId(
                                        row.getString("cursor_external_deal_id"))),
                Timestamp.from(now), Timestamp.from(now), token, Timestamp.from(now),
                Timestamp.from(now.plus(leaseDuration)));
        return rows.stream().findFirst();
    }

    @Override
    @Transactional
    public int scheduleDueReconciliations(
            Instant lastReconciledBefore, int limit, Instant now) {
        if (limit < 1 || limit > 1_000) {
            throw new IllegalArgumentException("schedule limit must be between 1 and 1000");
        }
        Integer inserted = jdbcTemplate.queryForObject("""
                WITH eligible AS (
                    SELECT state.tenant_id, state.connection_id
                    FROM line_item_watch_reliability_state state
                    JOIN platform_connection connection
                      ON connection.tenant_id = state.tenant_id
                     AND connection.id = state.connection_id
                    JOIN tenant_entitlement entitlement
                      ON entitlement.tenant_id = state.tenant_id
                     AND entitlement.product_module = 'LINE_ITEM_WATCH'
                    WHERE connection.status = 'ACTIVE'
                      AND state.ingestion_state = 'OBSERVING'
                      AND (state.last_reconciled_at IS NULL
                           OR state.last_reconciled_at <= ?)
                      AND EXISTS (
                          SELECT 1 FROM line_item_watch_snapshot_deal tracked
                          WHERE tracked.tenant_id = state.tenant_id
                            AND tracked.connection_id = state.connection_id
                            AND tracked.snapshot_kind = 'LATEST'
                      )
                      AND NOT EXISTS (
                          SELECT 1 FROM line_item_watch_reliability_operation active
                          WHERE active.tenant_id = state.tenant_id
                            AND active.connection_id = state.connection_id
                            AND active.operation_type = 'RECONCILE'
                            AND active.status IN ('PENDING', 'CLAIMED')
                      )
                    ORDER BY COALESCE(state.last_reconciled_at, '-infinity'),
                             state.tenant_id, state.connection_id
                    FOR UPDATE OF state SKIP LOCKED
                    LIMIT ?
                ), inserted AS (
                    INSERT INTO line_item_watch_reliability_operation (
                        id, tenant_id, connection_id, operation_type, scope_type,
                        requested_at, next_attempt_at
                    )
                    SELECT gen_random_uuid(), tenant_id, connection_id,
                           'RECONCILE', 'TENANT', ?, ?
                    FROM eligible
                    RETURNING 1
                )
                SELECT COUNT(*) FROM inserted
                """, Integer.class, Timestamp.from(lastReconciledBefore), limit,
                Timestamp.from(now), Timestamp.from(now));
        return inserted == null ? 0 : inserted;
    }

    @Override
    @Transactional(readOnly = true)
    public MetricSnapshot metricSnapshot() {
        return jdbcTemplate.queryForObject("""
                SELECT
                    (SELECT COUNT(*) FROM line_item_watch_line_item_reliability
                     WHERE possible_gap_since IS NOT NULL)
                    + (SELECT COUNT(*) FROM line_item_watch_reliability_state
                       WHERE coverage_state = 'POSSIBLE_GAP') AS gaps,
                    (SELECT COUNT(*) FROM line_item_watch_signal_processing
                     WHERE status = 'FAILED') AS exhausted,
                    (SELECT COUNT(*)
                     FROM line_item_watch_reliability_state state
                     JOIN platform_connection connection
                       ON connection.tenant_id = state.tenant_id
                      AND connection.id = state.connection_id
                     WHERE connection.status = 'ACTIVE'
                       AND state.ingestion_state = 'OBSERVING'
                       AND state.last_reconciled_at IS NULL) AS never_reconciled,
                    (SELECT MIN(last_reconciled_at)
                     FROM line_item_watch_reliability_state
                     WHERE ingestion_state = 'OBSERVING') AS oldest_reconciled_at,
                    (SELECT MAX(last_reconciled_at)
                     FROM line_item_watch_reliability_state
                     WHERE ingestion_state = 'OBSERVING') AS latest_reconciled_at
                """, (row, ignored) -> new MetricSnapshot(
                        row.getLong("gaps"), row.getLong("exhausted"),
                        row.getLong("never_reconciled"),
                        row.getTimestamp("oldest_reconciled_at") == null
                                ? null : row.getTimestamp("oldest_reconciled_at").toInstant(),
                        row.getTimestamp("latest_reconciled_at") == null
                                ? null : row.getTimestamp("latest_reconciled_at").toInstant()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProviderObjectId> trackedDeals(
            TenantId tenantId,
            PlatformConnectionId connectionId,
            ProviderObjectId after,
            int limit) {
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT external_deal_id
                FROM line_item_watch_snapshot_deal
                WHERE tenant_id = ? AND connection_id = ? AND snapshot_kind = 'LATEST'
                  AND external_deal_id > ?
                ORDER BY external_deal_id
                LIMIT ?
                """, String.class, tenantId.value(), connectionId.value(),
                after == null ? "" : after.value(), limit).stream()
                .map(ProviderObjectId::new).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProviderObjectId> trackedDealForLineItem(
            TenantId tenantId, PlatformConnectionId connectionId, UUID lineItemId) {
        return jdbcTemplate.queryForList("""
                SELECT external_deal_id
                FROM line_item_watch_snapshot_deal
                WHERE tenant_id = ? AND connection_id = ? AND line_item_id = ?
                  AND snapshot_kind = 'LATEST'
                ORDER BY external_deal_id
                LIMIT 1
                """, String.class, tenantId.value(), connectionId.value(), lineItemId)
                .stream().findFirst().map(ProviderObjectId::new);
    }

    @Override
    @Transactional
    public ReconciliationCommit reconcileDeal(
            ClaimedOperation operation,
            ReconciliationObservation providerRead,
            Instant committedAt) {
        lockClaim(operation);
        long generation = requireConnectionOwner(
                operation.tenantId(), operation.connectionId(), true);
        if (providerRead.expectedCredentialGeneration() >= 0
                && generation != providerRead.expectedCredentialGeneration()) {
            throw new ReliabilityOperationException(
                    OperationalErrorCode.RECONCILIATION_CONFLICT);
        }
        requireEntitlement(operation.tenantId());
        if (operation.scopeType() == ScopeType.DEAL
                && (operation.dealId() == null
                    || !operation.dealId().equals(providerRead.observations().dealId()))) {
            throw new ReliabilityOperationException(
                    OperationalErrorCode.RECONCILIATION_CONFLICT);
        }

        ProviderObjectId targetLineItem = operation.scopeType() == ScopeType.LINE_ITEM
                ? jdbcTemplate.queryForObject("""
                        SELECT external_line_item_id FROM line_item_watch_line_item
                        WHERE tenant_id = ? AND connection_id = ? AND id = ?
                        FOR UPDATE
                        """, (row, ignored) -> new ProviderObjectId(
                                row.getString("external_line_item_id")),
                        operation.tenantId().value(), operation.connectionId().value(),
                        operation.lineItemId())
                : null;
        if (operation.scopeType() == ScopeType.LINE_ITEM
                && jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM line_item_watch_snapshot_deal
                        WHERE tenant_id = ? AND connection_id = ? AND line_item_id = ?
                          AND snapshot_kind = 'LATEST' AND external_deal_id = ?
                        """, Long.class, operation.tenantId().value(),
                        operation.connectionId().value(), operation.lineItemId(),
                        providerRead.observations().dealId().value()) != 1) {
            throw new ReliabilityOperationException(
                    OperationalErrorCode.RECONCILIATION_CONFLICT);
        }
        Set<UUID> localForDeal = operation.scopeType() == ScopeType.LINE_ITEM
                ? new HashSet<>(Set.of(operation.lineItemId()))
                : new HashSet<>(jdbcTemplate.queryForList("""
                        SELECT line_item_id
                        FROM line_item_watch_snapshot_deal
                        WHERE tenant_id = ? AND connection_id = ?
                          AND snapshot_kind = 'LATEST' AND external_deal_id = ?
                        """, UUID.class, operation.tenantId().value(),
                        operation.connectionId().value(), operation.dealId().value()));
        int properties = 0;
        int associations = 0;
        int unknown = 0;
        int conflicts = 0;
        List<LineItemObservation> ordered = providerRead.observations().lineItems().stream()
                .filter(value -> targetLineItem == null
                        || targetLineItem.equals(value.lineItemId()))
                .sorted(Comparator.comparing(value -> value.lineItemId().value()))
                .toList();
        if (operation.scopeType() == ScopeType.LINE_ITEM && ordered.isEmpty()) {
            // A Deal-scoped provider read proves only that the association is absent. It does
            // not prove that the Line Item itself was deleted or provide a deletion timestamp.
            finding(operation, operation.lineItemId(), "ASSOCIATION_DRIFT", committedAt);
            jdbcTemplate.update("""
                    UPDATE line_item_watch_line_item_reliability
                    SET provider_state = 'UNKNOWN', reconciliation_outcome = 'CONFLICT',
                        last_reconciled_at = ?, possible_gap_since = COALESCE(possible_gap_since, ?),
                        updated_at = ?
                    WHERE tenant_id = ? AND connection_id = ? AND line_item_id = ?
                    """, Timestamp.from(committedAt), Timestamp.from(committedAt),
                    Timestamp.from(committedAt), operation.tenantId().value(),
                    operation.connectionId().value(), operation.lineItemId());
            reliabilityStore.markPossibleGap(
                    operation.tenantId(), operation.connectionId(), committedAt);
            reliabilityStore.reconciled(operation.tenantId(), operation.connectionId(),
                    committedAt, LineItemReliability.ReconciliationOutcome.CONFLICT);
            return new ReconciliationCommit(0, 0, 1, 0, 1, true);
        }
        for (LineItemObservation observation : ordered) {
            LockedLineItem item = projectionRepository.ensureAndLock(
                    operation.tenantId(), operation.connectionId(), observation.lineItemId());
            CurrentDrift drift = projectionRepository.compareLatest(item.id(), observation);
            localForDeal.remove(item.id());
            if (drift.stateConflict()) {
                conflicts++;
                finding(operation, item.id(), "PROVIDER_STATE_CONFLICT", committedAt);
                jdbcTemplate.update("""
                        UPDATE line_item_watch_line_item_reliability
                        SET provider_state = 'PRESENT', reconciliation_outcome = 'CONFLICT',
                            last_reconciled_at = ?, possible_gap_since = COALESCE(possible_gap_since, ?),
                            updated_at = ?
                        WHERE tenant_id = ? AND connection_id = ? AND line_item_id = ?
                        """, Timestamp.from(committedAt), Timestamp.from(committedAt),
                        Timestamp.from(committedAt), operation.tenantId().value(),
                        operation.connectionId().value(), item.id());
                continue;
            }
            projectionRepository.upsertCompleteCheckpoint(
                    operation.tenantId(), operation.connectionId(), item,
                    SnapshotKind.OBSERVED, observation);
            projectionRepository.rebuildLocked(
                    operation.tenantId(), operation.connectionId(), item, null);
            if (item.created()) {
                unknown++;
                finding(operation, item.id(), "LOCALLY_UNKNOWN_PROVIDER_OBJECT", committedAt);
            } else {
                if (drift.propertyDrift()) {
                    properties++;
                    finding(operation, item.id(), "PROPERTY_DRIFT", committedAt);
                }
                if (drift.associationDrift()) {
                    associations++;
                    finding(operation, item.id(), "ASSOCIATION_DRIFT", committedAt);
                }
            }
            jdbcTemplate.update("""
                    UPDATE line_item_watch_line_item_reliability
                    SET provider_state = 'PRESENT', reconciliation_outcome = ?,
                        last_reconciled_at = ?, updated_at = ?
                    WHERE tenant_id = ? AND connection_id = ? AND line_item_id = ?
                    """, drift.propertyDrift() || drift.associationDrift() || item.created()
                            ? "DRIFT_REPAIRED" : "SUCCEEDED",
                    Timestamp.from(committedAt), Timestamp.from(committedAt),
                    operation.tenantId().value(), operation.connectionId().value(), item.id());
        }
        for (UUID unobserved : localForDeal) {
            associations++;
            finding(operation, unobserved, "ASSOCIATION_DRIFT", committedAt);
            jdbcTemplate.update("""
                    UPDATE line_item_watch_line_item_reliability
                    SET possible_gap_since = COALESCE(possible_gap_since, ?),
                        reconciliation_outcome = 'CONFLICT', last_reconciled_at = ?, updated_at = ?
                    WHERE tenant_id = ? AND connection_id = ? AND line_item_id = ?
                    """, Timestamp.from(committedAt), Timestamp.from(committedAt),
                    Timestamp.from(committedAt), operation.tenantId().value(),
                    operation.connectionId().value(), unobserved);
        }
        boolean drift = properties + associations + unknown > 0;
        boolean gap = drift || conflicts > 0;
        if (gap) {
            reliabilityStore.markPossibleGap(
                    operation.tenantId(), operation.connectionId(), committedAt);
        }
        reliabilityStore.reconciled(
                operation.tenantId(), operation.connectionId(), committedAt,
                conflicts > 0 ? LineItemReliability.ReconciliationOutcome.CONFLICT
                        : drift ? LineItemReliability.ReconciliationOutcome.DRIFT_REPAIRED
                                : LineItemReliability.ReconciliationOutcome.SUCCEEDED);
        return new ReconciliationCommit(
                ordered.size(), properties, associations, unknown, conflicts, gap);
    }

    @Override
    @Transactional
    public ReplayResult replayLineItem(ClaimedOperation operation, Instant replayedAt) {
        lockClaim(operation);
        requireConnectionOwner(operation.tenantId(), operation.connectionId(), false);
        requireLineItemOwner(operation.tenantId(), operation.connectionId(), operation.lineItemId());
        ProviderObjectId externalId = jdbcTemplate.queryForObject("""
                SELECT external_line_item_id FROM line_item_watch_line_item
                WHERE tenant_id = ? AND connection_id = ? AND id = ?
                FOR UPDATE
                """, (row, ignored) -> new ProviderObjectId(
                        row.getString("external_line_item_id")),
                operation.tenantId().value(), operation.connectionId().value(),
                operation.lineItemId());
        LockedLineItem item = new LockedLineItem(operation.lineItemId(), externalId, false);
        try {
            LineItemProjection projection = projectionRepository.rebuildFromRetainedEvidenceLocked(
                    operation.tenantId(), operation.connectionId(), item);
            return new ReplayResult(1, projection.auditEvents().size());
        } catch (IllegalArgumentException exception) {
            throw new ReliabilityOperationException(
                    OperationalErrorCode.INSUFFICIENT_RETAINED_EVIDENCE, exception);
        }
    }

    @Override
    @Transactional
    public void recordReconciliationFailure(
            ClaimedOperation operation, String findingType, Instant detectedAt) {
        lockClaim(operation);
        if (!Set.of("PROVIDER_OBJECT_ABSENT", "PROVIDER_OBJECT_INACCESSIBLE",
                "PROVIDER_STATE_CONFLICT").contains(findingType)) {
            throw new ReliabilityOperationException(
                    OperationalErrorCode.INVALID_RECOVERY_OPERATION);
        }
        finding(operation, operation.lineItemId(), findingType, detectedAt);
        reliabilityStore.markPossibleGap(
                operation.tenantId(), operation.connectionId(), detectedAt);
        reliabilityStore.reconciled(operation.tenantId(), operation.connectionId(), detectedAt,
                "PROVIDER_STATE_CONFLICT".equals(findingType)
                        ? LineItemReliability.ReconciliationOutcome.CONFLICT
                        : LineItemReliability.ReconciliationOutcome.UNAVAILABLE);
    }

    @Override
    @Transactional
    public int expandReplayTenant(ClaimedOperation operation, int limit, Instant now) {
        lockClaim(operation);
        List<UUID> lineItems = jdbcTemplate.queryForList("""
                SELECT id FROM line_item_watch_line_item
                WHERE tenant_id = ? AND connection_id = ? AND id > ?
                ORDER BY id LIMIT ?
                """, UUID.class, operation.tenantId().value(), operation.connectionId().value(),
                operation.cursorLineItemId() == null
                        ? new UUID(0, 0) : operation.cursorLineItemId(), limit);
        for (UUID lineItemId : lineItems) {
            request(new Request(operation.tenantId(), operation.connectionId(),
                    OperationType.REPLAY, ScopeType.LINE_ITEM, lineItemId, null), now);
        }
        if (!lineItems.isEmpty()) {
            jdbcTemplate.update("""
                    UPDATE line_item_watch_reliability_operation SET cursor_line_item_id = ?
                    WHERE id = ? AND claim_token = ?
                    """, lineItems.getLast(), operation.id(), operation.claimToken());
        }
        return lineItems.size();
    }

    @Override
    @Transactional
    public int expandReconciliationTenant(
            ClaimedOperation operation, int limit, Instant now) {
        lockClaim(operation);
        List<ProviderObjectId> deals = trackedDeals(
                operation.tenantId(), operation.connectionId(), operation.cursorDealId(), limit);
        for (ProviderObjectId dealId : deals) {
            request(new Request(operation.tenantId(), operation.connectionId(),
                    OperationType.RECONCILE, ScopeType.DEAL, null, dealId), now);
        }
        if (!deals.isEmpty()) {
            jdbcTemplate.update("""
                    UPDATE line_item_watch_reliability_operation
                    SET cursor_external_deal_id = ?
                    WHERE id = ? AND claim_token = ?
                    """, deals.getLast().value(), operation.id(), operation.claimToken());
        }
        return deals.size();
    }

    @Override
    @Transactional
    public boolean succeed(ClaimedOperation operation, Instant completedAt) {
        return jdbcTemplate.update("""
                UPDATE line_item_watch_reliability_operation
                SET status = 'SUCCEEDED', claim_token = NULL, claimed_at = NULL,
                    lease_until = NULL, completed_at = ?, last_error_code = NULL
                WHERE id = ? AND tenant_id = ? AND connection_id = ?
                  AND status = 'CLAIMED' AND claim_token = ?
                """, Timestamp.from(completedAt), operation.id(),
                operation.tenantId().value(), operation.connectionId().value(),
                operation.claimToken()) == 1;
    }

    @Override
    @Transactional
    public boolean continuePending(ClaimedOperation operation, Instant now) {
        return jdbcTemplate.update("""
                UPDATE line_item_watch_reliability_operation
                SET status = 'PENDING', next_attempt_at = ?, claim_token = NULL,
                    claimed_at = NULL, lease_until = NULL, completed_at = NULL
                WHERE id = ? AND tenant_id = ? AND connection_id = ?
                  AND status = 'CLAIMED' AND claim_token = ?
                """, Timestamp.from(now), operation.id(), operation.tenantId().value(),
                operation.connectionId().value(), operation.claimToken()) == 1;
    }

    @Override
    @Transactional
    public boolean retry(
            ClaimedOperation operation,
            OperationalErrorCode errorCode,
            Instant now,
            Duration delay,
            int maxAttempts) {
        boolean terminal = operation.attempt() >= maxAttempts;
        return jdbcTemplate.update("""
                UPDATE line_item_watch_reliability_operation
                SET status = ?, next_attempt_at = ?, claim_token = NULL, claimed_at = NULL,
                    lease_until = NULL, completed_at = ?, last_error_code = ?
                WHERE id = ? AND tenant_id = ? AND connection_id = ?
                  AND status = 'CLAIMED' AND claim_token = ?
                """, terminal ? "FAILED" : "PENDING", Timestamp.from(now.plus(delay)),
                terminal ? Timestamp.from(now) : null, errorCode.name(), operation.id(),
                operation.tenantId().value(), operation.connectionId().value(),
                operation.claimToken()) == 1;
    }

    private void finding(
            ClaimedOperation operation, UUID lineItemId, String type, Instant detectedAt) {
        jdbcTemplate.update("""
                INSERT INTO line_item_watch_reconciliation_finding (
                    id, tenant_id, connection_id, operation_id, line_item_id,
                    finding_type, detected_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), operation.tenantId().value(),
                operation.connectionId().value(), operation.id(), lineItemId, type,
                Timestamp.from(detectedAt));
    }

    private void lockClaim(ClaimedOperation operation) {
        Integer found = jdbcTemplate.query("""
                SELECT 1 FROM line_item_watch_reliability_operation
                WHERE id = ? AND tenant_id = ? AND connection_id = ?
                  AND status = 'CLAIMED' AND claim_token = ?
                FOR UPDATE
                """, (row, ignored) -> 1, operation.id(), operation.tenantId().value(),
                operation.connectionId().value(), operation.claimToken())
                .stream().findFirst().orElseThrow(() -> new ReliabilityOperationException(
                        OperationalErrorCode.RECONCILIATION_CONFLICT));
    }

    private long requireConnectionOwner(
            TenantId tenantId, PlatformConnectionId connectionId, boolean requireActive) {
        return jdbcTemplate.query("""
                SELECT credential_generation, status
                FROM platform_connection
                WHERE tenant_id = ? AND id = ?
                FOR UPDATE
                """, (row, ignored) -> {
                    if (requireActive && !"ACTIVE".equals(row.getString("status"))) {
                        throw new ReliabilityOperationException(
                                OperationalErrorCode.RECONCILIATION_CONFLICT);
                    }
                    return row.getLong("credential_generation");
                }, tenantId.value(), connectionId.value()).stream().findFirst()
                .orElseThrow(() -> new ReliabilityOperationException(
                        OperationalErrorCode.INVALID_RECOVERY_OPERATION));
    }

    private void requireEntitlement(TenantId tenantId) {
        if (jdbcTemplate.query("""
                SELECT 1 FROM tenant_entitlement
                WHERE tenant_id = ? AND product_module = 'LINE_ITEM_WATCH'
                FOR UPDATE
                """, (row, ignored) -> 1, tenantId.value()).isEmpty()) {
            throw new ReliabilityOperationException(
                    OperationalErrorCode.RECONCILIATION_CONFLICT);
        }
    }

    private void requireLineItemOwner(
            TenantId tenantId, PlatformConnectionId connectionId, UUID lineItemId) {
        if (lineItemId == null || jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM line_item_watch_line_item
                WHERE tenant_id = ? AND connection_id = ? AND id = ?
                """, Long.class, tenantId.value(), connectionId.value(), lineItemId) != 1) {
            throw new ReliabilityOperationException(
                    OperationalErrorCode.INVALID_RECOVERY_OPERATION);
        }
    }
}
