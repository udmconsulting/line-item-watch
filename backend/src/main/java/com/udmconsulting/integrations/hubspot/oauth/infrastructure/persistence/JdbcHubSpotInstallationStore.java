package com.udmconsulting.integrations.hubspot.oauth.infrastructure.persistence;

import com.udmconsulting.integrations.hubspot.oauth.application.HubSpotInstallationStore;
import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.connection.domain.Provider;
import com.udmconsulting.platform.credential.application.SecretContext;
import com.udmconsulting.platform.credential.application.SecretProtector;
import com.udmconsulting.platform.credential.domain.ConnectionCredential;
import com.udmconsulting.platform.credential.domain.EncryptedSecret;
import com.udmconsulting.platform.tenant.domain.TenantId;
import java.sql.Array;
import java.sql.PreparedStatement;
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
public class JdbcHubSpotInstallationStore implements HubSpotInstallationStore {

    private final JdbcTemplate jdbcTemplate;
    private final SecretProtector secretProtector;

    public JdbcHubSpotInstallationStore(JdbcTemplate jdbcTemplate, SecretProtector secretProtector) {
        this.jdbcTemplate = jdbcTemplate;
        this.secretProtector = secretProtector;
    }

    @Override
    @Transactional
    public FinalizedInstallation finalizeInstallation(
            String externalAccountId, String refreshToken, Set<String> grantedScopes) {
        acquireAccountLock(externalAccountId);
        ConnectionIdentity identity = findConnection(externalAccountId)
                .orElseGet(() -> createConnection(externalAccountId));
        Optional<ConnectionCredential> prior = lockAndLoadCredential(identity.connectionId());
        long credentialGeneration = advanceGenerationAndActivate(identity.connectionId());
        EncryptedSecret encrypted = secretProtector.protect(
                refreshToken,
                new SecretContext(Provider.HUBSPOT, identity.connectionId()));
        upsertCredential(identity.connectionId(), encrypted, grantedScopes, credentialGeneration);
        jdbcTemplate.update("""
                INSERT INTO tenant_entitlement (tenant_id, product_module)
                VALUES (?, 'LINE_ITEM_WATCH')
                ON CONFLICT (tenant_id, product_module) DO NOTHING
                """, identity.tenantId().value());
        return new FinalizedInstallation(identity.tenantId(), identity.connectionId(), prior);
    }

    private void acquireAccountLock(String externalAccountId) {
        jdbcTemplate.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(CAST(? AS text), 0))",
                resultSet -> null,
                Provider.HUBSPOT.name() + ":" + externalAccountId);
    }

    private Optional<ConnectionIdentity> findConnection(String externalAccountId) {
        List<ConnectionIdentity> matches = jdbcTemplate.query("""
                SELECT id, tenant_id
                FROM platform_connection
                WHERE provider = 'HUBSPOT' AND external_account_id = ?
                FOR UPDATE
                """, (resultSet, rowNumber) -> new ConnectionIdentity(
                        new TenantId(resultSet.getObject("tenant_id", UUID.class)),
                        new PlatformConnectionId(resultSet.getObject("id", UUID.class))),
                externalAccountId);
        return matches.stream().findFirst();
    }

    private ConnectionIdentity createConnection(String externalAccountId) {
        TenantId tenantId = TenantId.newId();
        PlatformConnectionId connectionId = PlatformConnectionId.newId();
        jdbcTemplate.update("INSERT INTO tenant (id) VALUES (?)", tenantId.value());
        jdbcTemplate.update("""
                INSERT INTO platform_connection
                    (id, tenant_id, provider, external_account_id, status)
                VALUES (?, ?, 'HUBSPOT', ?, 'DISCONNECTED')
                """, connectionId.value(), tenantId.value(), externalAccountId);
        return new ConnectionIdentity(tenantId, connectionId);
    }

    private Optional<ConnectionCredential> lockAndLoadCredential(PlatformConnectionId connectionId) {
        return jdbcTemplate.query("""
                        SELECT cipher_version, key_id, nonce, ciphertext,
                               granted_scopes, credential_generation
                        FROM connection_credential
                        WHERE connection_id = ?
                        FOR UPDATE
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

    private long advanceGenerationAndActivate(PlatformConnectionId connectionId) {
        Long generation = jdbcTemplate.queryForObject("""
                UPDATE platform_connection
                SET credential_generation = credential_generation + 1,
                    status = 'ACTIVE',
                    status_changed_at = CURRENT_TIMESTAMP
                WHERE id = ?
                RETURNING credential_generation
                """, Long.class, connectionId.value());
        if (generation == null) {
            throw new IllegalStateException("Could not advance credential generation");
        }
        return generation;
    }

    private void upsertCredential(
            PlatformConnectionId connectionId,
            EncryptedSecret encrypted,
            Set<String> grantedScopes,
            long credentialGeneration) {
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO connection_credential
                        (connection_id, cipher_version, key_id, nonce, ciphertext,
                         granted_scopes, credential_generation)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (connection_id) DO UPDATE
                    SET cipher_version = EXCLUDED.cipher_version,
                        key_id = EXCLUDED.key_id,
                        nonce = EXCLUDED.nonce,
                        ciphertext = EXCLUDED.ciphertext,
                        granted_scopes = EXCLUDED.granted_scopes,
                        credential_generation = EXCLUDED.credential_generation,
                        updated_at = CURRENT_TIMESTAMP
                    """);
            statement.setObject(1, connectionId.value());
            statement.setShort(2, encrypted.cipherVersion());
            statement.setString(3, encrypted.keyId());
            statement.setBytes(4, encrypted.nonce());
            statement.setBytes(5, encrypted.ciphertext());
            Array scopes = connection.createArrayOf("text", grantedScopes.stream().sorted().toArray(String[]::new));
            statement.setArray(6, scopes);
            statement.setLong(7, credentialGeneration);
            return statement;
        });
    }

    private record ConnectionIdentity(TenantId tenantId, PlatformConnectionId connectionId) {
    }
}
