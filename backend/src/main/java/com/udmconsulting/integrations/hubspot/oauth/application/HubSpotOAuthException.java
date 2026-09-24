package com.udmconsulting.integrations.hubspot.oauth.application;

import java.util.Objects;

public abstract class HubSpotOAuthException extends RuntimeException {

    private final HubSpotOAuthFailureCategory category;

    protected HubSpotOAuthException(String safeMessage, HubSpotOAuthFailureCategory category) {
        super(safeMessage);
        this.category = Objects.requireNonNull(category);
    }

    public final HubSpotOAuthFailureCategory category() {
        return category;
    }
}
