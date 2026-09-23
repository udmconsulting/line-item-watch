package com.udmconsulting.integrations.hubspot.oauth.infrastructure.web;

import com.udmconsulting.integrations.hubspot.oauth.application.HubSpotAuthorizationException;
import com.udmconsulting.integrations.hubspot.oauth.application.HubSpotProviderUnavailableException;
import com.udmconsulting.integrations.hubspot.oauth.application.HubSpotInsufficientScopeException;
import com.udmconsulting.integrations.hubspot.oauth.application.HubSpotUninstallException;
import com.udmconsulting.integrations.hubspot.oauth.application.OAuthStateException;
import com.udmconsulting.platform.credential.application.ConcurrentCredentialChangeException;
import com.udmconsulting.platform.credential.application.ReauthenticationRequiredException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = HubSpotOAuthController.class)
public class OAuthErrorHandler {

    @ExceptionHandler(OAuthStateException.class)
    ResponseEntity<String> stateError(OAuthStateException exception) {
        return OAuthHttpResponses.error(HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(HubSpotAuthorizationException.class)
    ResponseEntity<String> authorizationError() {
        return OAuthHttpResponses.error(HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(HubSpotInsufficientScopeException.class)
    ResponseEntity<String> insufficientScope() {
        return OAuthHttpResponses.error(HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(HubSpotProviderUnavailableException.class)
    ResponseEntity<String> providerUnavailable() {
        return OAuthHttpResponses.error(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @ExceptionHandler(ReauthenticationRequiredException.class)
    ResponseEntity<String> reauthenticationRequired() {
        return OAuthHttpResponses.error(HttpStatus.CONFLICT);
    }

    @ExceptionHandler(ConcurrentCredentialChangeException.class)
    ResponseEntity<String> concurrentChange() {
        return OAuthHttpResponses.error(HttpStatus.CONFLICT);
    }

    @ExceptionHandler(HubSpotUninstallException.class)
    ResponseEntity<String> uninstallFailed() {
        return OAuthHttpResponses.error(HttpStatus.BAD_GATEWAY);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<String> unexpectedFailure() {
        return OAuthHttpResponses.error(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
