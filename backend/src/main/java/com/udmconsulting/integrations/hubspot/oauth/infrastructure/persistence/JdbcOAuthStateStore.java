package com.udmconsulting.integrations.hubspot.oauth.infrastructure.persistence;

import com.udmconsulting.integrations.hubspot.oauth.application.OAuthStateStore;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcOAuthStateStore implements OAuthStateStore {

    static final int CLEANUP_BATCH_SIZE = 100;

    private final JdbcTemplate jdbcTemplate;

    public JdbcOAuthStateStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void store(byte[] stateHash, UUID correlationId, Instant createdAt, Instant expiresAt) {
        jdbcTemplate.update("""
                INSERT INTO oauth_install_state
                    (state_hash, correlation_id, created_at, expires_at)
                VALUES (?, ?, ?, ?)
                """, stateHash, correlationId, Timestamp.from(createdAt), Timestamp.from(expiresAt));
    }

    @Override
    @Transactional
    public Consumption consume(byte[] stateHash, Instant consumedAt) {
        UUID correlationId = jdbcTemplate.query("""
                UPDATE oauth_install_state
                SET consumed_at = ?
                WHERE state_hash = ? AND consumed_at IS NULL AND expires_at > ?
                RETURNING correlation_id
                """, resultSet -> resultSet.next()
                        ? resultSet.getObject("correlation_id", UUID.class) : null,
                Timestamp.from(consumedAt), stateHash, Timestamp.from(consumedAt));
        if (correlationId != null) {
            return new Consumption(ConsumptionResult.CONSUMED, correlationId);
        }
        return jdbcTemplate.query("""
                        SELECT correlation_id, expires_at, consumed_at
                        FROM oauth_install_state
                        WHERE state_hash = ?
                        """, resultSet -> {
                    if (!resultSet.next()) {
                        return new Consumption(ConsumptionResult.INVALID, null);
                    }
                    UUID existingCorrelationId = resultSet.getObject("correlation_id", UUID.class);
                    if (resultSet.getObject("consumed_at") != null) {
                        return new Consumption(ConsumptionResult.REPLAYED, existingCorrelationId);
                    }
                    return new Consumption(ConsumptionResult.EXPIRED, existingCorrelationId);
                }, stateHash);
    }

    @Override
    @Transactional
    public void deleteRetainedBefore(Instant retentionThreshold) {
        jdbcTemplate.update("""
                WITH retained AS (
                    SELECT state_hash
                    FROM oauth_install_state
                    WHERE COALESCE(consumed_at, expires_at) < ?
                    ORDER BY COALESCE(consumed_at, expires_at), state_hash
                    LIMIT ?
                )
                DELETE FROM oauth_install_state target
                USING retained
                WHERE target.state_hash = retained.state_hash
                """, Timestamp.from(retentionThreshold), CLEANUP_BATCH_SIZE);
    }
}
