package com.udmconsulting.integrations.hubspot.oauth.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.connection.domain.Provider;
import com.udmconsulting.platform.credential.application.ConnectionCredentialService;
import java.net.URI;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SensitiveValueRenderingTest {

    @Test
    void oauthStateAndAuthorizationUriAreRedacted() {
        String state = "do-not-render-oauth-state";

        assertThat(new OAuthStateService.IssuedState(state, UUID.randomUUID()).toString())
                .doesNotContain(state);
        assertThat(new HubSpotInstallationUseCase.InstallationStart(
                URI.create("https://example.test/authorize?state=" + state), UUID.randomUUID()).toString())
                .doesNotContain(state);
    }

    @Test
    void tokenAndCredentialRecordsAreRedacted() {
        String access = "do-not-render-access-token";
        String refresh = "do-not-render-refresh-token";
        PlatformConnectionId connectionId = PlatformConnectionId.newId();
        Set<String> scopes = Set.of("scope");

        assertThat(new HubSpotOAuthGateway.IssuedAuthorizationTokens(
                access, refresh).toString())
                .doesNotContain(access, refresh);
        assertThat(new HubSpotOAuthGateway.IssuedRefreshTokens(
                access, Optional.of(refresh)).toString())
                .doesNotContain(access, refresh);
        assertThat(new HubSpotAccessTokenProvider.TransientAccessGrant(
                access, connectionId, 3).toString())
                .doesNotContain(access);
        assertThat(new ConnectionCredentialService.LoadedCredential(
                connectionId, Provider.HUBSPOT, refresh, scopes, 3).toString())
                .doesNotContain(refresh);
    }
}
