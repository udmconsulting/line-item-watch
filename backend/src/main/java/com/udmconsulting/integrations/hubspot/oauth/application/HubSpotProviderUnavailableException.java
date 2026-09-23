package com.udmconsulting.integrations.hubspot.oauth.application;

public final class HubSpotProviderUnavailableException extends RuntimeException {

    public HubSpotProviderUnavailableException() {
        super("HubSpot is temporarily unavailable");
    }

    public HubSpotProviderUnavailableException(Throwable cause) {
        super("HubSpot is temporarily unavailable", cause);
    }
}
