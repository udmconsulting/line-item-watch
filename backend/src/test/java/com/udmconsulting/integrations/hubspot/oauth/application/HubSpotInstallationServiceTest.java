package com.udmconsulting.integrations.hubspot.oauth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.udmconsulting.integrations.hubspot.config.HubSpotOAuthProperties;
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

class HubSpotInstallationServiceTest {

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
    void consumesStateAndFinalizesInstallation() {
        Fixture fixture = new Fixture();
        var start = fixture.service.beginInstallation();

        var completed = fixture.service.completeInstallation(start.authorizationUri()
                .getQuery().replaceAll(".*state=([^&]+).*", "$1"), "authorization-code");

        assertThat(completed.tenantId()).isEqualTo(fixture.tenantId);
        assertThat(completed.connectionId()).isEqualTo(fixture.connectionId);
        assertThat(fixture.gateway.exchangedCode).isEqualTo("authorization-code");
        assertThat(fixture.store.refreshToken).isEqualTo("new-refresh");
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
        private final java.util.List<String> revoked = new java.util.ArrayList<>();
        private Set<String> scopes = Set.copyOf(HubSpotOAuthProperties.REQUIRED_SCOPES);

        @Override
        public AuthorizationGrant exchangeAuthorizationCode(String authorizationCode) {
            exchangedCode = authorizationCode;
            return new AuthorizationGrant("access", "new-refresh", "12345", scopes);
        }

        @Override
        public RefreshGrant refresh(String refreshToken) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void revoke(String refreshToken) {
            revoked.add(refreshToken);
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
            refreshToken = token;
            return new FinalizedInstallation(tenantId, connectionId, Optional.empty());
        }
    }

    private static final class InMemoryStateStore implements OAuthStateStore {
        private final Map<Bytes, Row> rows = new HashMap<>();

        @Override
        public void store(byte[] stateHash, UUID correlationId, Instant createdAt, Instant expiresAt) {
            rows.put(new Bytes(stateHash), new Row(expiresAt));
        }

        @Override
        public ConsumptionResult consume(byte[] stateHash, Instant consumedAt) {
            Row row = rows.get(new Bytes(stateHash));
            if (row == null) {
                return ConsumptionResult.INVALID;
            }
            if (row.consumed) {
                return ConsumptionResult.REPLAYED;
            }
            if (!row.expiresAt.isAfter(consumedAt)) {
                return ConsumptionResult.EXPIRED;
            }
            row.consumed = true;
            return ConsumptionResult.CONSUMED;
        }

        @Override
        public void deleteRetainedBefore(Instant retentionThreshold) {
        }
    }

    private static final class Row {
        private final Instant expiresAt;
        private boolean consumed;

        private Row(Instant expiresAt) {
            this.expiresAt = expiresAt;
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
