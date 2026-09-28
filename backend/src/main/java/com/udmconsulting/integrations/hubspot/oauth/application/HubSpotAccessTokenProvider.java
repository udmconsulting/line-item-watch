package com.udmconsulting.integrations.hubspot.oauth.application;

import com.udmconsulting.integrations.hubspot.config.HubSpotOAuthProperties;
import com.udmconsulting.platform.connection.domain.PlatformConnection;
import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.credential.application.ConnectionCredentialService;
import com.udmconsulting.platform.credential.application.ConnectionCredentialService.LoadedCredential;
import com.udmconsulting.platform.credential.application.ReauthenticationRequiredException;
import com.udmconsulting.platform.supportability.OperationalErrorCode;
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
        LOGGER.atWarn()
                .addKeyValue("component", "hubspot_oauth")
                .addKeyValue("operation", "access_token")
                .addKeyValue("result", "FAILED")
                .addKeyValue("errorCode", operationalError(category).name())
                .addKeyValue("providerErrorCode", category.name())
                .addKeyValue("connectionRef", connectionId.value())
                .log("HubSpot access-token operation failed");
    }

    private static OperationalErrorCode operationalError(HubSpotOAuthFailureCategory category) {
        return switch (category) {
            case TOKEN_EXCHANGE_PROVIDER_UNAVAILABLE, TOKEN_INTROSPECTION_PROVIDER_UNAVAILABLE ->
                    OperationalErrorCode.PROVIDER_UNAVAILABLE;
            case TOKEN_EXCHANGE_REJECTED, TOKEN_INTROSPECTION_REJECTED,
                    ACCOUNT_IDENTITY_MISMATCH, REQUIRED_SCOPE_MISSING ->
                    OperationalErrorCode.PROVIDER_AUTH_REQUIRED;
            default -> OperationalErrorCode.INTERNAL_ERROR;
        };
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
