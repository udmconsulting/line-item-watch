package com.udmconsulting.integrations.hubspot.config;

import java.net.URI;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("hubspot.ui-extension")
public record HubSpotUiExtensionProperties(
        boolean enabled,
        URI publicBaseUri,
        String appId) {

    public static final String ENDPOINT_PREFIX = "/api/v1/line-item-watch/deals/";

    public HubSpotUiExtensionProperties {
        if (enabled && (publicBaseUri == null
                || !publicBaseUri.isAbsolute()
                || publicBaseUri.getHost() == null)) {
            throw new IllegalStateException(
                    "hubspot.ui-extension.public-base-uri must be an absolute HTTPS origin when enabled");
        }
        if (enabled && !"https".equals(publicBaseUri.getScheme().toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException("hubspot.ui-extension.public-base-uri must use HTTPS");
        }
        if (enabled && (publicBaseUri.getUserInfo() != null
                || publicBaseUri.getRawQuery() != null
                || publicBaseUri.getRawFragment() != null)) {
            throw new IllegalStateException(
                    "hubspot.ui-extension.public-base-uri must not contain user information, query, or fragment");
        }
        String path = enabled ? publicBaseUri.getRawPath() : null;
        if (enabled && path != null && !path.isEmpty() && !"/".equals(path)) {
            throw new IllegalStateException("hubspot.ui-extension.public-base-uri must be an origin without a path");
        }
        if (enabled && (appId == null || !appId.matches("[1-9][0-9]{0,63}"))) {
            throw new IllegalStateException(
                    "hubspot.ui-extension.app-id must be a positive decimal identifier when enabled");
        }
    }

    public String publicOrigin() {
        String value = publicBaseUri.toString();
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
