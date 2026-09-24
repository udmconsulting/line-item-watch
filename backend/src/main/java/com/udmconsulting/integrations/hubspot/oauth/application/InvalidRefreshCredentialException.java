package com.udmconsulting.integrations.hubspot.oauth.application;

public final class InvalidRefreshCredentialException extends HubSpotOAuthException {

    public InvalidRefreshCredentialException() {
        super("HubSpot rejected the refresh credential",
                HubSpotOAuthFailureCategory.TOKEN_EXCHANGE_REJECTED);
    }
}
