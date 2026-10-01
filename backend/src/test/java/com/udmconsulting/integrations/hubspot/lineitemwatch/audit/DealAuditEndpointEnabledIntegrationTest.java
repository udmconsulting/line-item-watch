package com.udmconsulting.integrations.hubspot.lineitemwatch.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.udmconsulting.modules.lineitemwatch.application.DealAuditView;
import com.udmconsulting.modules.lineitemwatch.application.LineItemSnapshotStore;
import com.udmconsulting.modules.lineitemwatch.domain.BillingStart;
import com.udmconsulting.modules.lineitemwatch.domain.LineItemObservation;
import com.udmconsulting.modules.lineitemwatch.domain.ProviderObjectId;
import com.udmconsulting.platform.connection.application.PlatformConnectionService;
import com.udmconsulting.platform.connection.domain.ExternalAccountId;
import com.udmconsulting.platform.connection.domain.PlatformConnection;
import com.udmconsulting.platform.connection.domain.Provider;
import com.udmconsulting.platform.entitlement.application.EntitlementService;
import com.udmconsulting.platform.module.domain.ProductModule;
import com.udmconsulting.platform.tenant.application.TenantService;
import com.udmconsulting.platform.tenant.domain.Tenant;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = {
        "line-item-watch.processing.enabled=false",
        "hubspot.ui-extension.enabled=true",
        "hubspot.ui-extension.public-base-uri=https://api.example.test",
        "hubspot.ui-extension.app-id=12345"
})
@Testcontainers
class DealAuditEndpointEnabledIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("hubspot.oauth.client-id", () -> "test-client-id");
        registry.add("hubspot.oauth.client-secret", () -> "test-client-secret");
        registry.add("hubspot.oauth.redirect-uri", () -> "http://localhost:8080/callback");
        registry.add("hubspot.oauth.api-base-url", () -> "http://localhost:9999");
        registry.add("hubspot.oauth.authorization-base-url", () -> "https://app.hubspot.com/oauth/authorize");
        registry.add("hubspot.credentials.key-id", () -> "test-key-1");
        registry.add("hubspot.credentials.encryption-key", () ->
                Base64.getEncoder().encodeToString(new byte[32]));
        registry.add("line-item-watch.audit.cursor.active-key-id", () -> "test-cursor-1");
        registry.add("line-item-watch.audit.cursor.active-key", () ->
                Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Autowired ApplicationContext applicationContext;
    @Autowired HubSpotDealAuditReadService readService;
    @Autowired TenantService tenantService;
    @Autowired PlatformConnectionService connectionService;
    @Autowired EntitlementService entitlementService;
    @Autowired LineItemSnapshotStore snapshotStore;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearData() {
        jdbcTemplate.update("DELETE FROM tenant");
    }

    @Test
    void explicitConfigurationActivatesCompleteSignedReadBoundary() {
        assertThat(applicationContext.containsBean("hubSpotDealAuditController")).isTrue();
        assertThat(applicationContext.containsBean("hubSpotUiExtensionRequestAuthenticator")).isTrue();
        assertThat(applicationContext.containsBean("correlationFilter")).isTrue();
        assertThat(applicationContext.containsBean("httpOperationMetricsFilter")).isTrue();
        assertThat(applicationContext.containsBean("dealAuditErrorHandler")).isTrue();
        assertThat(applicationContext.containsBean("hubSpotDealAuditReadService")).isTrue();
    }

    @Test
    void authorizedDealAuditReadUsesPostgresLocksAndDoesNotMutateBusinessData() {
        Tenant tenant = tenantService.create();
        ExternalAccountId accountId = new ExternalAccountId("deal-audit-lock-regression");
        PlatformConnection connection = connectionService.register(
                tenant.id(), Provider.HUBSPOT, accountId);
        jdbcTemplate.update("""
                UPDATE platform_connection
                SET status = 'ACTIVE', status_changed_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND id = ?
                """, tenant.id().value(), connection.id().value());
        entitlementService.enable(tenant.id(), ProductModule.LINE_ITEM_WATCH);

        Instant observedAt = Instant.parse("2026-09-29T12:00:00Z");
        snapshotStore.establish(tenant.id(), connection.id(), List.of(new LineItemObservation(
                new ProviderObjectId("2002"),
                "Synthetic test item",
                BigDecimal.ONE,
                null,
                null,
                null,
                null,
                BillingStart.unspecified(),
                null,
                observedAt.minusSeconds(60),
                observedAt.minusSeconds(30),
                observedAt,
                Set.of(new ProviderObjectId("1001")))));
        PersistentState before = persistentState(tenant, connection);

        DealAuditView result = readService.read(
                new AuthenticatedHubSpotUiCaller(accountId),
                new DealAuditQueryFactory.QueryInput(
                        new ProviderObjectId("1001"), 10, null, 20, null),
                UUID.randomUUID());

        assertThat(result.lineItems().items())
                .extracting(item -> item.lineItemId().value())
                .containsExactly("2002");
        assertThat(result.events().items()).isEmpty();
        assertThat(persistentState(tenant, connection)).isEqualTo(before);
    }

    private PersistentState persistentState(Tenant tenant, PlatformConnection connection) {
        return new PersistentState(
                jdbcTemplate.queryForObject("""
                        SELECT status FROM platform_connection
                        WHERE tenant_id = ? AND id = ?
                        """, String.class, tenant.id().value(), connection.id().value()),
                jdbcTemplate.queryForObject("""
                        SELECT status_changed_at FROM platform_connection
                        WHERE tenant_id = ? AND id = ?
                        """, Timestamp.class, tenant.id().value(), connection.id().value()),
                jdbcTemplate.queryForObject("""
                        SELECT enabled_at FROM tenant_entitlement
                        WHERE tenant_id = ? AND product_module = 'LINE_ITEM_WATCH'
                        """, Timestamp.class, tenant.id().value()),
                jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM application_activity_audit
                        WHERE tenant_id = ?
                        """, Long.class, tenant.id().value()),
                jdbcTemplate.queryForObject("""
                        SELECT
                            (SELECT COUNT(*) FROM line_item_watch_line_item WHERE tenant_id = ?)
                          + (SELECT COUNT(*) FROM line_item_watch_snapshot WHERE tenant_id = ?)
                          + (SELECT COUNT(*) FROM line_item_watch_snapshot_deal WHERE tenant_id = ?)
                          + (SELECT COUNT(*) FROM line_item_watch_audit_event WHERE tenant_id = ?)
                          + (SELECT COUNT(*) FROM line_item_watch_audit_event_deal_context
                             WHERE tenant_id = ?)
                        """, Long.class,
                        tenant.id().value(), tenant.id().value(), tenant.id().value(),
                        tenant.id().value(), tenant.id().value()));
    }

    private record PersistentState(
            String connectionStatus,
            Timestamp connectionStatusChangedAt,
            Timestamp entitlementEnabledAt,
            long activityRows,
            long projectionRows) {
    }
}
