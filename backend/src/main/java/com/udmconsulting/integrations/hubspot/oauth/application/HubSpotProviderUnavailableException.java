package com.udmconsulting.integrations.hubspot.oauth.application;

public final class HubSpotProviderUnavailableException extends HubSpotOAuthException {

    public HubSpotProviderUnavailableException() {
        this(HubSpotOAuthFailureCategory.TOKEN_EXCHANGE_PROVIDER_UNAVAILABLE);
    }

    public HubSpotProviderUnavailableException(HubSpotOAuthFailureCategory category) {
        super("HubSpot is temporarily unavailable", category);
    }

    public HubSpotProviderUnavailableException(
            HubSpotOAuthFailureCategory category, Throwable ignored) {
        this(category);
    }
}
