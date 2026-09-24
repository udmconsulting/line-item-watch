package com.udmconsulting.integrations.hubspot.oauth.application;

import com.udmconsulting.integrations.hubspot.config.HubSpotOAuthProperties;
import com.udmconsulting.platform.connection.domain.Provider;
import com.udmconsulting.platform.credential.application.SecretContext;
import com.udmconsulting.platform.credential.application.SecretProtector;
import com.udmconsulting.platform.credential.domain.ConnectionCredential;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public final class HubSpotInstallationService implements HubSpotInstallationUseCase {

    private static final Logger LOGGER = LoggerFactory.getLogger(HubSpotInstallationService.class);

    private final OAuthStateService stateService;
    private final HubSpotOAuthGateway oauthGateway;
    private final HubSpotInstallationStore installationStore;
    private final SecretProtector secretProtector;
    private final HubSpotOAuthProperties properties;

    public HubSpotInstallationService(
            OAuthStateService stateService,
            HubSpotOAuthGateway oauthGateway,
            HubSpotInstallationStore installationStore,
            SecretProtector secretProtector,
            HubSpotOAuthProperties properties) {
        this.stateService = Objects.requireNonNull(stateService);
        this.oauthGateway = Objects.requireNonNull(oauthGateway);
        this.installationStore = Objects.requireNonNull(installationStore);
        this.secretProtector = Objects.requireNonNull(secretProtector);
        this.properties = Objects.requireNonNull(properties);
    }

    @Override
    public InstallationStart beginInstallation() {
        OAuthStateService.IssuedState state = stateService.issue();
        var authorizationUri = UriComponentsBuilder.fromUri(properties.authorizationBaseUrl())
                .queryParam("client_id", properties.clientId())
                .queryParam("redirect_uri", properties.redirectUri())
                .queryParam("scope", String.join(" ", HubSpotOAuthProperties.REQUIRED_SCOPES))
                .queryParam("state", state.value())
                .build()
                .encode()
                .toUri();
        return new InstallationStart(authorizationUri, state.correlationId());
    }

    @Override
    public CompletedInstallation completeInstallation(String state, String authorizationCode) {
        UUID correlationId = stateService.consume(state);
        if (authorizationCode == null || authorizationCode.isBlank()) {
            logFailure(correlationId, HubSpotOAuthFailureCategory.TOKEN_EXCHANGE_REJECTED);
            throw new HubSpotAuthorizationException();
        }
        HubSpotOAuthGateway.IssuedAuthorizationTokens issuedTokens;
        try {
            issuedTokens = oauthGateway.exchangeAuthorizationCode(authorizationCode);
        } catch (RuntimeException exception) {
            logFailure(correlationId, failureCategory(
                    exception, HubSpotOAuthFailureCategory.TOKEN_EXCHANGE_REJECTED));
            throw exception;
        }

        try {
            HubSpotOAuthGateway.AccessTokenMetadata metadata =
                    oauthGateway.introspectAccessToken(issuedTokens.accessToken());
            if (!metadata.grantedScopes().containsAll(HubSpotOAuthProperties.REQUIRED_SCOPES)) {
                throw new HubSpotInsufficientScopeException();
            }
            HubSpotInstallationStore.FinalizedInstallation finalized =
                    installationStore.finalizeInstallation(
                            metadata.externalAccountId(),
                            issuedTokens.refreshToken(),
                            metadata.grantedScopes());
            finalized.supersededCredential().ifPresent(prior -> revokeSupersededBestEffort(
                    prior, issuedTokens.refreshToken(), finalized.connectionId()));
            LOGGER.info(
                    "HubSpot installation completed correlationId={} tenantId={} connectionId={} "
                            + "externalAccountId={}",
                    correlationId,
                    finalized.tenantId().value(),
                    finalized.connectionId().value(),
                    metadata.externalAccountId());
            return new CompletedInstallation(finalized.tenantId(), finalized.connectionId());
        } catch (RuntimeException exception) {
            revokeBestEffort(issuedTokens.refreshToken(), "new credential after post-issuance failure");
            logFailure(correlationId, failureCategory(
                    exception, HubSpotOAuthFailureCategory.LOCAL_FINALIZATION_FAILED));
            throw exception;
        }
    }

    @Override
    public void rejectInstallation(String state) {
        stateService.consume(state);
        throw new HubSpotAuthorizationException();
    }

    private void revokeSupersededBestEffort(
            ConnectionCredential prior,
            String activeRefreshToken,
            com.udmconsulting.platform.connection.domain.PlatformConnectionId connectionId) {
        try {
            String superseded = secretProtector.reveal(
                    prior.refreshCredential(), new SecretContext(Provider.HUBSPOT, connectionId));
            if (!MessageDigest.isEqual(
                    superseded.getBytes(StandardCharsets.UTF_8),
                    activeRefreshToken.getBytes(StandardCharsets.UTF_8))) {
                revokeBestEffort(superseded, "superseded credential");
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("Could not process superseded HubSpot credential for best-effort revocation; "
                    + "operator follow-up may be required");
        }
    }

    private void revokeBestEffort(String refreshToken, String reason) {
        try {
            oauthGateway.revoke(refreshToken);
        } catch (RuntimeException exception) {
            LOGGER.warn("HubSpot token revocation failed for {}; operator follow-up may be required", reason);
        }
    }

    private static HubSpotOAuthFailureCategory failureCategory(
            RuntimeException exception, HubSpotOAuthFailureCategory fallback) {
        return exception instanceof HubSpotOAuthException oauthException
                ? oauthException.category() : fallback;
    }

    private static void logFailure(UUID correlationId, HubSpotOAuthFailureCategory category) {
        LOGGER.warn("HubSpot installation failed correlationId={} category={}", correlationId, category);
    }

}
