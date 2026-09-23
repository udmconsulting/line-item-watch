package com.udmconsulting.integrations.hubspot.oauth.application;

public final class InvalidRefreshCredentialException extends RuntimeException {

    public InvalidRefreshCredentialException() {
        super("HubSpot rejected the refresh credential");
    }
}
