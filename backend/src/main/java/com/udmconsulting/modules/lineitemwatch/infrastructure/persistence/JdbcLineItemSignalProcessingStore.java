package com.udmconsulting.modules.lineitemwatch.infrastructure.persistence;

import com.udmconsulting.modules.lineitemwatch.application.ClaimedLineItemSignal;
import com.udmconsulting.modules.lineitemwatch.application.LineItemSignalProcessingStore;
import com.udmconsulting.modules.lineitemwatch.application.SignalProcessingException;
import com.udmconsulting.modules.lineitemwatch.domain.LineItemProjection;
import com.udmconsulting.modules.lineitemwatch.domain.ProviderObjectId;
import com.udmconsulting.modules.lineitemwatch.infrastructure.persistence.JdbcLineItemProjectionRepository.LockedLineItem;
import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.supportability.OperationalErrorCode;
import com.udmconsulting.platform.tenant.domain.TenantId;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcLineItemSignalProcessingStore implements LineItemSignalProcessingStore {

    private final JdbcTemplate jdbcTemplate;
    private final JdbcLineItemProjectionRepository projectionRepository;

    public JdbcLineItemSignalProcessingStore(
            JdbcTemplate jdbcTemplate,
            JdbcLineItemProjectionRepository projectionRepository) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
        this.projectionRepository = Objects.requireNonNull(projectionRepository);
    }

    @Override
    @Transactional
    public Optional<ClaimedLineItemSignal> claimNext(
            Instant now, Duration leaseDuration, int maxAttempts) {
        jdbcTemplate.update("""
                UPDATE line_item_watch_signal_processing
                SET status = 'FAILED', failed_at = ?, last_error_code = ?,
                    claim_token = NULL, claimed_at = NULL, lease_until = NULL,
                    updated_at = ?
                WHERE attempt_count >= ?
                  AND (
                      (status = 'PENDING' AND next_attempt_at <= ?)
                      OR (status = 'CLAIMED' AND lease_until <= ?)
                  )
                """,
                Timestamp.from(now), OperationalErrorCode.RETRY_EXHAUSTED.name(),
                Timestamp.from(now), maxAttempts,
                Timestamp.from(now), Timestamp.from(now));

        UUID claimToken = UUID.randomUUID();
        List<ClaimedLineItemSignal> claimed = jdbcTemplate.query("""
                WITH candidate AS (
                    SELECT p.signal_id, s.received_at, (p.status = 'CLAIMED') AS reclaimed
                    FROM line_item_watch_signal_processing p
                    JOIN line_item_watch_change_signal s ON s.id = p.signal_id
                    WHERE p.attempt_count < ?
                      AND (
                          (p.status = 'PENDING' AND p.next_attempt_at <= ?)
                          OR (p.status = 'CLAIMED' AND p.lease_until <= ?)
                      )
                      AND NOT EXISTS (
                          SELECT 1
                          FROM line_item_watch_signal_processing blocked
                          JOIN line_item_watch_change_signal failed_signal
                            ON failed_signal.id = blocked.signal_id
                          WHERE blocked.status = 'FAILED'
                            AND failed_signal.tenant_id = s.tenant_id
                            AND failed_signal.connection_id = s.connection_id
                            AND failed_signal.external_line_item_id = s.external_line_item_id
                            AND failed_signal.occurred_at <= s.occurred_at
                      )
                      AND NOT EXISTS (
                          SELECT 1
                          FROM line_item_watch_signal_processing earlier
                          JOIN line_item_watch_change_signal earlier_signal
                            ON earlier_signal.id = earlier.signal_id
                          WHERE earlier.signal_id <> p.signal_id
                            AND earlier.status <> 'PROCESSED'
                            AND earlier_signal.tenant_id = s.tenant_id
                            AND earlier_signal.connection_id = s.connection_id
                            AND earlier_signal.external_line_item_id = s.external_line_item_id
                            AND (
                                earlier.status = 'CLAIMED' AND earlier.lease_until > ?
                                OR (earlier_signal.occurred_at,
                                    earlier_signal.provider_deduplication_key,
                                    earlier.signal_id)
                                   < (s.occurred_at,
                                      s.provider_deduplication_key,
                                      p.signal_id)
                            )
                      )
                    ORDER BY s.occurred_at, s.provider_deduplication_key, p.signal_id
                    FOR UPDATE OF p SKIP LOCKED
                    LIMIT 1
                )
                UPDATE line_item_watch_signal_processing p
                SET status = 'CLAIMED',
                    attempt_count = p.attempt_count + 1,
                    claim_token = ?,
                    claimed_at = ?,
                    lease_until = ?,
                    processed_at = NULL,
                    failed_at = NULL,
                    updated_at = ?
                FROM candidate
                WHERE p.signal_id = candidate.signal_id
                RETURNING p.signal_id, p.tenant_id, p.connection_id, p.claim_token,
                          p.attempt_count, candidate.received_at, candidate.reclaimed
                """,
                (row, ignored) -> new ClaimedLineItemSignal(
                        row.getObject("signal_id", UUID.class),
                        new TenantId(row.getObject("tenant_id", UUID.class)),
                        new PlatformConnectionId(row.getObject("connection_id", UUID.class)),
                        row.getObject("claim_token", UUID.class),
                        row.getInt("attempt_count"),
                        row.getTimestamp("received_at").toInstant(),
                        row.getBoolean("reclaimed")),
                maxAttempts,
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now),
                claimToken,
                Timestamp.from(now),
                Timestamp.from(now.plus(leaseDuration)),
                Timestamp.from(now));
        return claimed.stream().findFirst();
    }

    @Override
    @Transactional
    public ProcessingResult process(ClaimedLineItemSignal claim, Instant processedAt) {
        ClaimTarget target = jdbcTemplate.query("""
                SELECT s.external_line_item_id
                FROM line_item_watch_signal_processing p
                JOIN line_item_watch_change_signal s ON s.id = p.signal_id
                WHERE p.signal_id = ? AND p.tenant_id = ? AND p.connection_id = ?
                  AND p.status = 'CLAIMED' AND p.claim_token = ?
                FOR UPDATE OF p
                """,
                (row, ignored) -> new ClaimTarget(
                        new ProviderObjectId(row.getString("external_line_item_id"))),
                claim.signalId(),
                claim.tenantId().value(),
                claim.connectionId().value(),
                claim.claimToken()).stream().findFirst().orElseThrow(() ->
                        new SignalProcessingException(OperationalErrorCode.STALE_CLAIM, null));

        LockedLineItem lineItem = projectionRepository.ensureAndLock(
                claim.tenantId(), claim.connectionId(), target.lineItemId());
        LineItemProjection projection;
        try {
            projection = projectionRepository.rebuildLocked(
                    claim.tenantId(), claim.connectionId(), lineItem, claim.signalId());
        } catch (IllegalArgumentException exception) {
            throw new SignalProcessingException(
                    OperationalErrorCode.INVALID_SIGNAL_VALUE, exception);
        }

        int completed = jdbcTemplate.update("""
                UPDATE line_item_watch_signal_processing
                SET status = 'PROCESSED', processed_at = ?, last_error_code = NULL,
                    claim_token = NULL, claimed_at = NULL, lease_until = NULL,
                    updated_at = ?
                WHERE signal_id = ? AND tenant_id = ? AND connection_id = ?
                  AND status = 'CLAIMED' AND claim_token = ?
                """, Timestamp.from(processedAt), Timestamp.from(processedAt),
                claim.signalId(), claim.tenantId().value(), claim.connectionId().value(),
                claim.claimToken());
        if (completed != 1) {
            throw new SignalProcessingException(OperationalErrorCode.STALE_CLAIM, null);
        }
        boolean sparse = projection.properties().values().stream()
                .anyMatch(value -> value.state()
                        == com.udmconsulting.modules.lineitemwatch.domain.ObservedValue.State.UNKNOWN)
                || !projection.dealSetComplete();
        return new ProcessingResult(
                projection.auditEvents().size(), projection.deletedAt() != null, sparse);
    }

    @Override
    @Transactional
    public FailureResult recordFailure(
            ClaimedLineItemSignal claim,
            OperationalErrorCode errorCode,
            boolean retryable,
            Instant failedAt,
            Duration retryDelay,
            int maxAttempts) {
        boolean terminal = !retryable || claim.attempt() >= maxAttempts;
        int updated;
        if (terminal) {
            updated = jdbcTemplate.update("""
                    UPDATE line_item_watch_signal_processing
                    SET status = 'FAILED', failed_at = ?, last_error_code = ?,
                        claim_token = NULL, claimed_at = NULL, lease_until = NULL,
                        updated_at = ?
                    WHERE signal_id = ? AND tenant_id = ? AND connection_id = ?
                      AND status = 'CLAIMED' AND claim_token = ?
                    """, Timestamp.from(failedAt), errorCode.name(), Timestamp.from(failedAt),
                    claim.signalId(), claim.tenantId().value(), claim.connectionId().value(),
                    claim.claimToken());
        } else {
            updated = jdbcTemplate.update("""
                    UPDATE line_item_watch_signal_processing
                    SET status = 'PENDING', next_attempt_at = ?, last_error_code = ?,
                        claim_token = NULL, claimed_at = NULL, lease_until = NULL,
                        updated_at = ?
                    WHERE signal_id = ? AND tenant_id = ? AND connection_id = ?
                      AND status = 'CLAIMED' AND claim_token = ?
                    """, Timestamp.from(failedAt.plus(retryDelay)), errorCode.name(),
                    Timestamp.from(failedAt), claim.signalId(), claim.tenantId().value(),
                    claim.connectionId().value(), claim.claimToken());
        }
        return new FailureResult(updated == 1, terminal && updated == 1);
    }

    @Override
    @Transactional(readOnly = true)
    public ProcessingStatistics statistics(Instant now) {
        return jdbcTemplate.queryForObject("""
                SELECT
                    COUNT(*) FILTER (WHERE p.status = 'PENDING'
                        OR (p.status = 'CLAIMED' AND p.lease_until <= ?)) AS backlog,
                    COUNT(*) FILTER (WHERE p.status = 'CLAIMED' AND p.lease_until > ?) AS claimed,
                    COUNT(*) FILTER (WHERE p.status = 'FAILED') AS failed,
                    MIN(s.received_at) FILTER (WHERE p.status IN ('PENDING', 'CLAIMED')) AS oldest
                FROM line_item_watch_signal_processing p
                JOIN line_item_watch_change_signal s ON s.id = p.signal_id
                """, (row, ignored) -> {
                    Timestamp oldest = row.getTimestamp("oldest");
                    return new ProcessingStatistics(
                            row.getLong("backlog"),
                            row.getLong("claimed"),
                            row.getLong("failed"),
                            oldest == null ? null : oldest.toInstant());
                }, Timestamp.from(now), Timestamp.from(now));
    }

    private record ClaimTarget(ProviderObjectId lineItemId) {
    }
}
