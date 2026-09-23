package com.udmconsulting.integrations.hubspot.oauth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.udmconsulting.integrations.hubspot.config.HubSpotOAuthProperties;
import com.udmconsulting.platform.connection.domain.ConnectionStatus;
import com.udmconsulting.platform.connection.domain.ExternalAccountId;
import com.udmconsulting.platform.connection.domain.PlatformConnection;
import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.connection.domain.Provider;
import com.udmconsulting.platform.credential.application.ConnectionCredentialService;
import com.udmconsulting.platform.credential.application.ConnectionCredentialStore;
import com.udmconsulting.platform.credential.application.ReauthenticationRequiredException;
import com.udmconsulting.platform.credential.application.SecretContext;
import com.udmconsulting.platform.credential.domain.ConnectionCredential;
import com.udmconsulting.platform.credential.domain.EncryptedSecret;
import com.udmconsulting.platform.credential.infrastructure.crypto.AesGcmSecretProtector;
import com.udmconsulting.platform.tenant.domain.TenantId;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HubSpotAccessTokenProviderTest {

    private static final Set<String> SCOPES = Set.copyOf(HubSpotOAuthProperties.REQUIRED_SCOPES);
    private static final String ACCOUNT_ID = "12345";

    @Test
    void refreshesOnEveryOperationAndNeverCachesAccessTokens() {
        Fixture fixture = new Fixture();
        fixture.gateway.grants = new HubSpotOAuthGateway.RefreshGrant[] {
                new HubSpotOAuthGateway.RefreshGrant("access-one", Optional.empty(), ACCOUNT_ID, SCOPES),
                new HubSpotOAuthGateway.RefreshGrant("access-two", Optional.empty(), ACCOUNT_ID, SCOPES)
        };

        assertThat(fixture.provider.accessTokenFor(fixture.connection).accessToken()).isEqualTo("access-one");
        assertThat(fixture.provider.accessTokenFor(fixture.connection).accessToken()).isEqualTo("access-two");
        assertThat(fixture.gateway.refreshCalls).isEqualTo(2);
    }

    @Test
    void returnsAdvancedOwnedVersionAfterReplacement() {
        Fixture fixture = new Fixture();
        fixture.gateway.grants = new HubSpotOAuthGateway.RefreshGrant[] {
                new HubSpotOAuthGateway.RefreshGrant(
                        "access", Optional.of("replacement"), ACCOUNT_ID, SCOPES)
        };

        HubSpotAccessTokenProvider.TransientAccessGrant result =
                fixture.provider.accessTokenFor(fixture.connection);

        assertThat(result.accessToken()).isEqualTo("access");
        assertThat(result.expectedCredentialGeneration()).isEqualTo(5);
        assertThat(fixture.store.credential.credentialGeneration()).isEqualTo(5);
    }

    @Test
    void confirmedInvalidRefreshMarksExactlyTheLoadedVersionForReauthentication() {
        Fixture fixture = new Fixture();
        fixture.gateway.invalid = true;

        assertThatThrownBy(() -> fixture.provider.accessTokenFor(fixture.connection))
                .isInstanceOf(ReauthenticationRequiredException.class);
        assertThat(fixture.store.credential).isNull();
        assertThat(fixture.store.reauthenticationRequired).isTrue();
    }

    @Test
    void transientRefreshFailurePreservesCredential() {
        Fixture fixture = new Fixture();
        fixture.gateway.unavailable = true;

        assertThatThrownBy(() -> fixture.provider.accessTokenFor(fixture.connection))
                .isInstanceOf(HubSpotProviderUnavailableException.class);

        assertThat(fixture.store.credential).isNotNull();
        assertThat(fixture.store.reauthenticationRequired).isFalse();
    }

    @Test
    void missingRequiredScopeRequiresReauthenticationForOwnedGeneration() {
        Fixture fixture = new Fixture();
        fixture.gateway.grants = new HubSpotOAuthGateway.RefreshGrant[] {
                new HubSpotOAuthGateway.RefreshGrant(
                        "under-scoped", Optional.empty(), ACCOUNT_ID, Set.of("crm.objects.deals.read"))
        };

        assertThatThrownBy(() -> fixture.provider.accessTokenFor(fixture.connection))
                .isInstanceOf(ReauthenticationRequiredException.class);

        assertThat(fixture.store.credential).isNull();
        assertThat(fixture.store.reauthenticationRequired).isTrue();
    }

    @Test
    void mismatchedProviderAccountRequiresReauthentication() {
        Fixture fixture = new Fixture();
        fixture.gateway.grants = new HubSpotOAuthGateway.RefreshGrant[] {
                new HubSpotOAuthGateway.RefreshGrant(
                        "wrong-account-access", Optional.empty(), "different-account", SCOPES)
        };

        assertThatThrownBy(() -> fixture.provider.accessTokenFor(fixture.connection))
                .isInstanceOf(ReauthenticationRequiredException.class);

        assertThat(fixture.store.credential).isNull();
    }

    private static final class Fixture {
        private final PlatformConnection connection = new PlatformConnection(
                PlatformConnectionId.newId(), TenantId.newId(), Provider.HUBSPOT,
                new ExternalAccountId(ACCOUNT_ID), ConnectionStatus.ACTIVE);
        private final AesGcmSecretProtector protector =
                new AesGcmSecretProtector("key-1", new byte[32]);
        private final InMemoryCredentialStore store = new InMemoryCredentialStore();
        private final StubGateway gateway = new StubGateway();
        private final HubSpotAccessTokenProvider provider;

        private Fixture() {
            EncryptedSecret encrypted = protector.protect(
                    "refresh-token", new SecretContext(Provider.HUBSPOT, connection.id()));
            store.credential = new ConnectionCredential(connection.id(), encrypted, SCOPES, 4);
            provider = new HubSpotAccessTokenProvider(
                    new ConnectionCredentialService(store, protector), gateway);
        }
    }

    private static final class InMemoryCredentialStore implements ConnectionCredentialStore {
        private ConnectionCredential credential;
        private boolean reauthenticationRequired;

        @Override
        public Optional<ConnectionCredential> findByConnectionId(PlatformConnectionId connectionId) {
            return Optional.ofNullable(credential);
        }

        @Override
        public boolean replaceIfGeneration(
                PlatformConnectionId connectionId,
                long expectedGeneration,
                EncryptedSecret replacement,
                Set<String> grantedScopes) {
            if (credential == null || credential.credentialGeneration() != expectedGeneration) {
                return false;
            }
            credential = new ConnectionCredential(
                    connectionId, replacement, grantedScopes, expectedGeneration + 1);
            return true;
        }

        @Override
        public boolean requireReauthenticationIfGeneration(
                PlatformConnectionId connectionId, long expectedGeneration) {
            if (credential == null || credential.credentialGeneration() != expectedGeneration) {
                return false;
            }
            credential = null;
            reauthenticationRequired = true;
            return true;
        }

        @Override
        public boolean disconnectIfGeneration(PlatformConnectionId connectionId, long expectedGeneration) {
            return false;
        }
    }

    private static final class StubGateway implements HubSpotOAuthGateway {
        private RefreshGrant[] grants = new RefreshGrant[0];
        private int refreshCalls;
        private boolean invalid;
        private boolean unavailable;

        @Override
        public AuthorizationGrant exchangeAuthorizationCode(String authorizationCode) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RefreshGrant refresh(String refreshToken) {
            if (invalid) {
                throw new InvalidRefreshCredentialException();
            }
            if (unavailable) {
                throw new HubSpotProviderUnavailableException();
            }
            return grants[refreshCalls++];
        }

        @Override
        public void revoke(String refreshToken) {
        }

        @Override
        public void uninstall(String accessToken) {
        }
    }
}
