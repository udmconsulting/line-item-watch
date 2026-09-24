package com.udmconsulting.integrations.hubspot.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.udmconsulting.integrations.hubspot.oauth.application.HubSpotAccessTokenProvider;
import com.udmconsulting.integrations.hubspot.oauth.application.HubSpotInstallationStore;
import com.udmconsulting.integrations.hubspot.oauth.application.HubSpotOAuthGateway;
import com.udmconsulting.integrations.hubspot.oauth.application.HubSpotOAuthFailureCategory;
import com.udmconsulting.integrations.hubspot.oauth.application.HubSpotProviderUnavailableException;
import com.udmconsulting.integrations.hubspot.oauth.application.HubSpotUninstallService;
import com.udmconsulting.integrations.hubspot.oauth.application.InvalidRefreshCredentialException;
import com.udmconsulting.integrations.hubspot.oauth.application.OAuthStateStore;
import com.udmconsulting.platform.connection.application.PlatformConnectionService;
import com.udmconsulting.platform.connection.domain.ConnectionStatus;
import com.udmconsulting.platform.connection.domain.ExternalAccountId;
import com.udmconsulting.platform.connection.domain.PlatformConnection;
import com.udmconsulting.platform.connection.domain.Provider;
import com.udmconsulting.platform.credential.application.ConcurrentCredentialChangeException;
import com.udmconsulting.platform.credential.application.ConnectionCredentialService;
import com.udmconsulting.platform.credential.application.ReauthenticationRequiredException;
import com.udmconsulting.platform.tenant.domain.TenantId;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers
class HubSpotOAuthLifecycleIntegrationTest {

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
    private ConnectionCredentialService credentialService;

    @Autowired
    private PlatformConnectionService connectionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OAuthStateStore stateStore;

    @Autowired
    private RequestMappingHandlerMapping requestMappingHandlerMapping;

    @BeforeEach
    void clearData() {
        jdbcTemplate.update("DELETE FROM oauth_install_state");
        jdbcTemplate.update("DELETE FROM tenant");
    }

    @Test
    void installAndReinstallReuseIdentityActivateEntitlementAndReplaceEncryptedCredential() {
        String account = uniqueAccount();

        HubSpotInstallationStore.FinalizedInstallation first =
                installationStore.finalizeInstallation(account, "first-refresh", SCOPES);
        HubSpotInstallationStore.FinalizedInstallation second =
                installationStore.finalizeInstallation(account, "second-refresh", SCOPES);

        assertThat(second.tenantId()).isEqualTo(first.tenantId());
        assertThat(second.connectionId()).isEqualTo(first.connectionId());
        assertThat(second.supersededCredential()).isPresent();
        PlatformConnection connection = connection(account);
        assertThat(connection.status()).isEqualTo(ConnectionStatus.ACTIVE);
        assertThat(credentialService.load(connection).refreshToken()).isEqualTo("second-refresh");
        assertThat(credentialService.load(connection).credentialGeneration()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM tenant_entitlement
                WHERE tenant_id = ? AND product_module = 'LINE_ITEM_WATCH'
                """, Long.class, first.tenantId().value())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT position(convert_to('second-refresh', 'UTF8') in ciphertext) > 0
                FROM connection_credential WHERE connection_id = ?
                """, Boolean.class, first.connectionId().value())).isFalse();
    }

    @Test
    void confirmedInvalidCredentialDeletesOnlyItsVersionAndRequiresReauthentication() {
        String account = uniqueAccount();
        installationStore.finalizeInstallation(account, "invalid-refresh", SCOPES);
        PlatformConnection connection = connection(account);
        HubSpotAccessTokenProvider provider = new HubSpotAccessTokenProvider(
                credentialService, new StubGateway() {
                    @Override
                    public IssuedRefreshTokens refresh(String refreshToken) {
                        throw new InvalidRefreshCredentialException();
                    }
                });

        assertThatThrownBy(() -> provider.accessTokenFor(connection))
                .isInstanceOf(ReauthenticationRequiredException.class);

        assertThat(credentialCount(connection)).isZero();
        assertThat(connection(account).status()).isEqualTo(ConnectionStatus.REAUTH_REQUIRED);
    }

    @Test
    void staleInvalidGrantCannotDeleteNewerCredentialOrChangeActiveState() {
        String account = uniqueAccount();
        installationStore.finalizeInstallation(account, "version-n", SCOPES);
        PlatformConnection staleConnection = connection(account);
        HubSpotAccessTokenProvider provider = new HubSpotAccessTokenProvider(
                credentialService, new StubGateway() {
                    @Override
                    public IssuedRefreshTokens refresh(String refreshToken) {
                        assertThat(refreshToken).isEqualTo("version-n");
                        installationStore.finalizeInstallation(account, "version-n-plus-one", SCOPES);
                        throw new InvalidRefreshCredentialException();
                    }
                });

        assertThatThrownBy(() -> provider.accessTokenFor(staleConnection))
                .isInstanceOf(ConcurrentCredentialChangeException.class);

        PlatformConnection current = connection(account);
        assertThat(current.status()).isEqualTo(ConnectionStatus.ACTIVE);
        assertThat(credentialService.load(current).refreshToken()).isEqualTo("version-n-plus-one");
        assertThat(credentialService.load(current).credentialGeneration()).isEqualTo(2);
    }

    @Test
    void replacementTokenCompareAndSetPreservesConcurrentWinner() {
        String account = uniqueAccount();
        installationStore.finalizeInstallation(account, "version-n", SCOPES);
        PlatformConnection staleConnection = connection(account);
        HubSpotAccessTokenProvider provider = new HubSpotAccessTokenProvider(
                credentialService, new StubGateway() {
                    @Override
                    public IssuedRefreshTokens refresh(String refreshToken) {
                        installationStore.finalizeInstallation(account, "concurrent-winner", SCOPES);
                        return issuedRefresh(
                                "transient-access", Optional.of("stale-replacement"), account, SCOPES);
                    }
                });

        assertThatThrownBy(() -> provider.accessTokenFor(staleConnection))
                .isInstanceOf(ConcurrentCredentialChangeException.class);

        PlatformConnection current = connection(account);
        assertThat(credentialService.load(current).refreshToken()).isEqualTo("concurrent-winner");
        assertThat(credentialService.load(current).credentialGeneration()).isEqualTo(2);
        assertThat(current.status()).isEqualTo(ConnectionStatus.ACTIVE);
    }

    @Test
    void replacementCredentialSurvivesTransientIntrospectionFailure() {
        String account = uniqueAccount();
        installationStore.finalizeInstallation(account, "version-n", SCOPES);
        HubSpotAccessTokenProvider provider = new HubSpotAccessTokenProvider(
                credentialService, new StubGateway() {
                    @Override
                    public IssuedRefreshTokens refresh(String refreshToken) {
                        return issuedRefresh(
                                "transient-access", Optional.of("version-n-plus-one"), account, SCOPES);
                    }

                    @Override
                    public AccessTokenMetadata introspectAccessToken(String accessToken) {
                        throw new HubSpotProviderUnavailableException(
                                HubSpotOAuthFailureCategory.TOKEN_INTROSPECTION_PROVIDER_UNAVAILABLE);
                    }
                });

        assertThatThrownBy(() -> provider.accessTokenFor(connection(account)))
                .isInstanceOf(HubSpotProviderUnavailableException.class);

        PlatformConnection current = connection(account);
        assertThat(current.status()).isEqualTo(ConnectionStatus.ACTIVE);
        assertThat(credentialService.load(current).refreshToken()).isEqualTo("version-n-plus-one");
        assertThat(credentialService.load(current).credentialGeneration()).isEqualTo(2);
    }

    @Test
    void staleIntrospectionAfterReplacementCannotInvalidateNewerCredential() {
        String account = uniqueAccount();
        installationStore.finalizeInstallation(account, "generation-n", SCOPES);
        HubSpotAccessTokenProvider provider = new HubSpotAccessTokenProvider(
                credentialService, new StubGateway() {
                    @Override
                    public IssuedRefreshTokens refresh(String refreshToken) {
                        return issuedRefresh(
                                "stale-access", Optional.of("generation-n-plus-one"), account, SCOPES);
                    }

                    @Override
                    public AccessTokenMetadata introspectAccessToken(String accessToken) {
                        installationStore.finalizeInstallation(account, "concurrent-winner", SCOPES);
                        return new AccessTokenMetadata(
                                account, Set.of("crm.objects.deals.read"));
                    }
                });

        assertThatThrownBy(() -> provider.accessTokenFor(connection(account)))
                .isInstanceOf(ConcurrentCredentialChangeException.class);

        PlatformConnection current = connection(account);
        assertThat(current.status()).isEqualTo(ConnectionStatus.ACTIVE);
        assertThat(credentialService.load(current).refreshToken()).isEqualTo("concurrent-winner");
        assertThat(credentialService.load(current).credentialGeneration()).isEqualTo(3);
    }

    @Test
    void staleInvalidGrantAfterDeleteAndReinstallCannotMatchRecreatedCredential() {
        String account = uniqueAccount();
        installationStore.finalizeInstallation(account, "generation-n", SCOPES);
        PlatformConnection staleConnection = connection(account);
        HubSpotAccessTokenProvider provider = new HubSpotAccessTokenProvider(
                credentialService, new StubGateway() {
                    @Override
                    public IssuedRefreshTokens refresh(String refreshToken) {
                        ConnectionCredentialService.LoadedCredential current =
                                credentialService.load(connection(account));
                        credentialService.requireReauthentication(current);
                        installationStore.finalizeInstallation(account, "reinstalled", SCOPES);
                        throw new InvalidRefreshCredentialException();
                    }
                });

        assertThatThrownBy(() -> provider.accessTokenFor(staleConnection))
                .isInstanceOf(ConcurrentCredentialChangeException.class);

        PlatformConnection current = connection(account);
        assertThat(current.status()).isEqualTo(ConnectionStatus.ACTIVE);
        assertThat(credentialService.load(current).refreshToken()).isEqualTo("reinstalled");
        assertThat(credentialService.load(current).credentialGeneration()).isEqualTo(3);
        assertThat(connectionGeneration(current)).isEqualTo(3);
    }

    @Test
    void staleReplacementAfterDeleteAndReinstallCannotReplaceRecreatedCredential() {
        String account = uniqueAccount();
        installationStore.finalizeInstallation(account, "generation-n", SCOPES);
        PlatformConnection staleConnection = connection(account);
        HubSpotAccessTokenProvider provider = new HubSpotAccessTokenProvider(
                credentialService, new StubGateway() {
                    @Override
                    public IssuedRefreshTokens refresh(String refreshToken) {
                        credentialService.requireReauthentication(credentialService.load(connection(account)));
                        installationStore.finalizeInstallation(account, "reinstalled-winner", SCOPES);
                        return issuedRefresh(
                                "stale-access", Optional.of("stale-replacement"), account, SCOPES);
                    }
                });

        assertThatThrownBy(() -> provider.accessTokenFor(staleConnection))
                .isInstanceOf(ConcurrentCredentialChangeException.class);

        PlatformConnection current = connection(account);
        assertThat(current.status()).isEqualTo(ConnectionStatus.ACTIVE);
        assertThat(credentialService.load(current).refreshToken()).isEqualTo("reinstalled-winner");
        assertThat(credentialService.load(current).credentialGeneration()).isEqualTo(3);
    }

    @Test
    void staleScopeLossAfterDeleteAndReinstallCannotInvalidateRecreatedCredential() {
        String account = uniqueAccount();
        installationStore.finalizeInstallation(account, "generation-n", SCOPES);
        PlatformConnection staleConnection = connection(account);
        HubSpotAccessTokenProvider provider = new HubSpotAccessTokenProvider(
                credentialService, new StubGateway() {
                    @Override
                    public IssuedRefreshTokens refresh(String refreshToken) {
                        credentialService.requireReauthentication(credentialService.load(connection(account)));
                        installationStore.finalizeInstallation(account, "reinstalled-winner", SCOPES);
                        return issuedRefresh(
                                "under-scoped", Optional.empty(), account,
                                Set.of("crm.objects.deals.read"));
                    }
                });

        assertThatThrownBy(() -> provider.accessTokenFor(staleConnection))
                .isInstanceOf(ConcurrentCredentialChangeException.class);

        PlatformConnection current = connection(account);
        assertThat(current.status()).isEqualTo(ConnectionStatus.ACTIVE);
        assertThat(credentialService.load(current).refreshToken()).isEqualTo("reinstalled-winner");
        assertThat(credentialService.load(current).credentialGeneration()).isEqualTo(3);
    }

    @Test
    void staleUninstallAfterDeleteAndReinstallCannotDisconnectRecreatedCredential() {
        String account = uniqueAccount();
        HubSpotInstallationStore.FinalizedInstallation installed =
                installationStore.finalizeInstallation(account, "generation-n", SCOPES);
        HubSpotOAuthGateway gateway = new StubGateway() {
            @Override
            public IssuedRefreshTokens refresh(String refreshToken) {
                return issuedRefresh("stale-access", Optional.empty(), account, SCOPES);
            }

            @Override
            public void uninstall(String accessToken) {
                ConnectionCredentialService.LoadedCredential current =
                        credentialService.load(connection(account));
                credentialService.disconnect(current.connectionId(), current.credentialGeneration());
                installationStore.finalizeInstallation(account, "reinstalled-winner", SCOPES);
            }
        };
        HubSpotUninstallService uninstallService = new HubSpotUninstallService(
                connectionService,
                new HubSpotAccessTokenProvider(credentialService, gateway),
                gateway,
                credentialService);

        assertThatThrownBy(() -> uninstallService.uninstall(installed.tenantId(), installed.connectionId()))
                .isInstanceOf(ConcurrentCredentialChangeException.class);

        PlatformConnection current = connection(account);
        assertThat(current.status()).isEqualTo(ConnectionStatus.ACTIVE);
        assertThat(credentialService.load(current).refreshToken()).isEqualTo("reinstalled-winner");
        assertThat(credentialService.load(current).credentialGeneration()).isEqualTo(3);
    }

    @Test
    void staleSuccessfulUninstallCannotDisconnectNewerCredential() {
        String account = uniqueAccount();
        HubSpotInstallationStore.FinalizedInstallation installed =
                installationStore.finalizeInstallation(account, "version-n", SCOPES);
        HubSpotOAuthGateway gateway = new StubGateway() {
            @Override
            public IssuedRefreshTokens refresh(String refreshToken) {
                return issuedRefresh("transient-access", Optional.empty(), account, SCOPES);
            }

            @Override
            public void uninstall(String accessToken) {
                assertThat(accessToken).isEqualTo("transient-access");
                installationStore.finalizeInstallation(account, "version-n-plus-one", SCOPES);
            }
        };
        HubSpotUninstallService uninstallService = new HubSpotUninstallService(
                connectionService,
                new HubSpotAccessTokenProvider(credentialService, gateway),
                gateway,
                credentialService);

        assertThatThrownBy(() -> uninstallService.uninstall(
                installed.tenantId(), installed.connectionId()))
                .isInstanceOf(ConcurrentCredentialChangeException.class);

        PlatformConnection current = connection(account);
        assertThat(current.status()).isEqualTo(ConnectionStatus.ACTIVE);
        assertThat(credentialService.load(current).refreshToken()).isEqualTo("version-n-plus-one");
    }

    @Test
    void successfulUninstallRemovesCredentialAndDisconnectsConnection() {
        String account = uniqueAccount();
        HubSpotInstallationStore.FinalizedInstallation installed =
                installationStore.finalizeInstallation(account, "refresh", SCOPES);
        HubSpotOAuthGateway gateway = new StubGateway() {
            @Override
            public IssuedRefreshTokens refresh(String refreshToken) {
                return issuedRefresh("transient-access", Optional.empty(), account, SCOPES);
            }
        };
        HubSpotUninstallService uninstallService = new HubSpotUninstallService(
                connectionService,
                new HubSpotAccessTokenProvider(credentialService, gateway),
                gateway,
                credentialService);

        uninstallService.uninstall(installed.tenantId(), installed.connectionId());

        assertThat(credentialCount(connection(account))).isZero();
        assertThat(connection(account).status()).isEqualTo(ConnectionStatus.DISCONNECTED);
        assertThat(connectionGeneration(connection(account))).isEqualTo(2);
    }

    @Test
    void failedIntrospectionPreventsProviderUninstall() {
        String account = uniqueAccount();
        HubSpotInstallationStore.FinalizedInstallation installed =
                installationStore.finalizeInstallation(account, "refresh", SCOPES);
        java.util.concurrent.atomic.AtomicInteger uninstallCalls = new java.util.concurrent.atomic.AtomicInteger();
        HubSpotOAuthGateway gateway = new StubGateway() {
            @Override
            public IssuedRefreshTokens refresh(String refreshToken) {
                return issuedRefresh("transient-access", Optional.empty(), account, SCOPES);
            }

            @Override
            public AccessTokenMetadata introspectAccessToken(String accessToken) {
                throw new HubSpotProviderUnavailableException(
                        HubSpotOAuthFailureCategory.TOKEN_INTROSPECTION_PROVIDER_UNAVAILABLE);
            }

            @Override
            public void uninstall(String accessToken) {
                uninstallCalls.incrementAndGet();
            }
        };
        HubSpotUninstallService uninstallService = new HubSpotUninstallService(
                connectionService,
                new HubSpotAccessTokenProvider(credentialService, gateway),
                gateway,
                credentialService);

        assertThatThrownBy(() -> uninstallService.uninstall(
                installed.tenantId(), installed.connectionId()))
                .isInstanceOf(HubSpotProviderUnavailableException.class);

        assertThat(uninstallCalls).hasValue(0);
        assertThat(connection(account).status()).isEqualTo(ConnectionStatus.ACTIVE);
        assertThat(credentialService.load(connection(account)).refreshToken()).isEqualTo("refresh");
    }

    @Test
    void wrongTenantCannotUninstallAnotherTenantsConnection() {
        String account = uniqueAccount();
        HubSpotInstallationStore.FinalizedInstallation installed =
                installationStore.finalizeInstallation(account, "refresh", SCOPES);
        java.util.concurrent.atomic.AtomicInteger providerCalls = new java.util.concurrent.atomic.AtomicInteger();
        HubSpotOAuthGateway gateway = new StubGateway() {
            @Override
            public IssuedRefreshTokens refresh(String refreshToken) {
                providerCalls.incrementAndGet();
                return issuedRefresh("access", Optional.empty(), account, SCOPES);
            }
        };
        HubSpotUninstallService uninstallService = new HubSpotUninstallService(
                connectionService,
                new HubSpotAccessTokenProvider(credentialService, gateway),
                gateway,
                credentialService);

        assertThatThrownBy(() -> uninstallService.uninstall(TenantId.newId(), installed.connectionId()))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(providerCalls).hasValue(0);
        assertThat(connection(account).status()).isEqualTo(ConnectionStatus.ACTIVE);
        assertThat(credentialService.load(connection(account)).refreshToken()).isEqualTo("refresh");
    }

    @Test
    void transientRefreshFailurePreservesCredentialAndActiveState() {
        String account = uniqueAccount();
        installationStore.finalizeInstallation(account, "refresh", SCOPES);
        HubSpotAccessTokenProvider provider = new HubSpotAccessTokenProvider(
                credentialService, new StubGateway() {
                    @Override
                    public IssuedRefreshTokens refresh(String refreshToken) {
                        throw new HubSpotProviderUnavailableException();
                    }
                });

        assertThatThrownBy(() -> provider.accessTokenFor(connection(account)))
                .isInstanceOf(HubSpotProviderUnavailableException.class);

        assertThat(connection(account).status()).isEqualTo(ConnectionStatus.ACTIVE);
        assertThat(credentialService.load(connection(account)).refreshToken()).isEqualTo("refresh");
    }

    @Test
    void concurrentFirstInstallsProduceOneTenantAndConnection() throws Exception {
        String account = uniqueAccount();
        Callable<HubSpotInstallationStore.FinalizedInstallation> install = () ->
                installationStore.finalizeInstallation(account, UUID.randomUUID().toString(), SCOPES);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(install);
            var second = executor.submit(install);
            assertThat(first.get().connectionId()).isEqualTo(second.get().connectionId());
            assertThat(first.get().tenantId()).isEqualTo(second.get().tenantId());
        }
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM platform_connection
                WHERE provider = 'HUBSPOT' AND external_account_id = ?
                """, Long.class, account)).isEqualTo(1);
        PlatformConnection surviving = connection(account);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tenant", Long.class)).isEqualTo(1);
        assertThat(credentialCount(surviving)).isEqualTo(1);
        assertThat(surviving.status()).isEqualTo(ConnectionStatus.ACTIVE);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM tenant_entitlement
                WHERE tenant_id = ? AND product_module = 'LINE_ITEM_WATCH'
                """, Long.class, surviving.tenantId().value())).isEqualTo(1);
    }

    @Test
    void oauthStateConsumptionIsAtomicAcrossConcurrentRequests() throws Exception {
        byte[] hash = new byte[32];
        new java.security.SecureRandom().nextBytes(hash);
        Instant now = Instant.parse("2026-09-23T10:00:00Z");
        stateStore.store(hash, UUID.randomUUID(), now, now.plusSeconds(600));
        Callable<OAuthStateStore.Consumption> consume = () -> stateStore.consume(hash, now.plusSeconds(1));

        java.util.List<OAuthStateStore.Consumption> results;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(consume);
            var second = executor.submit(consume);
            results = java.util.List.of(first.get(), second.get());
        }

        assertThat(results).extracting(OAuthStateStore.Consumption::result).containsExactlyInAnyOrder(
                OAuthStateStore.ConsumptionResult.CONSUMED,
                OAuthStateStore.ConsumptionResult.REPLAYED);
    }

    @Test
    void oauthStateCleanupDeletesAtMostOneBoundedBatchAndPreservesValidRows() {
        Instant now = Instant.parse("2026-09-23T10:00:00Z");
        for (int index = 0; index < 150; index++) {
            byte[] hash = java.nio.ByteBuffer.allocate(32).putInt(index).array();
            stateStore.store(hash, UUID.randomUUID(), now.minusSeconds(172_800), now.minusSeconds(172_000));
        }
        byte[] valid = new byte[32];
        java.util.Arrays.fill(valid, (byte) 127);
        stateStore.store(valid, UUID.randomUUID(), now, now.plusSeconds(600));

        stateStore.deleteRetainedBefore(now.minusSeconds(86_400));

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM oauth_install_state
                WHERE expires_at < ?
                """, Long.class, java.sql.Timestamp.from(now.minusSeconds(86_400)))).isEqualTo(50);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM oauth_install_state WHERE state_hash = ?
                """, Long.class, valid)).isEqualTo(1);
    }

    @Test
    void noPublicUninstallOrDisconnectMappingExists() {
        assertThat(requestMappingHandlerMapping.getHandlerMethods().keySet())
                .flatExtracting(mapping -> mapping.getPatternValues())
                .noneMatch(path -> path.toLowerCase(java.util.Locale.ROOT).contains("uninstall")
                        || path.toLowerCase(java.util.Locale.ROOT).contains("disconnect"));
    }

    private PlatformConnection connection(String account) {
        return connectionService.resolve(Provider.HUBSPOT, new ExternalAccountId(account)).orElseThrow();
    }

    private Long credentialCount(PlatformConnection connection) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM connection_credential WHERE connection_id = ?",
                Long.class,
                connection.id().value());
    }

    private Long connectionGeneration(PlatformConnection connection) {
        return jdbcTemplate.queryForObject(
                "SELECT credential_generation FROM platform_connection WHERE id = ?",
                Long.class,
                connection.id().value());
    }

    private static String uniqueAccount() {
        return "account-" + UUID.randomUUID();
    }

    private abstract static class StubGateway implements HubSpotOAuthGateway {

        private AccessTokenMetadata metadata;

        @Override
        public IssuedAuthorizationTokens exchangeAuthorizationCode(String authorizationCode) {
            throw new UnsupportedOperationException();
        }

        @Override
        public IssuedRefreshTokens refresh(String refreshToken) {
            throw new UnsupportedOperationException();
        }

        final IssuedRefreshTokens issuedRefresh(
                String accessToken,
                Optional<String> replacementRefreshToken,
                String account,
                Set<String> scopes) {
            metadata = new AccessTokenMetadata(account, scopes);
            return new IssuedRefreshTokens(accessToken, replacementRefreshToken);
        }

        @Override
        public AccessTokenMetadata introspectAccessToken(String accessToken) {
            if (metadata == null) {
                throw new IllegalStateException("Test gateway did not prepare token metadata");
            }
            return metadata;
        }

        @Override
        public void revoke(String refreshToken) {
        }

        @Override
        public void uninstall(String accessToken) {
        }
    }
}
