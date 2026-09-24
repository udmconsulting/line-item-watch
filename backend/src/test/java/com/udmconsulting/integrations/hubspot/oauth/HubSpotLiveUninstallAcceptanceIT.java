package com.udmconsulting.integrations.hubspot.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.udmconsulting.integrations.hubspot.config.HubSpotOAuthProperties;
import com.udmconsulting.integrations.hubspot.oauth.application.HubSpotUninstallService;
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
class HubSpotLiveUninstallAcceptanceIT {

    private static final String LIVE_UNINSTALL_CONFIRM_ENVIRONMENT_VARIABLE =
            "HUBSPOT_LIVE_UNINSTALL_CONFIRM";
    private static final String HUBSPOT_ACCOUNT_ID = "149377304";
    private static final long EXPECTED_GENERATION_BEFORE = 3L;
    private static final String LOCAL_DATABASE_URL =
            "jdbc:postgresql://localhost:5433/line_item_watch_local";

    static {
        if (!HUBSPOT_ACCOUNT_ID.equals(
                System.getenv(LIVE_UNINSTALL_CONFIRM_ENVIRONMENT_VARIABLE))) {
            throw new IllegalStateException(
                    "HUBSPOT_LIVE_UNINSTALL_CONFIRM must exactly match the approved HubSpot account ID");
        }
    }

    @Autowired
    private PlatformConnectionService connectionService;

    @Autowired
    private ConnectionCredentialStore credentialStore;

    @Autowired
    private EntitlementService entitlementService;

    @Autowired
    private HubSpotUninstallService uninstallService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Environment environment;

    @Test
    void uninstallsOnceAndPreservesDisconnectedIdentity() {
        assertLocalRuntime();

        PlatformConnection beforeConnection = resolveConnection();
        ConnectionCredential beforeCredential = loadCredential(beforeConnection);
        long generationBefore = beforeCredential.credentialGeneration();
        assertActiveInstalledState(beforeConnection, beforeCredential);

        uninstallService.uninstall(beforeConnection.tenantId(), beforeConnection.id());

        PlatformConnection afterConnection = resolveConnection();
        long generationAfter = connectionGeneration(afterConnection);
        assertDisconnectedState(beforeConnection, afterConnection, generationBefore, generationAfter);

        System.out.println("LIVE_UNINSTALL_ACCEPTANCE_PASS");
        System.out.println("generation before: " + generationBefore);
        System.out.println("generation after: " + generationAfter);
        System.out.println("connection status: DISCONNECTED");
        System.out.println("credential count: 0");
        System.out.println("tenant preserved: yes");
        System.out.println("connection preserved: yes");
        System.out.println("entitlement preserved: yes");
    }

    private void assertLocalRuntime() {
        assertTrue(
                Arrays.asList(environment.getActiveProfiles()).contains("local"),
                "The live uninstall acceptance requires SPRING_PROFILES_ACTIVE=local");
        assertEquals(
                LOCAL_DATABASE_URL,
                environment.getProperty("spring.datasource.url"),
                "The live uninstall acceptance is restricted to the local acceptance database");
    }

    private PlatformConnection resolveConnection() {
        return connectionService.resolve(Provider.HUBSPOT, new ExternalAccountId(HUBSPOT_ACCOUNT_ID))
                .orElseThrow(() -> new AssertionError("Expected HubSpot connection was not found"));
    }

    private ConnectionCredential loadCredential(PlatformConnection connection) {
        return credentialStore.findByConnectionId(connection.id())
                .orElseThrow(() -> new AssertionError("Expected encrypted refresh credential was not found"));
    }

    private void assertActiveInstalledState(
            PlatformConnection connection, ConnectionCredential credential) {
        assertEquals(Provider.HUBSPOT, connection.provider(), "Unexpected provider");
        assertEquals(HUBSPOT_ACCOUNT_ID, connection.externalAccountId().value(), "Unexpected HubSpot account");
        assertEquals(ConnectionStatus.ACTIVE, connection.status(), "HubSpot connection is not active");
        assertEquals(connection.id(), credential.connectionId(), "Credential belongs to another connection");
        assertEquals(
                EXPECTED_GENERATION_BEFORE,
                credential.credentialGeneration(),
                "Live uninstall credential generation does not match the approved baseline");
        assertTrue(
                credential.grantedScopes().containsAll(HubSpotOAuthProperties.REQUIRED_SCOPES),
                "Stored credential is missing required HubSpot scopes");
        assertEncryptedCredentialStructure(credential);

        assertEquals(1L, queryLong("SELECT COUNT(*) FROM tenant"), "Unexpected Tenant count");
        assertEquals(1L, queryLong("SELECT COUNT(*) FROM platform_connection"),
                "Unexpected Platform Connection count");
        assertEquals(1L, queryLong(
                "SELECT COUNT(*) FROM connection_credential WHERE connection_id = ?",
                connection.id().value()), "Expected exactly one connection credential");
        assertEquals(
                credential.credentialGeneration(),
                connectionGeneration(connection),
                "Platform Connection and credential generations differ");
        assertEntitlementPreserved(connection);
        assertNoTransientCredentialColumns();
    }

    private void assertDisconnectedState(
            PlatformConnection beforeConnection,
            PlatformConnection afterConnection,
            long generationBefore,
            long generationAfter) {
        assertEquals(beforeConnection.tenantId(), afterConnection.tenantId(),
                "Tenant changed during uninstall");
        assertEquals(beforeConnection.id(), afterConnection.id(),
                "Platform Connection changed during uninstall");
        assertEquals(Provider.HUBSPOT, afterConnection.provider(), "Unexpected provider after uninstall");
        assertEquals(HUBSPOT_ACCOUNT_ID, afterConnection.externalAccountId().value(),
                "Unexpected HubSpot account after uninstall");
        assertEquals(ConnectionStatus.DISCONNECTED, afterConnection.status(),
                "HubSpot connection is not disconnected");
        assertTrue(generationAfter >= generationBefore, "Credential generation moved backwards");
        assertTrue(credentialStore.findByConnectionId(afterConnection.id()).isEmpty(),
                "Refresh credential remains after uninstall");

        assertEquals(1L, queryLong("SELECT COUNT(*) FROM tenant"), "Tenant was not preserved");
        assertEquals(1L, queryLong("SELECT COUNT(*) FROM platform_connection"),
                "Platform Connection was not preserved");
        assertEquals(0L, queryLong(
                "SELECT COUNT(*) FROM connection_credential WHERE connection_id = ?",
                afterConnection.id().value()), "Credential row remains after uninstall");
        assertEntitlementPreserved(afterConnection);
        assertNoTransientCredentialColumns();
    }

    private void assertEntitlementPreserved(PlatformConnection connection) {
        assertTrue(
                entitlementService.isEnabled(connection.tenantId(), ProductModule.LINE_ITEM_WATCH),
                "LINE_ITEM_WATCH entitlement is not enabled");
        assertEquals(1L, queryLong(
                "SELECT COUNT(*) FROM tenant_entitlement WHERE tenant_id = ? AND product_module = ?",
                connection.tenantId().value(), ProductModule.LINE_ITEM_WATCH.name()),
                "Expected exactly one LINE_ITEM_WATCH entitlement");
    }

    private void assertNoTransientCredentialColumns() {
        assertEquals(0L, queryLong("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND (
                    lower(column_name) LIKE '%access%token%'
                    OR lower(column_name) LIKE '%authorization%code%'
                    OR lower(column_name) LIKE '%refresh%token%'
                    OR lower(column_name) LIKE '%client%secret%'
                    OR lower(column_name) LIKE '%encryption%key%'
                  )
                """), "A transient OAuth or secret value has a persistence column");
    }

    private static void assertEncryptedCredentialStructure(ConnectionCredential credential) {
        assertEquals((short) 1, credential.refreshCredential().cipherVersion(),
                "Unexpected credential cipher version");
        assertFalse(credential.refreshCredential().keyId().isBlank(), "Credential key ID is blank");
        assertEquals(12, credential.refreshCredential().nonce().length, "Credential nonce length is invalid");
        assertTrue(credential.refreshCredential().ciphertext().length > 16,
                "Encrypted refresh credential is missing");
    }

    private long connectionGeneration(PlatformConnection connection) {
        return queryLong(
                "SELECT credential_generation FROM platform_connection WHERE id = ?",
                connection.id().value());
    }

    private long queryLong(String sql, Object... arguments) {
        return Objects.requireNonNull(jdbcTemplate.queryForObject(sql, Long.class, arguments));
    }
}
