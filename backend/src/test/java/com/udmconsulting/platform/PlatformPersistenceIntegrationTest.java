package com.udmconsulting.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.udmconsulting.platform.connection.application.PlatformConnectionService;
import com.udmconsulting.platform.connection.domain.ExternalAccountId;
import com.udmconsulting.platform.connection.domain.ConnectionStatus;
import com.udmconsulting.platform.connection.domain.PlatformConnection;
import com.udmconsulting.platform.connection.domain.Provider;
import com.udmconsulting.platform.entitlement.application.EntitlementService;
import com.udmconsulting.platform.module.domain.ProductModule;
import com.udmconsulting.platform.tenant.application.TenantService;
import com.udmconsulting.platform.tenant.domain.Tenant;
import java.util.Set;
import java.util.UUID;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers
class PlatformPersistenceIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("hubspot.oauth.client-id", () -> "test-client-id");
        registry.add("hubspot.oauth.client-secret", () -> "test-client-secret");
        registry.add("hubspot.oauth.redirect-uri", () -> "http://localhost:8080/integrations/hubspot/oauth/callback");
        registry.add("hubspot.oauth.api-base-url", () -> "http://localhost:9999");
        registry.add("hubspot.oauth.authorization-base-url", () -> "https://app.hubspot.com/oauth/authorize");
        registry.add("hubspot.credentials.key-id", () -> "test-key-1");
        registry.add("hubspot.credentials.encryption-key", () ->
                Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Autowired
    private TenantService tenantService;

    @Autowired
    private PlatformConnectionService connectionService;

    @Autowired
    private EntitlementService entitlementService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearFoundationData() {
        jdbcTemplate.update("DELETE FROM tenant");
    }

    @Test
    void migrationsApplyAndHibernateValidatesTheExpectedSchema() {
        Set<String> tables = Set.copyOf(jdbcTemplate.queryForList("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                """, String.class));

        assertThat(tables).contains(
                "databasechangelog",
                "databasechangeloglock",
                "tenant",
                "platform_connection",
                "tenant_entitlement",
                "oauth_install_state",
                "connection_credential");
    }

    @Test
    void tenantAndMultipleConnectionsCanBePersistedAndResolved() {
        Tenant tenant = tenantService.create();
        PlatformConnection first = connectionService.register(
                tenant.id(), Provider.HUBSPOT, new ExternalAccountId("account-one"));
        PlatformConnection second = connectionService.register(
                tenant.id(), Provider.HUBSPOT, new ExternalAccountId("account-two"));

        assertThat(tenantService.findById(tenant.id())).contains(tenant);
        assertThat(connectionService.resolve(
                Provider.HUBSPOT, new ExternalAccountId("account-one"))).contains(first);
        assertThat(connectionService.resolve(
                Provider.HUBSPOT, new ExternalAccountId("account-two"))).contains(second);
        assertThat(first.status()).isEqualTo(ConnectionStatus.DISCONNECTED);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT credential_generation FROM platform_connection WHERE id = ?",
                Long.class, first.id().value())).isZero();
    }

    @Test
    void credentialSchemaRejectsUnsupportedCryptoMetadataAndInvalidScopeArrays() {
        Tenant tenant = tenantService.create();
        PlatformConnection connection = connectionService.register(
                tenant.id(), Provider.HUBSPOT, new ExternalAccountId("credential-constraints"));
        jdbcTemplate.update(
                "UPDATE platform_connection SET credential_generation = 1 WHERE id = ?",
                connection.id().value());

        String insert = """
                INSERT INTO connection_credential
                    (connection_id, cipher_version, key_id, nonce, ciphertext,
                     granted_scopes, credential_generation)
                VALUES (?, ?, ?, decode(repeat('00', 12), 'hex'),
                        decode(repeat('00', 17), 'hex'), %s, 1)
                """;

        assertThatThrownBy(() -> jdbcTemplate.update(
                insert.formatted("ARRAY['scope']::text[]"), connection.id().value(), 2, "key-1"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                insert.formatted("ARRAY['scope']::text[]"), connection.id().value(), 1, " key-1"))
                .isInstanceOf(DataIntegrityViolationException.class);
        for (String invalidScopes : new String[] {
                "ARRAY[]::text[]", "ARRAY[NULL]::text[]", "ARRAY['']::text[]"
        }) {
            assertThatThrownBy(() -> jdbcTemplate.update(
                    insert.formatted(invalidScopes), connection.id().value(), 1, "key-1"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        jdbcTemplate.update(
                insert.formatted("ARRAY['crm.objects.deals.read']::text[]"),
                connection.id().value(), 1, "key-1");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT credential_generation FROM connection_credential WHERE connection_id = ?",
                Long.class, connection.id().value())).isEqualTo(1);
    }

    @Test
    void connectionLookupIsTenantScopedWhenInternalConnectionIdIsUsed() {
        Tenant owner = tenantService.create();
        Tenant otherTenant = tenantService.create();
        PlatformConnection connection = connectionService.register(
                owner.id(), Provider.HUBSPOT, new ExternalAccountId("scoped-account"));

        assertThat(connectionService.findForTenant(owner.id(), connection.id())).contains(connection);
        assertThat(connectionService.findForTenant(otherTenant.id(), connection.id())).isEmpty();
    }

    @Test
    void databaseRejectsConnectionForUnknownTenant() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO platform_connection (id, tenant_id, provider, external_account_id)
                VALUES (?, ?, 'HUBSPOT', 'orphan-account')
                """, UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void providerAndExternalAccountCombinationIsGloballyUnique() {
        Tenant firstTenant = tenantService.create();
        Tenant secondTenant = tenantService.create();
        ExternalAccountId accountId = new ExternalAccountId("unique-account");

        connectionService.register(firstTenant.id(), Provider.HUBSPOT, accountId);

        assertThatThrownBy(() -> connectionService.register(
                secondTenant.id(), Provider.HUBSPOT, accountId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseEnforcesCanonicalExternalAccountIdsForDirectWrites() {
        Tenant tenant = tenantService.create();
        String insert = """
                INSERT INTO platform_connection (id, tenant_id, provider, external_account_id)
                VALUES (?, ?, 'HUBSPOT', ?)
                """;

        jdbcTemplate.update(insert, UUID.randomUUID(), tenant.id().value(), "00A9XYZ");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM platform_connection WHERE external_account_id = '00A9XYZ'",
                Long.class)).isEqualTo(1L);

        for (String invalidAccountId : new String[] {
                "", "   ", "\t", " 123", "123 ", "\t123", "123\t", " 123 "
        }) {
            assertThatThrownBy(() -> jdbcTemplate.update(
                    insert, UUID.randomUUID(), tenant.id().value(), invalidAccountId))
                    .as("external account ID <%s>", invalidAccountId)
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Test
    void entitlementOperationsAreIdempotentAndTenantIsolated() {
        Tenant enabledTenant = tenantService.create();
        Tenant otherTenant = tenantService.create();

        entitlementService.enable(enabledTenant.id(), ProductModule.LINE_ITEM_WATCH);
        entitlementService.enable(enabledTenant.id(), ProductModule.LINE_ITEM_WATCH);

        assertThat(entitlementService.isEnabled(
                enabledTenant.id(), ProductModule.LINE_ITEM_WATCH)).isTrue();
        assertThat(entitlementService.isEnabled(
                otherTenant.id(), ProductModule.LINE_ITEM_WATCH)).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tenant_entitlement WHERE tenant_id = ?",
                Long.class,
                enabledTenant.id().value())).isEqualTo(1L);

        entitlementService.disable(enabledTenant.id(), ProductModule.LINE_ITEM_WATCH);
        entitlementService.disable(enabledTenant.id(), ProductModule.LINE_ITEM_WATCH);

        assertThat(entitlementService.isEnabled(
                enabledTenant.id(), ProductModule.LINE_ITEM_WATCH)).isFalse();
    }

    @Test
    void deletingTenantCascadesConnectionsAndEntitlements() {
        Tenant tenant = tenantService.create();
        connectionService.register(
                tenant.id(), Provider.HUBSPOT, new ExternalAccountId("cascade-account"));
        entitlementService.enable(tenant.id(), ProductModule.LINE_ITEM_WATCH);

        jdbcTemplate.update("DELETE FROM tenant WHERE id = ?", tenant.id().value());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM platform_connection WHERE tenant_id = ?",
                Long.class,
                tenant.id().value())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tenant_entitlement WHERE tenant_id = ?",
                Long.class,
                tenant.id().value())).isZero();
    }
}
