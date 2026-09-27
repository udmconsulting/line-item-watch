package com.udmconsulting.integrations.hubspot.webhook;

final class HubSpotWebhookPayloadException extends RuntimeException {

    HubSpotWebhookPayloadException(String message) {
        super(message);
    }

    HubSpotWebhookPayloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
