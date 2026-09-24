package com.udmconsulting.integrations.hubspot.oauth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.udmconsulting.integrations.hubspot.config.HubSpotOAuthProperties;
import com.udmconsulting.platform.connection.domain.ConnectionStatus;
import com.udmconsulting.platform.connection.domain.ExternalAccountId;
import com.udmconsulting.platform.connection.domain.PlatformConnection;
import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.connection.domain.Provider;
import com.udmconsulting.platform.credential.application.ConcurrentCredentialChangeException;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class HubSpotAccessTokenProviderTest {

    private static final Set<String> SCOPES = Set.copyOf(HubSpotOAuthProperties.REQUIRED_SCOPES);
    private static final String ACCOUNT_ID = "12345";

    @Test
    void refreshesAndIntrospectsOnEveryOperationWithoutCaching() {
        Fixture fixture = new Fixture();
        fixture.gateway.issued = new HubSpotOAuthGateway.IssuedRefreshTokens[] {
                issued("access-one"), issued("access-two")
        };

        assertThat(fixture.provider.accessTokenFor(fixture.connection).accessToken()).isEqualTo("access-one");
        assertThat(fixture.provider.accessTokenFor(fixture.connection).accessToken()).isEqualTo("access-two");
        assertThat(fixture.gateway.refreshCalls).isEqualTo(2);
        assertThat(fixture.gateway.introspectionCalls).isEqualTo(2);
    }

    @Test
    void persistsReplacementBeforeIntrospectionAndReturnsAdvancedGeneration() {
        Fixture fixture = new Fixture();
        fixture.gateway.issued = new HubSpotOAuthGateway.IssuedRefreshTokens[] {
                new HubSpotOAuthGateway.IssuedRefreshTokens("access", Optional.of("replacement"))
        };
        fixture.gateway.beforeIntrospection = () ->
                assertThat(fixture.store.credential.credentialGeneration()).isEqualTo(5);

        HubSpotAccessTokenProvider.TransientAccessGrant result =
                fixture.provider.accessTokenFor(fixture.connection);

        assertThat(result.accessToken()).isEqualTo("access");
        assertThat(result.expectedCredentialGeneration()).isEqualTo(5);
        assertThat(fixture.store.credential.credentialGeneration()).isEqualTo(5);
    }

    @Test
    void replacementIsPreservedWhenSubsequentIntrospectionIsTransientlyUnavailable() {
        Fixture fixture = new Fixture();
        fixture.gateway.issued = new HubSpotOAuthGateway.IssuedRefreshTokens[] {
                new HubSpotOAuthGateway.IssuedRefreshTokens("access", Optional.of("replacement"))
        };
        fixture.gateway.introspectionFailure = new HubSpotProviderUnavailableException(
                HubSpotOAuthFailureCategory.TOKEN_INTROSPECTION_PROVIDER_UNAVAILABLE);

        assertThatThrownBy(() -> fixture.provider.accessTokenFor(fixture.connection))
                .isInstanceOf(HubSpotProviderUnavailableException.class);

        assertThat(fixture.store.credential).isNotNull();
        assertThat(fixture.store.credential.credentialGeneration()).isEqualTo(5);
        assertThat(fixture.store.reauthenticationRequired).isFalse();
    }

    @Test
    void replacementCasConflictSkipsIntrospection() {
        Fixture fixture = new Fixture();
        fixture.store.rejectReplacement = true;
        fixture.gateway.issued = new HubSpotOAuthGateway.IssuedRefreshTokens[] {
                new HubSpotOAuthGateway.IssuedRefreshTokens("stale-access", Optional.of("replacement"))
        };

        assertThatThrownBy(() -> fixture.provider.accessTokenFor(fixture.connection))
                .isInstanceOf(ConcurrentCredentialChangeException.class);

        assertThat(fixture.gateway.introspectionCalls).isZero();
        assertThat(fixture.store.credential.credentialGeneration()).isEqualTo(4);
    }

    @Test
    void confirmedInvalidRefreshMarksExactlyTheLoadedGenerationForReauthentication() {
        Fixture fixture = new Fixture();
        fixture.gateway.refreshFailure = new InvalidRefreshCredentialException();

        assertThatThrownBy(() -> fixture.provider.accessTokenFor(fixture.connection))
                .isInstanceOf(ReauthenticationRequiredException.class);
        assertThat(fixture.store.credential).isNull();
        assertThat(fixture.store.reauthenticationRequired).isTrue();
        assertThat(fixture.gateway.introspectionCalls).isZero();
    }

    @Test
    void transientTokenExchangeFailurePreservesCredential() {
        Fixture fixture = new Fixture();
        fixture.gateway.refreshFailure = new HubSpotProviderUnavailableException();

        assertThatThrownBy(() -> fixture.provider.accessTokenFor(fixture.connection))
                .isInstanceOf(HubSpotProviderUnavailableException.class);

        assertThat(fixture.store.credential).isNotNull();
        assertThat(fixture.store.reauthenticationRequired).isFalse();
    }

    @Test
    void inactiveTokenRequiresReauthenticationForCurrentPostReplacementGeneration() {
        Fixture fixture = new Fixture();
        fixture.gateway.issued = new HubSpotOAuthGateway.IssuedRefreshTokens[] {
                new HubSpotOAuthGateway.IssuedRefreshTokens("inactive", Optional.of("replacement"))
        };
        fixture.gateway.introspectionFailure = new HubSpotInactiveAccessTokenException();

        assertThatThrownBy(() -> fixture.provider.accessTokenFor(fixture.connection))
                .isInstanceOf(ReauthenticationRequiredException.class);

        assertThat(fixture.store.lastReauthenticationGeneration).isEqualTo(5);
        assertThat(fixture.store.credential).isNull();
    }

    @Test
    void missingRequiredScopeCannotProduceAccessAndUsesSanitizedDiagnostic(CapturedOutput output) {
        Fixture fixture = new Fixture();
        fixture.gateway.issued = new HubSpotOAuthGateway.IssuedRefreshTokens[] {
                issued("sensitive-under-scoped-access")
        };
        fixture.gateway.metadata = new HubSpotOAuthGateway.AccessTokenMetadata(
                ACCOUNT_ID, Set.of("crm.objects.deals.read"));

        assertThatThrownBy(() -> fixture.provider.accessTokenFor(fixture.connection))
                .isInstanceOf(ReauthenticationRequiredException.class);

        assertThat(fixture.store.credential).isNull();
        assertThat(fixture.store.reauthenticationRequired).isTrue();
        assertThat(output).contains("category=REQUIRED_SCOPE_MISSING")
                .doesNotContain("sensitive-under-scoped-access", "refresh-token");
    }

    @Test
    void mismatchedProviderAccountCannotProduceAccessAndUsesSanitizedDiagnostic(
            CapturedOutput output) {
        Fixture fixture = new Fixture();
        fixture.gateway.issued = new HubSpotOAuthGateway.IssuedRefreshTokens[] {
                issued("sensitive-wrong-account-access")
        };
        fixture.gateway.metadata = new HubSpotOAuthGateway.AccessTokenMetadata(
                "different-account", SCOPES);

        assertThatThrownBy(() -> fixture.provider.accessTokenFor(fixture.connection))
                .isInstanceOf(ReauthenticationRequiredException.class);

        assertThat(fixture.store.credential).isNull();
        assertThat(output).contains("category=ACCOUNT_IDENTITY_MISMATCH")
                .doesNotContain("sensitive-wrong-account-access", "refresh-token");
    }

    @Test
    void structurallyInvalidMetadataPreservesCredentialConservatively() {
        Fixture fixture = new Fixture();
        fixture.gateway.introspectionFailure = new HubSpotTokenMetadataException();

        assertThatThrownBy(() -> fixture.provider.accessTokenFor(fixture.connection))
                .isInstanceOf(HubSpotTokenMetadataException.class);

        assertThat(fixture.store.credential).isNotNull();
        assertThat(fixture.store.reauthenticationRequired).isFalse();
    }

    private static HubSpotOAuthGateway.IssuedRefreshTokens issued(String accessToken) {
        return new HubSpotOAuthGateway.IssuedRefreshTokens(accessToken, Optional.empty());
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
        private boolean rejectReplacement;
        private long lastReauthenticationGeneration = -1;

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
            if (rejectReplacement || credential == null
                    || credential.credentialGeneration() != expectedGeneration) {
                return false;
            }
            credential = new ConnectionCredential(
                    connectionId, replacement, grantedScopes, expectedGeneration + 1);
            return true;
        }

        @Override
        public boolean requireReauthenticationIfGeneration(
                PlatformConnectionId connectionId, long expectedGeneration) {
            lastReauthenticationGeneration = expectedGeneration;
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
        private IssuedRefreshTokens[] issued = new IssuedRefreshTokens[] {issued("access")};
        private AccessTokenMetadata metadata = new AccessTokenMetadata(ACCOUNT_ID, SCOPES);
        private RuntimeException refreshFailure;
        private RuntimeException introspectionFailure;
        private Runnable beforeIntrospection = () -> { };
        private int refreshCalls;
        private int introspectionCalls;

        @Override
        public IssuedAuthorizationTokens exchangeAuthorizationCode(String authorizationCode) {
            throw new UnsupportedOperationException();
        }

        @Override
        public IssuedRefreshTokens refresh(String refreshToken) {
            if (refreshFailure != null) {
                throw refreshFailure;
            }
            return issued[refreshCalls++];
        }

        @Override
        public AccessTokenMetadata introspectAccessToken(String accessToken) {
            introspectionCalls++;
            beforeIntrospection.run();
            if (introspectionFailure != null) {
                throw introspectionFailure;
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
