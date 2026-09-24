package com.udmconsulting.integrations.hubspot.oauth.application;

public final class HubSpotInsufficientScopeException extends HubSpotOAuthException {

    public HubSpotInsufficientScopeException() {
        super("HubSpot did not grant all required permissions",
                HubSpotOAuthFailureCategory.REQUIRED_SCOPE_MISSING);
    }
}
