package com.udmconsulting.integrations.hubspot.oauth.application;

public final class HubSpotUninstallException extends RuntimeException {

    public HubSpotUninstallException() {
        super("HubSpot uninstall could not be completed");
    }
}
