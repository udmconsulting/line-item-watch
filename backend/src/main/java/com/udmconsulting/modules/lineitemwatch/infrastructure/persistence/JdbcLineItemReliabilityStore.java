package com.udmconsulting.modules.lineitemwatch.infrastructure.persistence;

import com.udmconsulting.modules.lineitemwatch.application.LineItemReliability;
import com.udmconsulting.modules.lineitemwatch.application.LineItemReliability.CoverageState;
import com.udmconsulting.modules.lineitemwatch.application.LineItemReliability.IngestionState;
import com.udmconsulting.modules.lineitemwatch.application.LineItemReliability.ReconciliationOutcome;
import com.udmconsulting.modules.lineitemwatch.application.LineItemReliabilityStore;
import com.udmconsulting.platform.connection.domain.ConnectionStatus;
import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.tenant.domain.TenantId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcLineItemReliabilityStore implements LineItemReliabilityStore {

    private final JdbcTemplate jdbcTemplate;

    public JdbcLineItemReliabilityStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void connectionChanged(
            TenantId tenantId,
            PlatformConnectionId connectionId,
            ConnectionStatus previous,
            ConnectionStatus current,
            Instant occurredAt) {
        if (current == ConnectionStatus.ACTIVE) {
            startIfEligible(tenantId, connectionId, occurredAt,
                    previous == ConnectionStatus.REAUTH_REQUIRED
                            ? "REAUTHORIZED" : "ACTIVATED");
            return;
        }
        pause(tenantId, connectionId, occurredAt, current.name());
    }

    @Override
    public void entitlementChanged(TenantId tenantId, boolean enabled, Instant occurredAt) {
        List<UUID> connectionIds = jdbcTemplate.queryForList(
                "SELECT id FROM platform_connection WHERE tenant_id = ? ORDER BY id",
                UUID.class,
                tenantId.value());
        for (UUID connectionId : connectionIds) {
            PlatformConnectionId id = new PlatformConnectionId(connectionId);
            if (enabled) {
                startIfEligible(tenantId, id, occurredAt, "ENTITLEMENT_REACTIVATED");
            } else {
                pause(tenantId, id, occurredAt, "ENTITLEMENT_DEACTIVATED");
            }
        }
    }

    @Override
    public void signalObserved(
            TenantId tenantId, PlatformConnectionId connectionId, Instant observedAt) {
        ensureState(tenantId, connectionId);
        jdbcTemplate.update("""
                UPDATE line_item_watch_reliability_state
                SET last_signal_observed_at = GREATEST(
                        COALESCE(last_signal_observed_at, ?), ?),
                    updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND connection_id = ?
                """, Timestamp.from(observedAt), Timestamp.from(observedAt),
                tenantId.value(), connectionId.value());
    }

    @Override
    public void signalProcessed(
            TenantId tenantId, PlatformConnectionId connectionId, Instant processedAt) {
        ensureState(tenantId, connectionId);
        jdbcTemplate.update("""
                UPDATE line_item_watch_reliability_state
                SET last_successfully_processed_at = GREATEST(
                        COALESCE(last_successfully_processed_at, ?), ?),
                    updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND connection_id = ?
                """, Timestamp.from(processedAt), Timestamp.from(processedAt),
                tenantId.value(), connectionId.value());
    }

    @Override
    public void markPossibleGap(
            TenantId tenantId, PlatformConnectionId connectionId, Instant gapSince) {
        ensureState(tenantId, connectionId);
        jdbcTemplate.update("""
                UPDATE line_item_watch_reliability_state
                SET coverage_state = 'POSSIBLE_GAP',
                    possible_gap_since = LEAST(COALESCE(possible_gap_since, ?), ?),
                    gap_acknowledged_at = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND connection_id = ?
                """, Timestamp.from(gapSince), Timestamp.from(gapSince),
                tenantId.value(), connectionId.value());
    }

    @Override
    public void reconciled(
            TenantId tenantId,
            PlatformConnectionId connectionId,
            Instant reconciledAt,
            ReconciliationOutcome outcome) {
        ensureState(tenantId, connectionId);
        jdbcTemplate.update("""
                UPDATE line_item_watch_reliability_state
                SET last_reconciled_at = ?, reconciliation_outcome = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND connection_id = ?
                """, Timestamp.from(reconciledAt), outcome.name(),
                tenantId.value(), connectionId.value());
    }

    @Override
    public LineItemReliability read(TenantId tenantId, PlatformConnectionId connectionId) {
        return jdbcTemplate.query("""
                SELECT ingestion_state, coverage_state, possible_gap_since,
                       last_signal_observed_at, last_successfully_processed_at,
                       last_reconciled_at, reconciliation_outcome
                FROM line_item_watch_reliability_state
                WHERE tenant_id = ? AND connection_id = ?
                """, result -> {
                    if (!result.next()) {
                        return LineItemReliability.unknown();
                    }
                    return new LineItemReliability(
                            IngestionState.valueOf(result.getString("ingestion_state")),
                            CoverageState.valueOf(result.getString("coverage_state")),
                            instant(result.getTimestamp("possible_gap_since")),
                            instant(result.getTimestamp("last_signal_observed_at")),
                            instant(result.getTimestamp("last_successfully_processed_at")),
                            instant(result.getTimestamp("last_reconciled_at")),
                            ReconciliationOutcome.valueOf(
                                    result.getString("reconciliation_outcome")));
                }, tenantId.value(), connectionId.value());
    }

    private void startIfEligible(
            TenantId tenantId,
            PlatformConnectionId connectionId,
            Instant occurredAt,
            String reason) {
        Integer eligible = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM platform_connection connection
                JOIN tenant_entitlement entitlement
                  ON entitlement.tenant_id = connection.tenant_id
                 AND entitlement.product_module = 'LINE_ITEM_WATCH'
                WHERE connection.tenant_id = ? AND connection.id = ?
                  AND connection.status = 'ACTIVE'
                """, Integer.class, tenantId.value(), connectionId.value());
        if (eligible == null || eligible != 1) {
            return;
        }
        ensureState(tenantId, connectionId);
        jdbcTemplate.update("""
                UPDATE line_item_watch_reliability_state
                SET ingestion_state = 'OBSERVING', updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND connection_id = ?
                """, tenantId.value(), connectionId.value());
        jdbcTemplate.update("""
                INSERT INTO line_item_watch_observation_period (
                    id, tenant_id, connection_id, started_at, start_reason
                )
                SELECT ?, ?, ?, ?, ?
                WHERE NOT EXISTS (
                    SELECT 1 FROM line_item_watch_observation_period
                    WHERE tenant_id = ? AND connection_id = ? AND ended_at IS NULL
                )
                """, UUID.randomUUID(), tenantId.value(), connectionId.value(),
                Timestamp.from(occurredAt), reason,
                tenantId.value(), connectionId.value());
    }

    private void pause(
            TenantId tenantId,
            PlatformConnectionId connectionId,
            Instant occurredAt,
            String reason) {
        jdbcTemplate.update("""
                INSERT INTO line_item_watch_reliability_state (
                    tenant_id, connection_id, ingestion_state, coverage_state,
                    possible_gap_since
                ) VALUES (?, ?, 'PAUSED', 'POSSIBLE_GAP', ?)
                ON CONFLICT (tenant_id, connection_id) DO NOTHING
                """, tenantId.value(), connectionId.value(), Timestamp.from(occurredAt));
        jdbcTemplate.update("""
                UPDATE line_item_watch_reliability_state
                SET ingestion_state = 'PAUSED', coverage_state = 'POSSIBLE_GAP',
                    possible_gap_since = LEAST(COALESCE(possible_gap_since, ?), ?),
                    gap_acknowledged_at = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND connection_id = ?
                """, Timestamp.from(occurredAt), Timestamp.from(occurredAt),
                tenantId.value(), connectionId.value());
        jdbcTemplate.update("""
                UPDATE line_item_watch_observation_period
                SET ended_at = ?, end_reason = ?
                WHERE tenant_id = ? AND connection_id = ? AND ended_at IS NULL
                """, Timestamp.from(occurredAt), reason,
                tenantId.value(), connectionId.value());
    }

    private void ensureState(TenantId tenantId, PlatformConnectionId connectionId) {
        jdbcTemplate.update("""
                INSERT INTO line_item_watch_reliability_state (
                    tenant_id, connection_id, ingestion_state, coverage_state,
                    possible_gap_since
                )
                SELECT connection.tenant_id, connection.id,
                       CASE WHEN connection.status = 'ACTIVE'
                                  AND entitlement.tenant_id IS NOT NULL
                           THEN 'OBSERVING' ELSE 'PAUSED' END,
                       CASE WHEN connection.status = 'ACTIVE'
                                  AND entitlement.tenant_id IS NOT NULL
                           THEN 'NO_KNOWN_GAP' ELSE 'POSSIBLE_GAP' END,
                       CASE WHEN connection.status = 'ACTIVE'
                                  AND entitlement.tenant_id IS NOT NULL
                           THEN NULL ELSE connection.status_changed_at END
                FROM platform_connection connection
                LEFT JOIN tenant_entitlement entitlement
                  ON entitlement.tenant_id = connection.tenant_id
                 AND entitlement.product_module = 'LINE_ITEM_WATCH'
                WHERE connection.tenant_id = ? AND connection.id = ?
                ON CONFLICT (tenant_id, connection_id) DO NOTHING
                """, tenantId.value(), connectionId.value());
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
