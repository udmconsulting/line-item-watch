package com.udmconsulting.integrations.hubspot.config;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("hubspot.oauth")
public record HubSpotOAuthProperties(
        String clientId,
        String clientSecret,
        URI redirectUri,
        URI apiBaseUrl,
        URI authorizationBaseUrl,
        Duration connectTimeout,
        Duration readTimeout) {

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(20);
    private static final Set<String> LOCAL_HTTP_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    public static final List<String> REQUIRED_SCOPES = List.of(
            "crm.objects.deals.read",
            "crm.objects.line_items.read");

    public HubSpotOAuthProperties {
        requireText(clientId, "hubspot.oauth.client-id");
        requireText(clientSecret, "hubspot.oauth.client-secret");
        requireSecureUri(redirectUri, "hubspot.oauth.redirect-uri", true);
        requireSecureUri(apiBaseUrl, "hubspot.oauth.api-base-url", true);
        requireSecureUri(authorizationBaseUrl, "hubspot.oauth.authorization-base-url", false);
        connectTimeout = requirePositive(
                connectTimeout == null ? DEFAULT_CONNECT_TIMEOUT : connectTimeout,
                "hubspot.oauth.connect-timeout");
        readTimeout = requirePositive(
                readTimeout == null ? DEFAULT_READ_TIMEOUT : readTimeout,
                "hubspot.oauth.read-timeout");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
    }

    private static void requireSecureUri(URI value, String name, boolean allowLocalHttp) {
        if (value == null || !value.isAbsolute() || value.getHost() == null) {
            throw new IllegalStateException(name + " must be an absolute HTTP(S) URI");
        }
        if (value.getUserInfo() != null) {
            throw new IllegalStateException(name + " must not contain user information");
        }
        if ("https".equalsIgnoreCase(value.getScheme())) {
            return;
        }
        boolean allowedLocalHttp = allowLocalHttp
                && "http".equalsIgnoreCase(value.getScheme())
                && LOCAL_HTTP_HOSTS.contains(value.getHost().toLowerCase(java.util.Locale.ROOT));
        if (!allowedLocalHttp) {
            throw new IllegalStateException(name + " must use HTTPS except for an explicit local endpoint");
        }
    }

    private static Duration requirePositive(Duration value, String name) {
        if (value.isZero() || value.isNegative()) {
            throw new IllegalStateException(name + " must be positive");
        }
        return value;
    }

    @Override
    public String toString() {
        return "HubSpotOAuthProperties[clientId=" + clientId
                + ", clientSecret=<redacted>, redirectUri=" + redirectUri
                + ", apiBaseUrl=" + apiBaseUrl
                + ", authorizationBaseUrl=" + authorizationBaseUrl
                + ", connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout + "]";
    }
}
