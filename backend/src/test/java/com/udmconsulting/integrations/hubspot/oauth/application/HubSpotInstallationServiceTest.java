package com.udmconsulting.integrations.hubspot.oauth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.udmconsulting.integrations.hubspot.config.HubSpotOAuthProperties;
import com.udmconsulting.integrations.hubspot.oauth.infrastructure.http.RestHubSpotOAuthGateway;
import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.credential.infrastructure.crypto.AesGcmSecretProtector;
import com.udmconsulting.platform.tenant.domain.TenantId;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class HubSpotInstallationServiceTest {

    @Test
    void current202609ContractFinalizesWhenIssuanceOmitsHubIdAndScopes() {
        InMemoryStateStore states = new InMemoryStateStore();
        OAuthStateService stateService = new OAuthStateService(
                states,
                Clock.fixed(Instant.parse("2026-09-23T10:00:00Z"), ZoneOffset.UTC),
                new java.security.SecureRandom());
        HubSpotOAuthProperties properties = new HubSpotOAuthProperties(
                "client-id",
                "client-secret",
                URI.create("http://localhost:8080/integrations/hubspot/oauth/callback"),
                URI.create("https://api.hubspot.test"),
                URI.create("https://app.hubspot.test/oauth/authorize"),
                null,
                null);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestHubSpotOAuthGateway gateway =
                new RestHubSpotOAuthGateway(builder, new JsonMapper(), properties);
        TenantId tenantId = TenantId.newId();
        PlatformConnectionId connectionId = PlatformConnectionId.newId();
        StubInstallationStore store = new StubInstallationStore(tenantId, connectionId);
        HubSpotInstallationService service = new HubSpotInstallationService(
                stateService,
                gateway,
                store,
                new AesGcmSecretProtector("key-1", new byte[32]),
                properties);
        OAuthStateService.IssuedState state = stateService.issue();
        server.expect(requestTo("https://api.hubspot.test/oauth/2026-09/token"))
                .andRespond(withSuccess("""
                        {"access_token":"transient","refresh_token":"new-refresh","expires_in":1800,
                         "token_type":"bearer","token_use":"access_token"}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.hubspot.test/oauth/2026-09/token/introspect"))
                .andRespond(withSuccess("""
                        {"active":true,"token_use":"access_token","token_type":"bearer",
                         "client_id":"client-id","hub_id":12345,
                         "scopes":["crm.objects.deals.read","crm.objects.line_items.read"]}
                        """, MediaType.APPLICATION_JSON));

        HubSpotInstallationUseCase.CompletedInstallation completed =
                service.completeInstallation(state.value(), "authorization-code");

        assertThat(completed.tenantId()).isEqualTo(tenantId);
        assertThat(completed.connectionId()).isEqualTo(connectionId);
        assertThat(store.externalAccountId).isEqualTo("12345");
        assertThat(store.refreshToken).isEqualTo("new-refresh");
        assertThat(store.grantedScopes).containsExactlyInAnyOrderElementsOf(
                HubSpotOAuthProperties.REQUIRED_SCOPES);
        server.verify();
    }

    @Test
    void beginsInstallWithPersistedStateAndLeastPrivilegeScopes() {
        Fixture fixture = new Fixture();

        HubSpotInstallationUseCase.InstallationStart start = fixture.service.beginInstallation();

        assertThat(fixture.states.rows).hasSize(1);
        assertThat(start.authorizationUri().toString())
                .startsWith("https://app.hubspot.test/oauth/authorize?")
                .contains("client_id=client-id")
                .contains("redirect_uri=http://localhost:8080/integrations/hubspot/oauth/callback")
                .contains("crm.objects.deals.read")
                .contains("crm.objects.line_items.read")
                .contains("state=")
                .doesNotContain("scope=oauth");
        assertThat(start.authorizationUri().getQuery()).contains("state=");
    }

    @Test
    void liveDefectRegressionUsesIntrospectionForIdentityAndScopesBeforeFinalization() {
        Fixture fixture = new Fixture();
        var start = fixture.service.beginInstallation();

        var completed = fixture.service.completeInstallation(start.authorizationUri()
                .getQuery().replaceAll(".*state=([^&]+).*", "$1"), "authorization-code");

        assertThat(completed.tenantId()).isEqualTo(fixture.tenantId);
        assertThat(completed.connectionId()).isEqualTo(fixture.connectionId);
        assertThat(fixture.gateway.exchangedCode).isEqualTo("authorization-code");
        assertThat(fixture.gateway.introspectedAccessToken).isEqualTo("access");
        assertThat(fixture.store.refreshToken).isEqualTo("new-refresh");
        assertThat(fixture.store.externalAccountId).isEqualTo("12345");
        assertThat(fixture.store.grantedScopes).containsAll(HubSpotOAuthProperties.REQUIRED_SCOPES);
    }

    @Test
    void revokesNewCredentialBestEffortWhenLocalFinalizationFails() {
        Fixture fixture = new Fixture();
        fixture.store.failure = new IllegalStateException("database failure");
        OAuthStateService.IssuedState state = fixture.stateService.issue();

        assertThatThrownBy(() -> fixture.service.completeInstallation(state.value(), "authorization-code"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database failure");
        assertThat(fixture.gateway.revoked).containsExactly("new-refresh");
    }

    @Test
    void missingRequiredScopeDoesNotActivateAndRevokesIssuedCredential() {
        Fixture fixture = new Fixture();
        fixture.gateway.scopes = Set.of("crm.objects.deals.read");
        OAuthStateService.IssuedState state = fixture.stateService.issue();

        assertThatThrownBy(() -> fixture.service.completeInstallation(state.value(), "authorization-code"))
                .isInstanceOf(HubSpotInsufficientScopeException.class)
                .hasMessageNotContaining("new-refresh");

        assertThat(fixture.store.refreshToken).isNull();
        assertThat(fixture.gateway.revoked).containsExactly("new-refresh");
    }

    @Test
    void introspectionFailureDoesNotFinalizeAndRevokesIssuedCredentialBestEffort() {
        Fixture fixture = new Fixture();
        fixture.gateway.introspectionFailure = new HubSpotTokenMetadataException();
        OAuthStateService.IssuedState state = fixture.stateService.issue();

        assertThatThrownBy(() -> fixture.service.completeInstallation(state.value(), "authorization-code"))
                .isInstanceOf(HubSpotTokenMetadataException.class);

        assertThat(fixture.store.refreshToken).isNull();
        assertThat(fixture.gateway.revoked).containsExactly("new-refresh");
    }

    @Test
    void introspectionProviderFailureDoesNotFinalizeAndPreservesPrimaryFailureWhenRevocationFails() {
        Fixture fixture = new Fixture();
        fixture.gateway.introspectionFailure = new HubSpotProviderUnavailableException(
                HubSpotOAuthFailureCategory.TOKEN_INTROSPECTION_PROVIDER_UNAVAILABLE);
        fixture.gateway.revocationFailure = new HubSpotProviderUnavailableException();
        OAuthStateService.IssuedState state = fixture.stateService.issue();

        assertThatThrownBy(() -> fixture.service.completeInstallation(state.value(), "authorization-code"))
                .isSameAs(fixture.gateway.introspectionFailure);

        assertThat(fixture.store.refreshToken).isNull();
        assertThat(fixture.gateway.revoked).containsExactly("new-refresh");
    }

    private static final class Fixture {
        private final InMemoryStateStore states = new InMemoryStateStore();
        private final OAuthStateService stateService = new OAuthStateService(
                states,
                Clock.fixed(Instant.parse("2026-09-23T10:00:00Z"), ZoneOffset.UTC),
                new java.security.SecureRandom());
        private final StubGateway gateway = new StubGateway();
        private final TenantId tenantId = TenantId.newId();
        private final PlatformConnectionId connectionId = PlatformConnectionId.newId();
        private final StubInstallationStore store = new StubInstallationStore(tenantId, connectionId);
        private final HubSpotInstallationService service = new HubSpotInstallationService(
                stateService,
                gateway,
                store,
                new AesGcmSecretProtector("key-1", new byte[32]),
                new HubSpotOAuthProperties(
                        "client-id",
                        "client-secret",
                        URI.create("http://localhost:8080/integrations/hubspot/oauth/callback"),
                        URI.create("https://api.hubspot.test"),
                        URI.create("https://app.hubspot.test/oauth/authorize"),
                        null,
                        null));
    }

    private static final class StubGateway implements HubSpotOAuthGateway {
        private String exchangedCode;
        private String introspectedAccessToken;
        private final java.util.List<String> revoked = new java.util.ArrayList<>();
        private Set<String> scopes = Set.copyOf(HubSpotOAuthProperties.REQUIRED_SCOPES);
        private RuntimeException introspectionFailure;
        private RuntimeException revocationFailure;

        @Override
        public IssuedAuthorizationTokens exchangeAuthorizationCode(String authorizationCode) {
            exchangedCode = authorizationCode;
            return new IssuedAuthorizationTokens("access", "new-refresh");
        }

        @Override
        public IssuedRefreshTokens refresh(String refreshToken) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AccessTokenMetadata introspectAccessToken(String accessToken) {
            introspectedAccessToken = accessToken;
            if (introspectionFailure != null) {
                throw introspectionFailure;
            }
            return new AccessTokenMetadata("12345", scopes);
        }

        @Override
        public void revoke(String refreshToken) {
            revoked.add(refreshToken);
            if (revocationFailure != null) {
                throw revocationFailure;
            }
        }

        @Override
        public void uninstall(String accessToken) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class StubInstallationStore implements HubSpotInstallationStore {
        private final TenantId tenantId;
        private final PlatformConnectionId connectionId;
        private String refreshToken;
        private String externalAccountId;
        private Set<String> grantedScopes;
        private RuntimeException failure;

        private StubInstallationStore(TenantId tenantId, PlatformConnectionId connectionId) {
            this.tenantId = tenantId;
            this.connectionId = connectionId;
        }

        @Override
        public FinalizedInstallation finalizeInstallation(
                String externalAccountId, String token, Set<String> grantedScopes) {
            if (failure != null) {
                throw failure;
            }
            this.externalAccountId = externalAccountId;
            this.grantedScopes = Set.copyOf(grantedScopes);
            refreshToken = token;
            return new FinalizedInstallation(tenantId, connectionId, Optional.empty());
        }
    }

    private static final class InMemoryStateStore implements OAuthStateStore {
        private final Map<Bytes, Row> rows = new HashMap<>();

        @Override
        public void store(byte[] stateHash, UUID correlationId, Instant createdAt, Instant expiresAt) {
            rows.put(new Bytes(stateHash), new Row(expiresAt, correlationId));
        }

        @Override
        public Consumption consume(byte[] stateHash, Instant consumedAt) {
            Row row = rows.get(new Bytes(stateHash));
            if (row == null) {
                return new Consumption(ConsumptionResult.INVALID, null);
            }
            if (row.consumed) {
                return new Consumption(ConsumptionResult.REPLAYED, row.correlationId);
            }
            if (!row.expiresAt.isAfter(consumedAt)) {
                return new Consumption(ConsumptionResult.EXPIRED, row.correlationId);
            }
            row.consumed = true;
            return new Consumption(ConsumptionResult.CONSUMED, row.correlationId);
        }

        @Override
        public void deleteRetainedBefore(Instant retentionThreshold) {
        }
    }

    private static final class Row {
        private final Instant expiresAt;
        private final UUID correlationId;
        private boolean consumed;

        private Row(Instant expiresAt, UUID correlationId) {
            this.expiresAt = expiresAt;
            this.correlationId = correlationId;
        }
    }

    private record Bytes(byte[] value) {
        private Bytes {
            value = value.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Bytes that && Arrays.equals(value, that.value);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(value);
        }
    }
}
