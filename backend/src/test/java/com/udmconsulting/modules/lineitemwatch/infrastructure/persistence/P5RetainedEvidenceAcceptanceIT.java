package com.udmconsulting.modules.lineitemwatch.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.udmconsulting.modules.lineitemwatch.application.LineItemSignalProcessingStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "line-item-watch.processing.enabled=false")
@ActiveProfiles("local")
@Transactional
class P5RetainedEvidenceAcceptanceIT {

    @Autowired LineItemSignalProcessingStore processingStore;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void reconstructsAndReplaysRetainedSignalFirstEvidenceWithoutProviderAccess() {
        assumeTrue("true".equals(System.getenv("P5_RETAINED_EVIDENCE_CONFIRM")));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(DISTINCT external_line_item_id)
                FROM line_item_watch_change_signal
                """, Long.class)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM line_item_watch_change_signal signal
                JOIN line_item_watch_line_item item
                  ON item.tenant_id = signal.tenant_id
                 AND item.connection_id = signal.connection_id
                 AND item.external_line_item_id = signal.external_line_item_id
                """, Long.class)).isZero();
        assertThat(jdbcTemplate.queryForList("""
                SELECT DISTINCT signal_type
                FROM line_item_watch_change_signal
                """, String.class)).containsExactlyInAnyOrder(
                        "CREATED", "PROPERTY_CHANGED", "ASSOCIATION_CHANGED", "DELETED");
        assertThat(jdbcTemplate.queryForList("""
                SELECT DISTINCT association_action
                FROM line_item_watch_change_signal
                WHERE association_action IS NOT NULL
                """, String.class)).containsExactlyInAnyOrder("ADDED", "REMOVED");

        Scope scope = scope();
        processAll();
        ProjectionEvidence first = evidence(scope);
        assertThat(first.eventTypes()).containsExactly(
                "CREATED", "PROPERTY_CHANGED", "DEAL_ASSOCIATED",
                "PROPERTY_CHANGED", "DELETED");
        assertThat(first.unknownPropertyPredecessors()).isEqualTo(2);
        assertThat(first.associationSources()).isEqualTo(2);
        assertThat(first.disassociationEvents()).isZero();
        assertThat(first.latestDeals()).isEqualTo(1);
        assertThat(first.latestDeleted()).isTrue();
        assertThat(first.dealSetComplete()).isFalse();
        assertThat(first.knownProperties()).containsExactlyInAnyOrder("name", "quantity");

        jdbcTemplate.update("""
                UPDATE line_item_watch_signal_processing
                SET status = 'PENDING', attempt_count = 0,
                    next_attempt_at = CURRENT_TIMESTAMP,
                    claim_token = NULL, claimed_at = NULL, lease_until = NULL,
                    processed_at = NULL, failed_at = NULL, last_error_code = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE signal_id IN (
                    SELECT id FROM line_item_watch_change_signal
                    WHERE tenant_id = ? AND connection_id = ? AND external_line_item_id = ?
                )
                """, scope.tenantId(), scope.connectionId(), scope.lineItemId());
        processAll();
        ProjectionEvidence replay = evidence(scope);
        assertThat(replay).usingRecursiveComparison().isEqualTo(first);
    }

    private void processAll() {
        Instant now = Instant.now().plusSeconds(1);
        int count = 0;
        while (true) {
            var claim = processingStore.claimNext(now, Duration.ofMinutes(2), 8);
            if (claim.isEmpty()) {
                break;
            }
            processingStore.process(claim.orElseThrow(), now);
            if (++count > 100) {
                throw new IllegalStateException("retained-evidence processing did not converge");
            }
        }
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM line_item_watch_signal_processing
                WHERE status <> 'PROCESSED'
                """, Long.class)).isZero();
    }

    private Scope scope() {
        return jdbcTemplate.queryForObject("""
                SELECT DISTINCT tenant_id, connection_id, external_line_item_id
                FROM line_item_watch_change_signal
                """, (row, ignored) -> new Scope(
                row.getObject("tenant_id", UUID.class),
                row.getObject("connection_id", UUID.class),
                row.getString("external_line_item_id")));
    }

    private ProjectionEvidence evidence(Scope scope) {
        List<String> semanticKeys = jdbcTemplate.queryForList("""
                SELECT encode(event.semantic_key, 'hex')
                FROM line_item_watch_audit_event event
                JOIN line_item_watch_line_item item ON item.id = event.line_item_id
                WHERE event.tenant_id = ? AND event.connection_id = ?
                  AND item.external_line_item_id = ?
                ORDER BY event.occurred_at, event.event_type, encode(event.semantic_key, 'hex')
                """, String.class, scope.tenantId(), scope.connectionId(), scope.lineItemId());
        List<String> eventTypes = jdbcTemplate.queryForList("""
                SELECT event.event_type
                FROM line_item_watch_audit_event event
                JOIN line_item_watch_line_item item ON item.id = event.line_item_id
                WHERE event.tenant_id = ? AND event.connection_id = ?
                  AND item.external_line_item_id = ?
                ORDER BY event.occurred_at, event.event_type, encode(event.semantic_key, 'hex')
                """, String.class, scope.tenantId(), scope.connectionId(), scope.lineItemId());
        Integer unknown = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM line_item_watch_audit_event event
                JOIN line_item_watch_line_item item ON item.id = event.line_item_id
                WHERE event.tenant_id = ? AND event.connection_id = ?
                  AND item.external_line_item_id = ?
                  AND event.event_type = 'PROPERTY_CHANGED' AND event.before_state = 'UNKNOWN'
                """, Integer.class, scope.tenantId(), scope.connectionId(), scope.lineItemId());
        Integer associationSources = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM line_item_watch_audit_event_source source
                JOIN line_item_watch_audit_event event ON event.id = source.audit_event_id
                JOIN line_item_watch_line_item item ON item.id = event.line_item_id
                WHERE event.tenant_id = ? AND event.connection_id = ?
                  AND item.external_line_item_id = ?
                  AND event.event_type = 'DEAL_ASSOCIATED'
                """, Integer.class, scope.tenantId(), scope.connectionId(), scope.lineItemId());
        Integer disassociationEvents = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM line_item_watch_audit_event event
                JOIN line_item_watch_line_item item ON item.id = event.line_item_id
                WHERE event.tenant_id = ? AND event.connection_id = ?
                  AND item.external_line_item_id = ?
                  AND event.event_type = 'DEAL_DISASSOCIATED'
                """, Integer.class, scope.tenantId(), scope.connectionId(), scope.lineItemId());
        Integer latestDeals = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM line_item_watch_snapshot_deal deal
                JOIN line_item_watch_line_item item ON item.id = deal.line_item_id
                WHERE deal.tenant_id = ? AND deal.connection_id = ?
                  AND item.external_line_item_id = ? AND deal.snapshot_kind = 'LATEST'
                """, Integer.class, scope.tenantId(), scope.connectionId(), scope.lineItemId());
        Boolean latestDeleted = jdbcTemplate.queryForObject("""
                SELECT snapshot.deleted_at IS NOT NULL FROM line_item_watch_snapshot snapshot
                JOIN line_item_watch_line_item item ON item.id = snapshot.line_item_id
                WHERE snapshot.tenant_id = ? AND snapshot.connection_id = ?
                  AND item.external_line_item_id = ? AND snapshot.snapshot_kind = 'LATEST'
                """, Boolean.class, scope.tenantId(), scope.connectionId(), scope.lineItemId());
        Boolean dealSetComplete = jdbcTemplate.queryForObject("""
                SELECT snapshot.deal_set_complete FROM line_item_watch_snapshot snapshot
                JOIN line_item_watch_line_item item ON item.id = snapshot.line_item_id
                WHERE snapshot.tenant_id = ? AND snapshot.connection_id = ?
                  AND item.external_line_item_id = ? AND snapshot.snapshot_kind = 'LATEST'
                """, Boolean.class, scope.tenantId(), scope.connectionId(), scope.lineItemId());
        List<String> knownProperties = jdbcTemplate.queryForList("""
                SELECT unnest(snapshot.known_properties) FROM line_item_watch_snapshot snapshot
                JOIN line_item_watch_line_item item ON item.id = snapshot.line_item_id
                WHERE snapshot.tenant_id = ? AND snapshot.connection_id = ?
                  AND item.external_line_item_id = ? AND snapshot.snapshot_kind = 'LATEST'
                """, String.class, scope.tenantId(), scope.connectionId(), scope.lineItemId());
        return new ProjectionEvidence(
                semanticKeys,
                eventTypes,
                unknown == null ? 0 : unknown,
                associationSources == null ? 0 : associationSources,
                disassociationEvents == null ? 0 : disassociationEvents,
                latestDeals == null ? 0 : latestDeals,
                Boolean.TRUE.equals(latestDeleted),
                Boolean.TRUE.equals(dealSetComplete),
                knownProperties);
    }

    private record Scope(UUID tenantId, UUID connectionId, String lineItemId) {
    }

    private record ProjectionEvidence(
            List<String> semanticKeys,
            List<String> eventTypes,
            int unknownPropertyPredecessors,
            int associationSources,
            int disassociationEvents,
            int latestDeals,
            boolean latestDeleted,
            boolean dealSetComplete,
            List<String> knownProperties) {
    }
}
