package com.udmconsulting.platform.credential.infrastructure.persistence;

import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.credential.application.ConnectionCredentialStore;
import com.udmconsulting.platform.credential.domain.ConnectionCredential;
import com.udmconsulting.platform.credential.domain.EncryptedSecret;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcConnectionCredentialStore implements ConnectionCredentialStore {

    private final JdbcTemplate jdbcTemplate;

    public JdbcConnectionCredentialStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
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
            PlatformConnectionId connectionId, long expectedGeneration) {
        return destructiveTransition(connectionId, expectedGeneration, "REAUTH_REQUIRED");
    }

    @Override
    @Transactional
    public boolean disconnectIfGeneration(PlatformConnectionId connectionId, long expectedGeneration) {
        return destructiveTransition(connectionId, expectedGeneration, "DISCONNECTED");
    }

    private boolean destructiveTransition(
            PlatformConnectionId connectionId, long expectedGeneration, String status) {
        int advanced = jdbcTemplate.update("""
                UPDATE platform_connection p
                SET credential_generation = credential_generation + 1,
                    status = ?,
                    status_changed_at = CURRENT_TIMESTAMP
                WHERE p.id = ?
                  AND p.credential_generation = ?
                  AND EXISTS (
                      SELECT 1 FROM connection_credential c
                      WHERE c.connection_id = p.id
                        AND c.credential_generation = ?
                  )
                """, status, connectionId.value(), expectedGeneration, expectedGeneration);
        if (advanced == 0) {
            return false;
        }
        int deleted = jdbcTemplate.update("""
                DELETE FROM connection_credential
                WHERE connection_id = ? AND credential_generation = ?
                """, connectionId.value(), expectedGeneration);
        if (deleted != 1) {
            throw new IllegalStateException("Credential generation changed during lifecycle transition");
        }
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
}
