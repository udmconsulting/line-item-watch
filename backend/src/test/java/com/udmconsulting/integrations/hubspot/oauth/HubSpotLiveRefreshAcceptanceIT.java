package com.udmconsulting.integrations.hubspot.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.udmconsulting.integrations.hubspot.config.HubSpotOAuthProperties;
import com.udmconsulting.integrations.hubspot.oauth.application.HubSpotAccessTokenProvider;
import com.udmconsulting.platform.connection.application.PlatformConnectionService;
import com.udmconsulting.platform.connection.domain.ConnectionStatus;
import com.udmconsulting.platform.connection.domain.ExternalAccountId;
import com.udmconsulting.platform.connection.domain.PlatformConnection;
import com.udmconsulting.platform.connection.domain.Provider;
import com.udmconsulting.platform.credential.application.ConnectionCredentialStore;
import com.udmconsulting.platform.credential.domain.ConnectionCredential;
import com.udmconsulting.platform.entitlement.application.EntitlementService;
import com.udmconsulting.platform.module.domain.ProductModule;
import java.util.Arrays;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class HubSpotLiveRefreshAcceptanceIT {

    private static final String LIVE_ACCEPTANCE_ENVIRONMENT_VARIABLE = "HUBSPOT_LIVE_ACCEPTANCE";
    private static final String HUBSPOT_ACCOUNT_ID = "149377304";
    private static final String LOCAL_DATABASE_URL =
            "jdbc:postgresql://localhost:5433/line_item_watch_local";

    static {
        if (!"true".equals(System.getenv(LIVE_ACCEPTANCE_ENVIRONMENT_VARIABLE))) {
            throw new IllegalStateException(
                    "HUBSPOT_LIVE_ACCEPTANCE must be exactly true to run the live refresh acceptance");
        }
    }

    @Autowired
    private PlatformConnectionService connectionService;

    @Autowired
    private ConnectionCredentialStore credentialStore;

    @Autowired
    private EntitlementService entitlementService;

    @Autowired
    private HubSpotAccessTokenProvider accessTokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Environment environment;

    @Test
    void refreshesAndIntrospectsOneTransientAccessToken() {
        assertLocalRuntime();

        PlatformConnection beforeConnection = resolveConnection();
        ConnectionCredential beforeCredential = loadCredential(beforeConnection);
        long generationBefore = beforeCredential.credentialGeneration();
        assertInstalledState(beforeConnection, beforeCredential);

        HubSpotAccessTokenProvider.TransientAccessGrant access =
                accessTokenProvider.accessTokenFor(beforeConnection);

        assertTrue(
                access.accessToken() != null && !access.accessToken().isBlank(),
                "HubSpot did not return a transient access token");
        assertEquals(
                beforeConnection.id(), access.connectionId(),
                "Transient access grant belongs to an unexpected connection");

        PlatformConnection afterConnection = resolveConnection();
        ConnectionCredential afterCredential = loadCredential(afterConnection);
        long generationAfter = afterCredential.credentialGeneration();
        assertInstalledState(afterConnection, afterCredential);

        assertEquals(
                beforeConnection.tenantId(), afterConnection.tenantId(),
                "Tenant changed during refresh");
        assertEquals(
                beforeConnection.id(), afterConnection.id(),
                "Platform Connection changed during refresh");
        assertTrue(generationAfter >= generationBefore, "Credential generation moved backwards");
        assertEquals(
                generationAfter,
                access.expectedCredentialGeneration(),
                "Transient access grant does not own the current credential generation");

        boolean replacementPersisted = generationAfter > generationBefore;
        System.out.println("LIVE_REFRESH_ACCEPTANCE_PASS");
        System.out.println("generation before: " + generationBefore);
        System.out.println("generation after: " + generationAfter);
        System.out.println("replacement persisted: " + (replacementPersisted ? "yes" : "no"));
        System.out.println("connection status: ACTIVE");
        System.out.println("client match: yes");
        System.out.println("account match: yes");
        System.out.println("required scopes: yes");
    }

    private void assertLocalRuntime() {
        assertTrue(
                Arrays.asList(environment.getActiveProfiles()).contains("local"),
                "The live refresh acceptance requires SPRING_PROFILES_ACTIVE=local");
        assertEquals(
                LOCAL_DATABASE_URL,
                environment.getProperty("spring.datasource.url"),
                "The live refresh acceptance is restricted to the local acceptance database");
    }

    private PlatformConnection resolveConnection() {
        return connectionService.resolve(Provider.HUBSPOT, new ExternalAccountId(HUBSPOT_ACCOUNT_ID))
                .orElseThrow(() -> new AssertionError("Expected HubSpot connection was not found"));
    }

    private ConnectionCredential loadCredential(PlatformConnection connection) {
        return credentialStore.findByConnectionId(connection.id())
                .orElseThrow(() -> new AssertionError("Expected encrypted refresh credential was not found"));
    }

    private void assertInstalledState(
            PlatformConnection connection, ConnectionCredential credential) {
        assertEquals(Provider.HUBSPOT, connection.provider(), "Unexpected provider");
        assertEquals(HUBSPOT_ACCOUNT_ID, connection.externalAccountId().value(), "Unexpected HubSpot account");
        assertEquals(ConnectionStatus.ACTIVE, connection.status(), "HubSpot connection is not active");
        assertEquals(connection.id(), credential.connectionId(), "Credential belongs to another connection");
        assertTrue(credential.credentialGeneration() > 0, "Credential generation must be positive");
        assertTrue(
                credential.grantedScopes().containsAll(HubSpotOAuthProperties.REQUIRED_SCOPES),
                "Stored credential is missing required HubSpot scopes");
        assertEncryptedCredentialStructure(credential);

        assertTrue(
                entitlementService.isEnabled(connection.tenantId(), ProductModule.LINE_ITEM_WATCH),
                "LINE_ITEM_WATCH entitlement is not enabled");
        assertEquals(1L, queryLong("SELECT COUNT(*) FROM tenant"), "Unexpected Tenant count");
        assertEquals(1L, queryLong("SELECT COUNT(*) FROM platform_connection"),
                "Unexpected Platform Connection count");
        assertEquals(1L, queryLong(
                "SELECT COUNT(*) FROM connection_credential WHERE connection_id = ?",
                connection.id().value()), "Expected exactly one connection credential");
        assertEquals(1L, queryLong(
                "SELECT COUNT(*) FROM tenant_entitlement WHERE tenant_id = ? AND product_module = ?",
                connection.tenantId().value(), ProductModule.LINE_ITEM_WATCH.name()),
                "Expected exactly one LINE_ITEM_WATCH entitlement");
        assertEquals(
                credential.credentialGeneration(),
                queryLong("SELECT credential_generation FROM platform_connection WHERE id = ?",
                        connection.id().value()),
                "Platform Connection and credential generations differ");
        assertEquals(0L, queryLong("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND (
                    lower(column_name) LIKE '%access%token%'
                    OR lower(column_name) LIKE '%authorization%code%'
                  )
                """), "A transient OAuth value has a persistence column");
    }

    private static void assertEncryptedCredentialStructure(ConnectionCredential credential) {
        assertEquals((short) 1, credential.refreshCredential().cipherVersion(),
                "Unexpected credential cipher version");
        assertFalse(credential.refreshCredential().keyId().isBlank(), "Credential key ID is blank");
        assertEquals(12, credential.refreshCredential().nonce().length, "Credential nonce length is invalid");
        assertTrue(
                credential.refreshCredential().ciphertext().length > 16,
                "Encrypted refresh credential is missing");
    }

    private long queryLong(String sql, Object... arguments) {
        return Objects.requireNonNull(jdbcTemplate.queryForObject(sql, Long.class, arguments));
    }
}
