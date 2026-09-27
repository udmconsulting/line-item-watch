package com.udmconsulting.integrations.hubspot.config;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("hubspot.webhook")
public record HubSpotWebhookProperties(boolean enabled, URI publicUri) {

    public static final String ENDPOINT_PATH = "/integrations/hubspot/webhooks";
    public static final int MAX_BODY_BYTES = 8 * 1024 * 1024;
    public static final int MAX_BATCH_EVENTS = 100;
    public static final Duration MAX_TIMESTAMP_SKEW = Duration.ofMinutes(5);

    public HubSpotWebhookProperties {
        if (enabled) {
            validatePublicUri(publicUri);
        }
    }

    private static void validatePublicUri(URI uri) {
        if (uri == null || !uri.isAbsolute() || uri.getHost() == null) {
            throw new IllegalStateException(
                    "hubspot.webhook.public-uri must be an absolute HTTPS URI when webhooks are enabled");
        }
        if (!"https".equals(uri.getScheme().toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException("hubspot.webhook.public-uri must use HTTPS");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalStateException("hubspot.webhook.public-uri must not contain user information");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalStateException("hubspot.webhook.public-uri must not contain a query or fragment");
        }
        if (uri.getRawPath() == null || uri.getRawPath().contains("%")) {
            throw new IllegalStateException("hubspot.webhook.public-uri must have an unencoded path");
        }
        if (!uri.getRawPath().endsWith(ENDPOINT_PATH)) {
            throw new IllegalStateException(
                    "hubspot.webhook.public-uri must end with " + ENDPOINT_PATH);
        }
    }
}
