package com.udmconsulting.integrations.hubspot.oauth.infrastructure.web;

import com.udmconsulting.integrations.hubspot.oauth.application.HubSpotAuthorizationException;
import com.udmconsulting.integrations.hubspot.oauth.application.HubSpotProviderUnavailableException;
import com.udmconsulting.integrations.hubspot.oauth.application.HubSpotInsufficientScopeException;
import com.udmconsulting.integrations.hubspot.oauth.application.HubSpotOAuthException;
import com.udmconsulting.integrations.hubspot.oauth.application.HubSpotUninstallException;
import com.udmconsulting.integrations.hubspot.oauth.application.OAuthStateException;
import com.udmconsulting.platform.credential.application.ConcurrentCredentialChangeException;
import com.udmconsulting.platform.credential.application.ReauthenticationRequiredException;
import com.udmconsulting.platform.supportability.OperationalErrorCode;
import com.udmconsulting.platform.supportability.RequestSupportability;
import com.udmconsulting.platform.supportability.SafeDiagnosticException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = HubSpotOAuthController.class)
public class OAuthErrorHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(OAuthErrorHandler.class);

    @ExceptionHandler(OAuthStateException.class)
    ResponseEntity<String> stateError(HttpServletRequest request, OAuthStateException exception) {
        return known(request, HttpStatus.BAD_REQUEST, OperationalErrorCode.INVALID_REQUEST);
    }

    @ExceptionHandler(HubSpotAuthorizationException.class)
    ResponseEntity<String> authorizationError(HttpServletRequest request) {
        return known(request, HttpStatus.BAD_REQUEST, OperationalErrorCode.AUTHENTICATION_FAILED);
    }

    @ExceptionHandler(HubSpotInsufficientScopeException.class)
    ResponseEntity<String> insufficientScope(HttpServletRequest request) {
        return known(request, HttpStatus.BAD_REQUEST, OperationalErrorCode.PROVIDER_AUTH_REQUIRED);
    }

    @ExceptionHandler(HubSpotOAuthException.class)
    ResponseEntity<String> oauthContractError(HttpServletRequest request) {
        return known(request, HttpStatus.BAD_REQUEST, OperationalErrorCode.INVALID_REQUEST);
    }

    @ExceptionHandler(HubSpotProviderUnavailableException.class)
    ResponseEntity<String> providerUnavailable(HttpServletRequest request) {
        return known(request, HttpStatus.SERVICE_UNAVAILABLE, OperationalErrorCode.PROVIDER_UNAVAILABLE);
    }

    @ExceptionHandler(ReauthenticationRequiredException.class)
    ResponseEntity<String> reauthenticationRequired(HttpServletRequest request) {
        return known(request, HttpStatus.CONFLICT, OperationalErrorCode.PROVIDER_AUTH_REQUIRED);
    }

    @ExceptionHandler(ConcurrentCredentialChangeException.class)
    ResponseEntity<String> concurrentChange(HttpServletRequest request) {
        return known(request, HttpStatus.CONFLICT, OperationalErrorCode.CONCURRENT_STATE_CHANGE);
    }

    @ExceptionHandler(HubSpotUninstallException.class)
    ResponseEntity<String> uninstallFailed(HttpServletRequest request) {
        return known(request, HttpStatus.BAD_GATEWAY, OperationalErrorCode.PROVIDER_UNAVAILABLE);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<String> unexpectedFailure(HttpServletRequest request, Exception exception) {
        RequestSupportability.error(request, OperationalErrorCode.INTERNAL_ERROR);
        LOGGER.atError()
                .addKeyValue("component", "hubspot_oauth")
                .addKeyValue("operation", "oauth_request")
                .addKeyValue("result", "FAILED")
                .addKeyValue("errorCode", OperationalErrorCode.INTERNAL_ERROR.name())
                .setCause(SafeDiagnosticException.from(exception))
                .log("HubSpot OAuth request failed unexpectedly");
        return OAuthHttpResponses.error(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private static ResponseEntity<String> known(
            HttpServletRequest request,
            HttpStatus status,
            OperationalErrorCode errorCode) {
        RequestSupportability.error(request, errorCode);
        LOGGER.atWarn()
                .addKeyValue("component", "hubspot_oauth")
                .addKeyValue("operation", "oauth_request")
                .addKeyValue("result", "REJECTED")
                .addKeyValue("errorCode", errorCode.name())
                .log("HubSpot OAuth request rejected");
        return OAuthHttpResponses.error(status);
    }
}
