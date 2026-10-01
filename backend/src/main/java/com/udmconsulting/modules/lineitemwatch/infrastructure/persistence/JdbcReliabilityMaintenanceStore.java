package com.udmconsulting.modules.lineitemwatch.infrastructure.persistence;

import com.udmconsulting.modules.lineitemwatch.application.ReliabilityMaintenanceStore;
import com.udmconsulting.modules.lineitemwatch.application.ReliabilityOperationException;
import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.supportability.OperationalErrorCode;
import com.udmconsulting.platform.tenant.domain.TenantId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcReliabilityMaintenanceStore implements ReliabilityMaintenanceStore {

    private final JdbcTemplate jdbcTemplate;

    public JdbcReliabilityMaintenanceStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExhaustedSignal> exhaustedSignals(
            TenantId tenantId, PlatformConnectionId connectionId, UUID after, int limit) {
        requireConnection(tenantId, connectionId);
        return jdbcTemplate.query("""
                SELECT signal_id, failure_disposition, last_error_code
                FROM line_item_watch_signal_processing
                WHERE tenant_id = ? AND connection_id = ? AND status = 'FAILED'
                  AND signal_id > ?
                ORDER BY signal_id
                LIMIT ?
                """, (row, ignored) -> new ExhaustedSignal(
                        row.getObject("signal_id", UUID.class),
                        row.getString("failure_disposition"),
                        row.getString("last_error_code")),
                tenantId.value(), connectionId.value(), zero(after), limit);
    }

    @Override
    @Transactional
    public RequeueResult requeueTerminalSignal(
            TenantId tenantId,
            PlatformConnectionId connectionId,
            UUID signalId,
            Instant now) {
        requireConnection(tenantId, connectionId);
        int updated = jdbcTemplate.update("""
                UPDATE line_item_watch_signal_processing
                SET status = 'PENDING', attempt_count = 0, next_attempt_at = ?,
                    claim_token = NULL, claimed_at = NULL, lease_until = NULL,
                    processed_at = NULL, failed_at = NULL, last_error_code = NULL,
                    failure_disposition = NULL, updated_at = ?
                WHERE tenant_id = ? AND connection_id = ? AND signal_id = ?
                  AND status = 'FAILED'
                  AND failure_disposition IN ('PERMANENT', 'RETRY_EXHAUSTED')
                """, Timestamp.from(now), Timestamp.from(now), tenantId.value(),
                connectionId.value(), signalId);
        if (updated == 1) {
            return RequeueResult.REQUEUED;
        }
        List<Boolean> alreadyRequeued = jdbcTemplate.query("""
                SELECT EXISTS (
                    SELECT 1 FROM application_activity_audit audit
                    WHERE audit.tenant_id = processing.tenant_id
                      AND audit.connection_id = processing.connection_id
                      AND audit.action = 'LINE_ITEM_SIGNAL_REQUEUED'
                      AND audit.resource_reference = processing.signal_id::text
                ) AS requeued
                FROM line_item_watch_signal_processing processing
                WHERE processing.tenant_id = ? AND processing.connection_id = ?
                  AND processing.signal_id = ? AND processing.status = 'PENDING'
                """, (row, ignored) -> row.getBoolean("requeued"),
                tenantId.value(), connectionId.value(), signalId);
        return alreadyRequeued.size() == 1 && alreadyRequeued.getFirst()
                ? RequeueResult.ALREADY_PENDING : RequeueResult.INVALID;
    }

    @Override
    @Transactional
    public AnchorResult createAndVerifyAnchor(
            TenantId tenantId,
            PlatformConnectionId connectionId,
            UUID lineItemId,
            Instant now) {
        requireConnection(tenantId, connectionId);
        jdbcTemplate.queryForObject("""
                SELECT id FROM line_item_watch_line_item
                WHERE tenant_id = ? AND connection_id = ? AND id = ?
                FOR UPDATE
                """, UUID.class, tenantId.value(), connectionId.value(), lineItemId);
        Long trustworthy = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM line_item_watch_snapshot latest
                JOIN line_item_watch_reliability_state state
                  ON state.tenant_id = latest.tenant_id
                 AND state.connection_id = latest.connection_id
                JOIN line_item_watch_line_item_reliability item
                  ON item.tenant_id = latest.tenant_id
                 AND item.connection_id = latest.connection_id
                 AND item.line_item_id = latest.line_item_id
                WHERE latest.tenant_id = ? AND latest.connection_id = ?
                  AND latest.line_item_id = ? AND latest.snapshot_kind = 'LATEST'
                  AND latest.deal_set_complete
                  AND cardinality(latest.known_properties) = 10
                  AND latest.provider_created_at IS NOT NULL
                  AND latest.provider_updated_at IS NOT NULL
                  AND state.ingestion_state = 'OBSERVING'
                  AND state.coverage_state = 'NO_KNOWN_GAP'
                  AND item.possible_gap_since IS NULL
                  AND item.provider_state <> 'INACCESSIBLE'
                """, Long.class, tenantId.value(), connectionId.value(), lineItemId);
        if (trustworthy == null || trustworthy != 1) {
            throw new ReliabilityOperationException(
                    OperationalErrorCode.INSUFFICIENT_RETAINED_EVIDENCE);
        }

        UUID anchorId = UUID.randomUUID();
        int inserted = jdbcTemplate.update("""
                INSERT INTO line_item_watch_replay_anchor (
                    id, tenant_id, connection_id, line_item_id, anchor_at,
                    lifecycle_state, name, quantity, unit_price, unit_discount,
                    discount_percentage, billing_frequency, billing_start_date,
                    billing_start_delay_unit, billing_start_delay_count,
                    recurring_billing_period, known_properties, deal_set_complete,
                    provider_created_at, provider_updated_at, observed_at, deleted_at,
                    history_coverage_mode, history_observed_from,
                    evidence_through_occurred_at, evidence_through_deduplication_key,
                    evidence_through_signal_id, trusted, verified_at
                )
                SELECT ?, latest.tenant_id, latest.connection_id, latest.line_item_id, ?,
                       CASE WHEN latest.deleted_at IS NULL THEN 'PRESENT' ELSE 'DELETED' END,
                       latest.name, latest.quantity, latest.unit_price, latest.unit_discount,
                       latest.discount_percentage, latest.billing_frequency,
                       latest.billing_start_date, latest.billing_start_delay_unit,
                       latest.billing_start_delay_count, latest.recurring_billing_period,
                       latest.known_properties, latest.deal_set_complete,
                       latest.provider_created_at, latest.provider_updated_at,
                       latest.observed_at, latest.deleted_at,
                       latest.history_coverage_mode, latest.history_observed_from,
                       watermark.occurred_at, watermark.provider_deduplication_key,
                       watermark.id, TRUE, ?
                FROM line_item_watch_snapshot latest
                LEFT JOIN LATERAL (
                    SELECT signal.occurred_at, signal.provider_deduplication_key, signal.id
                    FROM line_item_watch_change_signal signal
                    JOIN line_item_watch_signal_processing processing
                      ON processing.signal_id = signal.id
                    JOIN line_item_watch_line_item owned
                      ON owned.tenant_id = signal.tenant_id
                     AND owned.connection_id = signal.connection_id
                     AND owned.external_line_item_id = signal.external_line_item_id
                    WHERE owned.id = latest.line_item_id AND processing.status = 'PROCESSED'
                    ORDER BY signal.occurred_at DESC,
                             signal.provider_deduplication_key DESC, signal.id DESC
                    LIMIT 1
                ) watermark ON TRUE
                WHERE latest.tenant_id = ? AND latest.connection_id = ?
                  AND latest.line_item_id = ? AND latest.snapshot_kind = 'LATEST'
                """, anchorId, Timestamp.from(now), Timestamp.from(now),
                tenantId.value(), connectionId.value(), lineItemId);
        if (inserted != 1) {
            throw new ReliabilityOperationException(
                    OperationalErrorCode.INSUFFICIENT_RETAINED_EVIDENCE);
        }
        jdbcTemplate.update("""
                INSERT INTO line_item_watch_replay_anchor_deal (
                    tenant_id, connection_id, anchor_id, external_deal_id
                )
                SELECT tenant_id, connection_id, ?, external_deal_id
                FROM line_item_watch_snapshot_deal
                WHERE tenant_id = ? AND connection_id = ? AND line_item_id = ?
                  AND snapshot_kind = 'LATEST'
                """, anchorId, tenantId.value(), connectionId.value(), lineItemId);
        Long verified = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM line_item_watch_replay_anchor anchor
                JOIN line_item_watch_snapshot latest
                  ON latest.line_item_id = anchor.line_item_id
                 AND latest.snapshot_kind = 'LATEST'
                WHERE anchor.id = ? AND anchor.tenant_id = ? AND anchor.connection_id = ?
                  AND anchor.line_item_id = ? AND anchor.trusted
                  AND anchor.verified_at IS NOT NULL
                  AND ROW(
                      anchor.lifecycle_state, anchor.name, anchor.quantity,
                      anchor.unit_price, anchor.unit_discount, anchor.discount_percentage,
                      anchor.billing_frequency, anchor.billing_start_date,
                      anchor.billing_start_delay_unit, anchor.billing_start_delay_count,
                      anchor.recurring_billing_period, anchor.known_properties,
                      anchor.deal_set_complete, anchor.provider_created_at,
                      anchor.provider_updated_at, anchor.observed_at, anchor.deleted_at,
                      anchor.history_coverage_mode, anchor.history_observed_from
                  ) IS NOT DISTINCT FROM ROW(
                      CASE WHEN latest.deleted_at IS NULL THEN 'PRESENT' ELSE 'DELETED' END,
                      latest.name, latest.quantity, latest.unit_price,
                      latest.unit_discount, latest.discount_percentage,
                      latest.billing_frequency, latest.billing_start_date,
                      latest.billing_start_delay_unit, latest.billing_start_delay_count,
                      latest.recurring_billing_period, latest.known_properties,
                      latest.deal_set_complete, latest.provider_created_at,
                      latest.provider_updated_at, latest.observed_at, latest.deleted_at,
                      latest.history_coverage_mode, latest.history_observed_from
                  )
                  AND NOT EXISTS (
                      (SELECT external_deal_id FROM line_item_watch_snapshot_deal
                       WHERE line_item_id = latest.line_item_id AND snapshot_kind = 'LATEST')
                      EXCEPT
                      (SELECT external_deal_id FROM line_item_watch_replay_anchor_deal
                       WHERE anchor_id = ?)
                  )
                  AND NOT EXISTS (
                      (SELECT external_deal_id FROM line_item_watch_replay_anchor_deal
                       WHERE anchor_id = ?)
                      EXCEPT
                      (SELECT external_deal_id FROM line_item_watch_snapshot_deal
                       WHERE line_item_id = latest.line_item_id AND snapshot_kind = 'LATEST')
                  )
                """, Long.class, anchorId, tenantId.value(), connectionId.value(), lineItemId,
                anchorId, anchorId);
        if (verified == null || verified != 1) {
            throw new ReliabilityOperationException(OperationalErrorCode.REPLAY_FAILED);
        }
        return new AnchorResult(anchorId, true, true);
    }

    @Override
    @Transactional(readOnly = true)
    public Inspection inspect(TenantId tenantId, PlatformConnectionId connectionId) {
        requireConnection(tenantId, connectionId);
        return jdbcTemplate.queryForObject("""
                SELECT
                    (SELECT COUNT(*) FROM line_item_watch_reliability_operation
                     WHERE tenant_id = ? AND connection_id = ? AND status = 'PENDING') pending,
                    (SELECT COUNT(*) FROM line_item_watch_reliability_operation
                     WHERE tenant_id = ? AND connection_id = ? AND status = 'CLAIMED') active,
                    (SELECT COUNT(*) FROM line_item_watch_reliability_operation
                     WHERE tenant_id = ? AND connection_id = ? AND status = 'FAILED') failed,
                    (SELECT COUNT(*) FROM line_item_watch_reconciliation_finding
                     WHERE tenant_id = ? AND connection_id = ? AND status = 'OPEN') findings,
                    (SELECT COUNT(*) FROM line_item_watch_line_item_reliability
                     WHERE tenant_id = ? AND connection_id = ? AND possible_gap_since IS NOT NULL)
                        + (SELECT COUNT(*) FROM line_item_watch_reliability_state
                           WHERE tenant_id = ? AND connection_id = ?
                             AND coverage_state = 'POSSIBLE_GAP') gaps,
                    (SELECT COUNT(*) FROM line_item_watch_signal_processing
                     WHERE tenant_id = ? AND connection_id = ? AND status = 'FAILED') exhausted
                """, (row, ignored) -> new Inspection(
                        row.getLong("pending"), row.getLong("active"), row.getLong("failed"),
                        row.getLong("findings"), row.getLong("gaps"), row.getLong("exhausted")),
                tenantId.value(), connectionId.value(), tenantId.value(), connectionId.value(),
                tenantId.value(), connectionId.value(), tenantId.value(), connectionId.value(),
                tenantId.value(), connectionId.value(), tenantId.value(), connectionId.value(),
                tenantId.value(), connectionId.value());
    }

    @Override
    @Transactional(readOnly = true)
    public RetentionPreview previewRetention(
            TenantId tenantId,
            PlatformConnectionId connectionId,
            RetentionCutoffs cutoffs) {
        requireRetentionPolicy(cutoffs);
        requireConnection(tenantId, connectionId);
        return new RetentionPreview(
                countDeletableSignals(tenantId, connectionId, cutoffs.processedSignalsBefore()),
                count("line_item_watch_audit_event", tenantId, connectionId,
                        "occurred_at", cutoffs.semanticEventsBefore(), ""),
                count("application_activity_audit", tenantId, connectionId,
                        "occurred_at", cutoffs.activityBefore(), ""),
                count("line_item_watch_reliability_operation", tenantId, connectionId,
                        "completed_at", cutoffs.terminalOperationsBefore(),
                        "AND status IN ('SUCCEEDED', 'FAILED') AND NOT EXISTS ("
                        + "SELECT 1 FROM line_item_watch_reconciliation_finding f "
                        + "WHERE f.operation_id = line_item_watch_reliability_operation.id "
                        + "AND f.status = 'OPEN')"));
    }

    @Override
    @Transactional
    public RetentionResult executeRetention(
            TenantId tenantId,
            PlatformConnectionId connectionId,
            RetentionCutoffs cutoffs,
            boolean confirmed,
            int batchSize) {
        requireRetentionPolicy(cutoffs);
        requireConnection(tenantId, connectionId);
        if (!confirmed || batchSize < 1 || batchSize > 10_000) {
            throw new ReliabilityOperationException(
                    OperationalErrorCode.INVALID_RECOVERY_OPERATION);
        }
        DeletedSignals deletedSignals = deleteSignals(
                tenantId, connectionId, cutoffs.processedSignalsBefore(), batchSize);
        int semantic = deleteBatch("line_item_watch_audit_event", "occurred_at",
                tenantId, connectionId, cutoffs.semanticEventsBefore(), batchSize,
                "");
        int activity = deleteBatch("application_activity_audit", "occurred_at",
                tenantId, connectionId, cutoffs.activityBefore(), batchSize, "");
        int operations = deleteBatch("line_item_watch_reliability_operation", "completed_at",
                tenantId, connectionId, cutoffs.terminalOperationsBefore(), batchSize,
                "AND status IN ('SUCCEEDED', 'FAILED') AND NOT EXISTS ("
                        + "SELECT 1 FROM line_item_watch_reconciliation_finding f "
                        + "WHERE f.operation_id = line_item_watch_reliability_operation.id "
                        + "AND f.status = 'OPEN')");
        if (deletedSignals.count() > 0 && cutoffs.processedSignalsBefore() != null) {
            for (UUID lineItemId : deletedSignals.lineItemIds()) {
                jdbcTemplate.update("""
                    UPDATE line_item_watch_line_item_reliability item
                    SET retained_from = GREATEST(retained_from, ?),
                        retention_limited = TRUE, updated_at = CURRENT_TIMESTAMP
                    WHERE tenant_id = ? AND connection_id = ? AND line_item_id = ?
                    """, Timestamp.from(cutoffs.processedSignalsBefore()),
                    tenantId.value(), connectionId.value(), lineItemId);
            }
        }
        return new RetentionResult(
                deletedSignals.count(), semantic, activity, operations);
    }

    @Override
    @Transactional
    public boolean acknowledgeFinding(
            TenantId tenantId,
            PlatformConnectionId connectionId,
            UUID findingId,
            Instant now) {
        requireConnection(tenantId, connectionId);
        return jdbcTemplate.update("""
                UPDATE line_item_watch_reconciliation_finding
                SET status = 'ACKNOWLEDGED', resolved_at = ?
                WHERE id = ? AND tenant_id = ? AND connection_id = ? AND status = 'OPEN'
                """, Timestamp.from(now), findingId, tenantId.value(), connectionId.value()) == 1;
    }

    @Override
    @Transactional
    public boolean acknowledgeGap(
            TenantId tenantId,
            PlatformConnectionId connectionId,
            Instant now) {
        requireConnection(tenantId, connectionId);
        return jdbcTemplate.update("""
                UPDATE line_item_watch_reliability_state
                SET gap_acknowledged_at = ?, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND connection_id = ?
                  AND coverage_state = 'POSSIBLE_GAP'
                  AND reconciliation_outcome IN ('SUCCEEDED', 'DRIFT_REPAIRED')
                  AND gap_acknowledged_at IS NULL
                """, Timestamp.from(now), tenantId.value(), connectionId.value()) == 1;
    }

    private long countDeletableSignals(
            TenantId tenantId, PlatformConnectionId connectionId, Instant cutoff) {
        if (cutoff == null) {
            return 0;
        }
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM line_item_watch_change_signal signal
                JOIN line_item_watch_signal_processing processing
                  ON processing.signal_id = signal.id
                JOIN line_item_watch_line_item item
                  ON item.tenant_id = signal.tenant_id
                 AND item.connection_id = signal.connection_id
                 AND item.external_line_item_id = signal.external_line_item_id
                WHERE signal.tenant_id = ? AND signal.connection_id = ?
                  AND processing.status = 'PROCESSED' AND processing.processed_at < ?
                  AND EXISTS (
                      SELECT 1 FROM line_item_watch_replay_anchor anchor
                      WHERE anchor.tenant_id = signal.tenant_id
                        AND anchor.connection_id = signal.connection_id
                        AND anchor.line_item_id = item.id
                        AND anchor.trusted AND anchor.verified_at IS NOT NULL
                        AND (anchor.evidence_through_occurred_at,
                             anchor.evidence_through_deduplication_key,
                             anchor.evidence_through_signal_id)
                            >= (signal.occurred_at,
                                signal.provider_deduplication_key, signal.id)
                  )
                """, Long.class, tenantId.value(), connectionId.value(), Timestamp.from(cutoff));
    }

    private DeletedSignals deleteSignals(
            TenantId tenantId, PlatformConnectionId connectionId, Instant cutoff, int limit) {
        if (cutoff == null) {
            return new DeletedSignals(0, Set.of());
        }
        List<UUID> affected = jdbcTemplate.queryForList("""
                WITH candidate AS (
                    SELECT candidate.id AS signal_id, item.id AS line_item_id
                    FROM line_item_watch_change_signal candidate
                    JOIN line_item_watch_signal_processing processing
                      ON processing.signal_id = candidate.id
                    JOIN line_item_watch_line_item item
                      ON item.tenant_id = candidate.tenant_id
                     AND item.connection_id = candidate.connection_id
                     AND item.external_line_item_id = candidate.external_line_item_id
                    WHERE candidate.tenant_id = ? AND candidate.connection_id = ?
                      AND processing.status = 'PROCESSED' AND processing.processed_at < ?
                      AND EXISTS (
                          SELECT 1 FROM line_item_watch_replay_anchor anchor
                          WHERE anchor.tenant_id = candidate.tenant_id
                            AND anchor.connection_id = candidate.connection_id
                            AND anchor.line_item_id = item.id
                            AND anchor.trusted AND anchor.verified_at IS NOT NULL
                            AND (anchor.evidence_through_occurred_at,
                                 anchor.evidence_through_deduplication_key,
                                 anchor.evidence_through_signal_id)
                                >= (candidate.occurred_at,
                                    candidate.provider_deduplication_key, candidate.id)
                      )
                    ORDER BY candidate.occurred_at, candidate.id
                    LIMIT ?
                ), deleted AS (
                    DELETE FROM line_item_watch_change_signal signal
                    USING candidate
                    WHERE signal.id = candidate.signal_id
                    RETURNING candidate.line_item_id
                )
                SELECT line_item_id FROM deleted
                """, UUID.class, tenantId.value(), connectionId.value(),
                Timestamp.from(cutoff), limit);
        return new DeletedSignals(
                affected.size(), affected.stream().collect(Collectors.toUnmodifiableSet()));
    }

    private long count(
            String table,
            TenantId tenantId,
            PlatformConnectionId connectionId,
            String timeColumn,
            Instant cutoff,
            String predicate) {
        if (cutoff == null) {
            return 0;
        }
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table
                        + " WHERE tenant_id = ? AND connection_id = ? AND "
                        + timeColumn + " < ? " + predicate,
                Long.class, tenantId.value(), connectionId.value(), Timestamp.from(cutoff));
    }

    private int deleteBatch(
            String table,
            String timeColumn,
            TenantId tenantId,
            PlatformConnectionId connectionId,
            Instant cutoff,
            int limit,
            String predicate) {
        if (cutoff == null) {
            return 0;
        }
        return jdbcTemplate.update(
                "DELETE FROM " + table + " WHERE id IN (SELECT id FROM " + table
                        + " WHERE tenant_id = ? AND connection_id = ? AND " + timeColumn
                        + " < ? " + predicate + " ORDER BY " + timeColumn
                        + ", id LIMIT ?)",
                tenantId.value(), connectionId.value(), Timestamp.from(cutoff), limit);
    }

    private void requireConnection(TenantId tenantId, PlatformConnectionId connectionId) {
        Long found = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM platform_connection WHERE tenant_id = ? AND id = ?
                """, Long.class, tenantId.value(), connectionId.value());
        if (found == null || found != 1) {
            throw new ReliabilityOperationException(
                    OperationalErrorCode.INVALID_RECOVERY_OPERATION);
        }
    }

    private static void requireRetentionPolicy(RetentionCutoffs cutoffs) {
        if (cutoffs == null || cutoffs.empty()) {
            throw new ReliabilityOperationException(
                    OperationalErrorCode.RETENTION_FAILED);
        }
    }

    private static UUID zero(UUID value) {
        return value == null ? new UUID(0, 0) : value;
    }

    private record DeletedSignals(int count, Set<UUID> lineItemIds) {
    }
}
