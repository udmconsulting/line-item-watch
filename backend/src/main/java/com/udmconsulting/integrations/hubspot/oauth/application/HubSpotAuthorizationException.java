package com.udmconsulting.integrations.hubspot.oauth.application;

public final class HubSpotAuthorizationException extends HubSpotOAuthException {

    public HubSpotAuthorizationException() {
        this(HubSpotOAuthFailureCategory.TOKEN_EXCHANGE_REJECTED);
    }

    public HubSpotAuthorizationException(HubSpotOAuthFailureCategory category) {
        super("HubSpot authorization could not be completed", category);
    }
}
