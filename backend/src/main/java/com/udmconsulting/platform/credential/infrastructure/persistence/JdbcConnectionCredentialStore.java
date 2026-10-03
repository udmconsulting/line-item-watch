package com.udmconsulting.platform.credential.infrastructure.persistence;

import com.udmconsulting.platform.activity.application.ActivityContext;
import com.udmconsulting.platform.activity.application.ApplicationActivityAudit;
import com.udmconsulting.platform.activity.application.LifecycleTransitionObserver;
import com.udmconsulting.platform.activity.domain.ActivityAction;
import com.udmconsulting.platform.activity.domain.ActivityResourceType;
import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.connection.domain.ConnectionStatus;
import com.udmconsulting.platform.connection.domain.Provider;
import com.udmconsulting.platform.credential.application.ConnectionCredentialStore;
import com.udmconsulting.platform.credential.domain.ConnectionCredential;
import com.udmconsulting.platform.credential.domain.EncryptedSecret;
import com.udmconsulting.platform.tenant.domain.TenantId;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcConnectionCredentialStore implements ConnectionCredentialStore {

    private final JdbcTemplate jdbcTemplate;
    private final ApplicationActivityAudit activityAudit;
    private final List<LifecycleTransitionObserver> lifecycleObservers;
    private final Clock clock;

    public JdbcConnectionCredentialStore(
            JdbcTemplate jdbcTemplate,
            ApplicationActivityAudit activityAudit,
            List<LifecycleTransitionObserver> lifecycleObservers,
            Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.activityAudit = activityAudit;
        this.lifecycleObservers = List.copyOf(lifecycleObservers);
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ConnectionCredential> findByConnectionId(PlatformConnectionId connectionId) {
        return jdbcTemplate.query("""
                        SELECT c.cipher_version, c.key_id, c.nonce, c.ciphertext,
                               c.granted_scopes, c.credential_generation
                        FROM connection_credential c
                        JOIN platform_connection p ON p.id = c.connection_id
                        WHERE c.connection_id = ?
                          AND c.credential_generation = p.credential_generation
                        """, resultSet -> {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    String[] scopeArray = (String[]) resultSet.getArray("granted_scopes").getArray();
                    return Optional.of(new ConnectionCredential(
                            connectionId,
                            new EncryptedSecret(
                                    resultSet.getShort("cipher_version"),
                                    resultSet.getString("key_id"),
                                    resultSet.getBytes("nonce"),
                                    resultSet.getBytes("ciphertext")),
                            new LinkedHashSet<>(Arrays.asList(scopeArray)),
                            resultSet.getLong("credential_generation")));
                }, connectionId.value());
    }

    @Override
    @Transactional
    public boolean replaceIfGeneration(
            PlatformConnectionId connectionId,
            long expectedGeneration,
            EncryptedSecret replacement,
            Set<String> grantedScopes) {
        Long nextGeneration = advanceActiveGeneration(connectionId, expectedGeneration);
        if (nextGeneration == null) {
            return false;
        }
        int changed = jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    UPDATE connection_credential
                    SET cipher_version = ?, key_id = ?, nonce = ?, ciphertext = ?,
                        granted_scopes = ?, credential_generation = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE connection_id = ? AND credential_generation = ?
                    """);
            statement.setShort(1, replacement.cipherVersion());
            statement.setString(2, replacement.keyId());
            statement.setBytes(3, replacement.nonce());
            statement.setBytes(4, replacement.ciphertext());
            Array scopes = connection.createArrayOf("text", grantedScopes.stream().sorted().toArray(String[]::new));
            statement.setArray(5, scopes);
            statement.setLong(6, nextGeneration);
            statement.setObject(7, connectionId.value());
            statement.setLong(8, expectedGeneration);
            return statement;
        });
        if (changed != 1) {
            throw new IllegalStateException("Credential generation changed during replacement");
        }
        return true;
    }

    @Override
    @Transactional
    public boolean requireReauthenticationIfGeneration(
            PlatformConnectionId connectionId,
            long expectedGeneration,
            ActivityContext activityContext) {
        return destructiveTransition(
                connectionId,
                expectedGeneration,
                ConnectionStatus.REAUTH_REQUIRED,
                ActivityAction.PLATFORM_CONNECTION_REAUTHENTICATION_REQUIRED,
                activityContext);
    }

    @Override
    @Transactional
    public boolean disconnectIfGeneration(
            PlatformConnectionId connectionId,
            long expectedGeneration,
            ActivityContext activityContext) {
        return destructiveTransition(
                connectionId,
                expectedGeneration,
                ConnectionStatus.DISCONNECTED,
                ActivityAction.PLATFORM_CONNECTION_DISCONNECTED,
                activityContext);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CredentialRewrapCandidate> findForRewrap(
            TenantId tenantId, String sourceKeyId, int limit) {
        if (limit < 1 || limit > 1_000) {
            throw new IllegalArgumentException("rewrap limit must be between 1 and 1000");
        }
        return jdbcTemplate.query("""
                SELECT c.connection_id, p.provider, c.credential_generation,
                       c.cipher_version, c.key_id, c.nonce, c.ciphertext
                FROM connection_credential c
                JOIN platform_connection p ON p.id = c.connection_id
                WHERE p.tenant_id = ? AND c.key_id = ?
                  AND c.credential_generation = p.credential_generation
                ORDER BY c.connection_id
                LIMIT ?
                """, (row, ignored) -> new CredentialRewrapCandidate(
                        tenantId,
                        new PlatformConnectionId(row.getObject("connection_id", UUID.class)),
                        Provider.valueOf(row.getString("provider")),
                        row.getLong("credential_generation"),
                        new EncryptedSecret(
                                row.getShort("cipher_version"),
                                row.getString("key_id"),
                                row.getBytes("nonce"),
                                row.getBytes("ciphertext"))),
                tenantId.value(), sourceKeyId, limit);
    }

    @Override
    @Transactional
    public boolean rewrapIfUnchanged(
            CredentialRewrapCandidate candidate,
            EncryptedSecret replacement,
            ActivityContext activityContext) {
        EncryptedSecret current = candidate.encryptedSecret();
        int changed = jdbcTemplate.update("""
                UPDATE connection_credential c
                SET cipher_version = ?, key_id = ?, nonce = ?, ciphertext = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE c.connection_id = ?
                  AND c.credential_generation = ?
                  AND c.key_id = ?
                  AND c.nonce = ?
                  AND c.ciphertext = ?
                  AND EXISTS (
                      SELECT 1 FROM platform_connection p
                      WHERE p.id = c.connection_id AND p.tenant_id = ?
                        AND p.credential_generation = c.credential_generation
                  )
                """, replacement.cipherVersion(), replacement.keyId(),
                replacement.nonce(), replacement.ciphertext(),
                candidate.connectionId().value(), candidate.credentialGeneration(),
                current.keyId(), current.nonce(), current.ciphertext(),
                candidate.tenantId().value());
        if (changed == 0) {
            return false;
        }
        if (changed != 1) {
            throw new IllegalStateException("Credential rewrap changed an unexpected row count");
        }
        activityAudit.record(
                candidate.tenantId(),
                candidate.connectionId(),
                activityContext,
                ActivityAction.CREDENTIAL_KEY_REWRAPPED,
                ActivityResourceType.CREDENTIAL,
                candidate.connectionId().value().toString(),
                "PREVIOUS_KEY",
                "ACTIVE_KEY");
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public long countByKeyId(TenantId tenantId, String keyId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM connection_credential c
                JOIN platform_connection p ON p.id = c.connection_id
                WHERE p.tenant_id = ? AND c.key_id = ?
                  AND c.credential_generation = p.credential_generation
                """, Long.class, tenantId.value(), keyId);
        return count == null ? 0 : count;
    }

    private boolean destructiveTransition(
            PlatformConnectionId connectionId,
            long expectedGeneration,
            ConnectionStatus resultingStatus,
            ActivityAction action,
            ActivityContext activityContext) {
        LifecycleState current = jdbcTemplate.query("""
                        SELECT tenant_id, status, credential_generation
                        FROM platform_connection
                        WHERE id = ?
                        FOR UPDATE
                        """,
                resultSet -> resultSet.next()
                        ? new LifecycleState(
                                resultSet.getObject("tenant_id", UUID.class),
                                ConnectionStatus.valueOf(resultSet.getString("status")),
                                resultSet.getLong("credential_generation"))
                        : null,
                connectionId.value());
        if (current == null
                || current.credentialGeneration() != expectedGeneration
                || current.status() == resultingStatus) {
            return false;
        }
        Integer credentials = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM connection_credential
                WHERE connection_id = ? AND credential_generation = ?
                """, Integer.class, connectionId.value(), expectedGeneration);
        if (credentials == null || credentials != 1) {
            return false;
        }
        int advanced = jdbcTemplate.update("""
                UPDATE platform_connection
                SET credential_generation = credential_generation + 1,
                    status = ?,
                    status_changed_at = CURRENT_TIMESTAMP
                WHERE id = ? AND credential_generation = ?
                """, resultingStatus.name(), connectionId.value(), expectedGeneration);
        if (advanced != 1) {
            return false;
        }
        int deleted = jdbcTemplate.update("""
                DELETE FROM connection_credential
                WHERE connection_id = ? AND credential_generation = ?
                """, connectionId.value(), expectedGeneration);
        if (deleted != 1) {
            throw new IllegalStateException("Credential generation changed during lifecycle transition");
        }
        activityAudit.record(
                new com.udmconsulting.platform.tenant.domain.TenantId(current.tenantId()),
                connectionId,
                activityContext,
                action,
                ActivityResourceType.PLATFORM_CONNECTION,
                connectionId.value().toString(),
                current.status().name(),
                resultingStatus.name());
        lifecycleObservers.forEach(observer -> observer.connectionChanged(
                new com.udmconsulting.platform.tenant.domain.TenantId(current.tenantId()),
                connectionId,
                current.status(),
                resultingStatus,
                clock.instant()));
        return true;
    }

    private Long advanceActiveGeneration(
            PlatformConnectionId connectionId, long expectedGeneration) {
        return jdbcTemplate.query("""
                        UPDATE platform_connection p
                        SET credential_generation = credential_generation + 1
                        WHERE p.id = ?
                          AND p.status = 'ACTIVE'
                          AND p.credential_generation = ?
                          AND EXISTS (
                              SELECT 1 FROM connection_credential c
                              WHERE c.connection_id = p.id
                                AND c.credential_generation = ?
                          )
                        RETURNING credential_generation
                        """, resultSet -> resultSet.next() ? resultSet.getLong(1) : null,
                connectionId.value(), expectedGeneration, expectedGeneration);
    }

    private record LifecycleState(
            UUID tenantId, ConnectionStatus status, long credentialGeneration) {
    }
}
