package com.udmconsulting.integrations.hubspot.oauth.application;

public final class HubSpotInactiveAccessTokenException extends HubSpotOAuthException {

    public HubSpotInactiveAccessTokenException() {
        super("HubSpot access token is inactive",
                HubSpotOAuthFailureCategory.TOKEN_INTROSPECTION_REJECTED);
    }
}
