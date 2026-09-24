package com.udmconsulting.integrations.hubspot.oauth.application;

import com.udmconsulting.integrations.hubspot.config.HubSpotOAuthProperties;
import com.udmconsulting.platform.connection.domain.PlatformConnection;
import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.credential.application.ConnectionCredentialService;
import com.udmconsulting.platform.credential.application.ConnectionCredentialService.LoadedCredential;
import com.udmconsulting.platform.credential.application.ReauthenticationRequiredException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public final class HubSpotAccessTokenProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(HubSpotAccessTokenProvider.class);

    private final ConnectionCredentialService credentialService;
    private final HubSpotOAuthGateway oauthGateway;

    public HubSpotAccessTokenProvider(
            ConnectionCredentialService credentialService, HubSpotOAuthGateway oauthGateway) {
        this.credentialService = credentialService;
        this.oauthGateway = oauthGateway;
    }

    public TransientAccessGrant accessTokenFor(PlatformConnection connection) {
        LoadedCredential loaded = credentialService.load(connection);
        HubSpotOAuthGateway.IssuedRefreshTokens issuedTokens;
        try {
            issuedTokens = oauthGateway.refresh(loaded.refreshToken());
        } catch (InvalidRefreshCredentialException exception) {
            logFailure(connection.id(), exception.category());
            credentialService.requireReauthentication(loaded);
            throw new ReauthenticationRequiredException();
        } catch (HubSpotOAuthException exception) {
            logFailure(connection.id(), exception.category());
            throw exception;
        }
        long expectedGeneration = loaded.credentialGeneration();
        if (issuedTokens.replacementRefreshToken().isPresent()) {
            expectedGeneration = credentialService.replace(
                    loaded,
                    issuedTokens.replacementRefreshToken().orElseThrow(),
                    loaded.grantedScopes());
        }

        HubSpotOAuthGateway.AccessTokenMetadata metadata;
        try {
            metadata = oauthGateway.introspectAccessToken(issuedTokens.accessToken());
        } catch (HubSpotInactiveAccessTokenException exception) {
            logFailure(connection.id(), exception.category());
            credentialService.requireReauthentication(connection.id(), expectedGeneration);
            throw new ReauthenticationRequiredException();
        } catch (HubSpotOAuthException exception) {
            logFailure(connection.id(), exception.category());
            throw exception;
        }
        if (!connection.externalAccountId().value().equals(metadata.externalAccountId())) {
            logFailure(connection.id(), HubSpotOAuthFailureCategory.ACCOUNT_IDENTITY_MISMATCH);
            credentialService.requireReauthentication(connection.id(), expectedGeneration);
            throw new ReauthenticationRequiredException();
        }
        if (!metadata.grantedScopes().containsAll(HubSpotOAuthProperties.REQUIRED_SCOPES)) {
            logFailure(connection.id(), HubSpotOAuthFailureCategory.REQUIRED_SCOPE_MISSING);
            credentialService.requireReauthentication(connection.id(), expectedGeneration);
            throw new ReauthenticationRequiredException();
        }
        return new TransientAccessGrant(
                issuedTokens.accessToken(), connection.id(), expectedGeneration);
    }

    private static void logFailure(
            PlatformConnectionId connectionId, HubSpotOAuthFailureCategory category) {
        LOGGER.warn("HubSpot access-token operation failed connectionId={} category={}",
                connectionId.value(), category);
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
