package com.udmconsulting.integrations.hubspot.oauth.application;

import com.udmconsulting.integrations.hubspot.config.HubSpotOAuthProperties;
import com.udmconsulting.platform.connection.domain.PlatformConnection;
import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.credential.application.ConnectionCredentialService;
import com.udmconsulting.platform.credential.application.ConnectionCredentialService.LoadedCredential;
import com.udmconsulting.platform.credential.application.ReauthenticationRequiredException;
import org.springframework.stereotype.Service;

@Service
public final class HubSpotAccessTokenProvider {

    private final ConnectionCredentialService credentialService;
    private final HubSpotOAuthGateway oauthGateway;

    public HubSpotAccessTokenProvider(
            ConnectionCredentialService credentialService, HubSpotOAuthGateway oauthGateway) {
        this.credentialService = credentialService;
        this.oauthGateway = oauthGateway;
    }

    public TransientAccessGrant accessTokenFor(PlatformConnection connection) {
        LoadedCredential loaded = credentialService.load(connection);
        HubSpotOAuthGateway.RefreshGrant refreshGrant;
        try {
            refreshGrant = oauthGateway.refresh(loaded.refreshToken());
        } catch (InvalidRefreshCredentialException exception) {
            credentialService.requireReauthentication(loaded);
            throw new ReauthenticationRequiredException();
        }
        if (!connection.externalAccountId().value().equals(refreshGrant.externalAccountId())
                || !refreshGrant.grantedScopes().containsAll(HubSpotOAuthProperties.REQUIRED_SCOPES)) {
            credentialService.requireReauthentication(loaded);
            throw new ReauthenticationRequiredException();
        }
        long expectedGeneration = loaded.credentialGeneration();
        if (refreshGrant.replacementRefreshToken().isPresent()) {
            expectedGeneration = credentialService.replace(
                    loaded,
                    refreshGrant.replacementRefreshToken().orElseThrow(),
                    refreshGrant.grantedScopes());
        }
        return new TransientAccessGrant(
                refreshGrant.accessToken(), connection.id(), expectedGeneration);
    }

    public record TransientAccessGrant(
            String accessToken, PlatformConnectionId connectionId, long expectedCredentialGeneration) {

        @Override
        public String toString() {
            return "TransientAccessGrant[connectionId=" + connectionId
                    + ", expectedCredentialGeneration=" + expectedCredentialGeneration
                    + ", accessToken=<redacted>]";
        }
    }
}
