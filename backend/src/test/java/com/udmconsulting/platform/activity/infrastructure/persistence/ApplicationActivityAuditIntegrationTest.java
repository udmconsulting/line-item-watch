package com.udmconsulting.platform.activity.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.udmconsulting.integrations.hubspot.oauth.application.HubSpotInstallationStore;
import com.udmconsulting.platform.activity.application.ApplicationActivityAuditStore;
import com.udmconsulting.platform.connection.application.PlatformConnectionService;
import com.udmconsulting.platform.connection.domain.ConnectionStatus;
import com.udmconsulting.platform.connection.domain.ExternalAccountId;
import com.udmconsulting.platform.connection.domain.PlatformConnection;
import com.udmconsulting.platform.connection.domain.Provider;
import com.udmconsulting.platform.credential.application.ConcurrentCredentialChangeException;
import com.udmconsulting.platform.credential.application.ConnectionCredentialService;
import com.udmconsulting.platform.entitlement.application.EntitlementService;
import com.udmconsulting.platform.module.domain.ProductModule;
import com.udmconsulting.platform.tenant.application.TenantService;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers
class ApplicationActivityAuditIntegrationTest {

    private static final Set<String> SCOPES = Set.of(
            "crm.objects.deals.read", "crm.objects.line_items.read");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("hubspot.oauth.client-id", () -> "test-client-id");
        registry.add("hubspot.oauth.client-secret", () -> "test-client-secret");
        registry.add("hubspot.oauth.redirect-uri", () ->
                "http://localhost:8080/integrations/hubspot/oauth/callback");
        registry.add("hubspot.oauth.api-base-url", () -> "http://localhost:9999");
        registry.add("hubspot.oauth.authorization-base-url", () ->
                "https://app.hubspot.com/oauth/authorize");
        registry.add("hubspot.credentials.key-id", () -> "test-key-1");
        registry.add("hubspot.credentials.encryption-key", () ->
                Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Autowired
    private HubSpotInstallationStore installationStore;

    @Autowired
    private PlatformConnectionService connectionService;

    @Autowired
    private ConnectionCredentialService credentialService;

    @Autowired
    private EntitlementService entitlementService;

    @Autowired
    private TenantService tenantService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void clearData() {
        dropFailureTrigger();
        jdbcTemplate.update("DELETE FROM tenant");
    }

    @AfterEach
    void cleanupTrigger() {
        dropFailureTrigger();
    }

    @Test
    void installReauthorizationAndEntitlementAreTruthfulAndIdempotent() {
        String account = "audit-" + UUID.randomUUID();
        var first = installationStore.finalizeInstallation(account, "first-refresh", SCOPES);
        var second = installationStore.finalizeInstallation(account, "second-refresh", SCOPES);

        assertThat(second.tenantId()).isEqualTo(first.tenantId());
        assertThat(actions()).containsExactly(
                "PLATFORM_CONNECTION_ACTIVATED",
                "ENTITLEMENT_ACTIVATED",
                "PLATFORM_CONNECTION_REAUTHORIZED");
        assertThat(jdbcTemplate.queryForList("""
                SELECT actor_type, actor_source, actor_reference
                FROM application_activity_audit
                ORDER BY recorded_at, action
                """))
                .allSatisfy(row -> {
                    assertThat(row.get("actor_type")).isEqualTo("UNATTRIBUTED");
                    assertThat(row.get("actor_source")).isEqualTo("HUBSPOT");
                    assertThat(row.get("actor_reference")).isNull();
                });
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM application_activity_audit
                WHERE action = 'ENTITLEMENT_ACTIVATED'
                """, Long.class)).isEqualTo(1);
    }

    @Test
    void automaticReauthenticationTransitionIsRecordedOnceBySystem() throws Exception {
        String account = "reauth-" + UUID.randomUUID();
        installationStore.finalizeInstallation(account, "refresh", SCOPES);
        PlatformConnection connection = connection(account);
        var loaded = credentialService.load(connection);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<String> transition = () -> {
                try {
                    credentialService.requireReauthentication(
                            connection.id(), loaded.credentialGeneration());
                    return "changed";
                } catch (ConcurrentCredentialChangeException exception) {
                    return "lost";
                }
            };
            assertThat(executor.invokeAll(List.of(transition, transition)).stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception exception) {
                            throw new AssertionError(exception);
                        }
                    }).toList()).containsExactlyInAnyOrder("changed", "lost");
        }

        assertThat(connection(account).status()).isEqualTo(ConnectionStatus.REAUTH_REQUIRED);
        assertThatThrownBy(() -> credentialService.requireReauthentication(
                connection.id(), loaded.credentialGeneration()))
                .isInstanceOf(ConcurrentCredentialChangeException.class);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM application_activity_audit
                WHERE action = 'PLATFORM_CONNECTION_REAUTHENTICATION_REQUIRED'
                """, Long.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT actor_type, actor_source, previous_state, resulting_state
                FROM application_activity_audit
                WHERE action = 'PLATFORM_CONNECTION_REAUTHENTICATION_REQUIRED'
                """))
                .containsEntry("actor_type", "SYSTEM")
                .containsEntry("actor_source", "APPLICATION")
                .containsEntry("previous_state", "ACTIVE")
                .containsEntry("resulting_state", "REAUTH_REQUIRED");
    }

    @Test
    void serviceOnlyDisconnectIsRecordedOnceWithoutInventingAUser() {
        String account = "disconnect-" + UUID.randomUUID();
        installationStore.finalizeInstallation(account, "refresh", SCOPES);
        PlatformConnection connection = connection(account);
        var loaded = credentialService.load(connection);

        credentialService.disconnect(connection.id(), loaded.credentialGeneration());

        assertThat(connection(account).status()).isEqualTo(ConnectionStatus.DISCONNECTED);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT actor_type, actor_source, previous_state, resulting_state
                FROM application_activity_audit
                WHERE action = 'PLATFORM_CONNECTION_DISCONNECTED'
                """))
                .containsEntry("actor_type", "UNATTRIBUTED")
                .containsEntry("actor_source", "APPLICATION")
                .containsEntry("previous_state", "ACTIVE")
                .containsEntry("resulting_state", "DISCONNECTED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM application_activity_audit
                WHERE action = 'PLATFORM_CONNECTION_DISCONNECTED'
                """, Long.class)).isEqualTo(1);
    }

    @Test
    void entitlementNoOpsDoNotDuplicateAndOuterRollbackRemovesStateAndAudit() {
        var tenant = tenantService.create();
        entitlementService.enable(tenant.id(), ProductModule.LINE_ITEM_WATCH);
        entitlementService.enable(tenant.id(), ProductModule.LINE_ITEM_WATCH);
        entitlementService.disable(tenant.id(), ProductModule.LINE_ITEM_WATCH);
        entitlementService.disable(tenant.id(), ProductModule.LINE_ITEM_WATCH);

        assertThat(actions()).containsExactly("ENTITLEMENT_ACTIVATED", "ENTITLEMENT_DEACTIVATED");

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            entitlementService.enable(tenant.id(), ProductModule.LINE_ITEM_WATCH);
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(entitlementService.isEnabled(tenant.id(), ProductModule.LINE_ITEM_WATCH)).isFalse();
        assertThat(actions()).containsExactly("ENTITLEMENT_ACTIVATED", "ENTITLEMENT_DEACTIVATED");
    }

    @Test
    void auditInsertFailureRollsBackInstallationState() {
        jdbcTemplate.execute("""
                CREATE FUNCTION fail_application_activity_insert() RETURNS trigger
                LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'activity audit insert intentionally blocked';
                END;
                $$
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER fail_application_activity_insert
                BEFORE INSERT ON application_activity_audit
                FOR EACH ROW EXECUTE FUNCTION fail_application_activity_insert()
                """);
        String account = "rollback-" + UUID.randomUUID();

        assertThatThrownBy(() ->
                installationStore.finalizeInstallation(account, "refresh", SCOPES))
                .isInstanceOf(RuntimeException.class);

        assertThat(connectionService.resolve(Provider.HUBSPOT, new ExternalAccountId(account)))
                .isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM application_activity_audit", Long.class)).isZero();
    }

    @Test
    void auditPortIsInsertOnlyAndRowsCascadeWithTenantLifecycle() {
        assertThat(ApplicationActivityAuditStore.class.getDeclaredMethods())
                .extracting(java.lang.reflect.Method::getName)
                .containsExactly("append");
        String account = "cascade-" + UUID.randomUUID();
        var installation = installationStore.finalizeInstallation(account, "refresh", SCOPES);
        assertThat(actions()).isNotEmpty();

        jdbcTemplate.update("DELETE FROM tenant WHERE id = ?", installation.tenantId().value());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM application_activity_audit", Long.class)).isZero();
    }

    private PlatformConnection connection(String account) {
        return connectionService.resolve(Provider.HUBSPOT, new ExternalAccountId(account))
                .orElseThrow();
    }

    private List<String> actions() {
        return jdbcTemplate.queryForList("""
                SELECT action FROM application_activity_audit
                ORDER BY occurred_at, recorded_at, action
                """, String.class);
    }

    private void dropFailureTrigger() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_application_activity_insert ON application_activity_audit");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_application_activity_insert()");
    }
}
