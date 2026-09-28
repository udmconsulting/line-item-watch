package com.udmconsulting.integrations.hubspot.oauth.application;

import com.udmconsulting.integrations.hubspot.config.HubSpotOAuthProperties;
import com.udmconsulting.platform.activity.application.ActivityContext;
import com.udmconsulting.platform.activity.domain.ActivityActor;
import com.udmconsulting.platform.activity.domain.ActivityActorSource;
import com.udmconsulting.platform.connection.domain.Provider;
import com.udmconsulting.platform.credential.application.SecretContext;
import com.udmconsulting.platform.credential.application.SecretProtector;
import com.udmconsulting.platform.credential.domain.ConnectionCredential;
import com.udmconsulting.platform.supportability.DiagnosticContext;
import com.udmconsulting.platform.supportability.OperationalErrorCode;
import com.udmconsulting.platform.supportability.SafeDiagnosticException;
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
        return new InstallationStart(authorizationUri, state.oauthOperationId());
    }

    @Override
    public CompletedInstallation completeInstallation(String state, String authorizationCode) {
        UUID oauthOperationId = stateService.consume(state);
        if (authorizationCode == null || authorizationCode.isBlank()) {
            logFailure(oauthOperationId, HubSpotOAuthFailureCategory.TOKEN_EXCHANGE_REJECTED);
            throw new HubSpotAuthorizationException();
        }
        HubSpotOAuthGateway.IssuedAuthorizationTokens issuedTokens;
        try {
            issuedTokens = oauthGateway.exchangeAuthorizationCode(authorizationCode);
        } catch (RuntimeException exception) {
            logFailure(oauthOperationId, failureCategory(
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
                            metadata.grantedScopes(),
                            new ActivityContext(
                                    ActivityActor.unattributed(ActivityActorSource.HUBSPOT),
                                    DiagnosticContext.currentDiagnosticIdOrNew()));
            finalized.supersededCredential().ifPresent(prior -> revokeSupersededBestEffort(
                    prior, issuedTokens.refreshToken(), finalized.connectionId()));
            LOGGER.atInfo()
                    .addKeyValue("component", "hubspot_oauth")
                    .addKeyValue("operation", "oauth_callback")
                    .addKeyValue("result", "SUCCESS")
                    .addKeyValue("operationId", oauthOperationId)
                    .addKeyValue("tenantRef", finalized.tenantId().value())
                    .addKeyValue("connectionRef", finalized.connectionId().value())
                    .log("HubSpot installation completed");
            return new CompletedInstallation(finalized.tenantId(), finalized.connectionId());
        } catch (RuntimeException exception) {
            revokeBestEffort(issuedTokens.refreshToken());
            logFailure(oauthOperationId, failureCategory(
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
                revokeBestEffort(superseded);
            }
        } catch (RuntimeException exception) {
            LOGGER.atError()
                    .addKeyValue("component", "hubspot_oauth")
                    .addKeyValue("operation", "revoke_superseded_credential")
                    .addKeyValue("result", "FAILED")
                    .addKeyValue("errorCode", "INTERNAL_ERROR")
                    .setCause(SafeDiagnosticException.from(exception))
                    .log("Could not process superseded HubSpot credential; operator follow-up may be required");
        }
    }

    private void revokeBestEffort(String refreshToken) {
        try {
            oauthGateway.revoke(refreshToken);
        } catch (RuntimeException exception) {
            if (exception instanceof HubSpotOAuthException oauthException) {
                LOGGER.atWarn()
                        .addKeyValue("component", "hubspot_oauth")
                        .addKeyValue("operation", "revoke_credential")
                        .addKeyValue("result", "FAILED")
                        .addKeyValue("errorCode", operationalError(oauthException.category()).name())
                        .addKeyValue("providerErrorCode", oauthException.category().name())
                        .log("HubSpot token revocation failed; operator follow-up may be required");
            } else {
                LOGGER.atError()
                        .addKeyValue("component", "hubspot_oauth")
                        .addKeyValue("operation", "revoke_credential")
                        .addKeyValue("result", "FAILED")
                        .addKeyValue("errorCode", OperationalErrorCode.INTERNAL_ERROR.name())
                        .setCause(SafeDiagnosticException.from(exception))
                        .log("HubSpot token revocation failed unexpectedly; operator follow-up may be required");
            }
        }
    }

    private static HubSpotOAuthFailureCategory failureCategory(
            RuntimeException exception, HubSpotOAuthFailureCategory fallback) {
        return exception instanceof HubSpotOAuthException oauthException
                ? oauthException.category() : fallback;
    }

    private static void logFailure(UUID oauthOperationId, HubSpotOAuthFailureCategory category) {
        LOGGER.atWarn()
                .addKeyValue("component", "hubspot_oauth")
                .addKeyValue("operation", "oauth_callback")
                .addKeyValue("result", "FAILED")
                .addKeyValue("operationId", oauthOperationId)
                .addKeyValue("errorCode", operationalError(category).name())
                .addKeyValue("providerErrorCode", category.name())
                .log("HubSpot installation failed");
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

}
