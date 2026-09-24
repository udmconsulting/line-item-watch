package com.udmconsulting.integrations.hubspot.oauth.application;

public final class HubSpotTokenMetadataException extends HubSpotOAuthException {

    public HubSpotTokenMetadataException() {
        super("HubSpot access token metadata is invalid",
                HubSpotOAuthFailureCategory.TOKEN_METADATA_INVALID);
    }
}
