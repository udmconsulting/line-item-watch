package com.udmconsulting.integrations.hubspot.webhook;

final class HubSpotWebhookAuthenticationException extends RuntimeException {

    HubSpotWebhookAuthenticationException(String message) {
        super(message);
    }
}
