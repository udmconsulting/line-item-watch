package com.udmconsulting.integrations.hubspot.oauth.application;

public final class HubSpotAuthorizationException extends RuntimeException {

    public HubSpotAuthorizationException() {
        super("HubSpot authorization could not be completed");
    }
}
