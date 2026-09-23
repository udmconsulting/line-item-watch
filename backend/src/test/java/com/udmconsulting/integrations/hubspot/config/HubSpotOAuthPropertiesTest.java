package com.udmconsulting.integrations.hubspot.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class HubSpotOAuthPropertiesTest {

    @Test
    void rejectsMissingSecretsAndNonAbsoluteUris() {
        assertThatThrownBy(() -> new HubSpotOAuthProperties(
                "client", " ", URI.create("http://localhost/callback"),
                URI.create("https://api.hubapi.com"), URI.create("https://app.hubspot.com/oauth/authorize"),
                null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("client-secret");

        assertThatThrownBy(() -> new HubSpotOAuthProperties(
                "client", "secret", URI.create("relative/callback"),
                URI.create("https://api.hubapi.com"), URI.create("https://app.hubspot.com/oauth/authorize"),
                null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("redirect-uri");
    }

    @Test
    void rejectsRemoteHttpUserInfoAndUnboundedTimeoutsButAllowsLocalHttp() {
        assertThatThrownBy(() -> properties(
                URI.create("http://remote.example/callback"),
                URI.create("https://api.hubapi.com"), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTPS");

        assertThatThrownBy(() -> properties(
                URI.create("https://user@example.com/callback"),
                URI.create("https://api.hubapi.com"), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("user information");

        assertThatThrownBy(() -> properties(
                URI.create("http://localhost:8080/callback"),
                URI.create("http://remote.example"), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTPS");

        assertThatThrownBy(() -> properties(
                URI.create("http://localhost:8080/callback"),
                URI.create("http://127.0.0.1:9999"), Duration.ZERO))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("read-timeout");

        HubSpotOAuthProperties local = properties(
                URI.create("http://localhost:8080/callback"),
                URI.create("http://127.0.0.1:9999"), Duration.ofSeconds(2));
        assertThat(local.connectTimeout()).isEqualTo(Duration.ofSeconds(1));
        assertThat(local.readTimeout()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void renderingRedactsClientSecret() {
        HubSpotOAuthProperties properties = properties(
                URI.create("http://localhost:8080/callback"),
                URI.create("https://api.hubapi.com"), Duration.ofSeconds(2));

        assertThat(properties.toString())
                .contains("clientSecret=<redacted>")
                .doesNotContain("do-not-render-client-secret");
    }

    private static HubSpotOAuthProperties properties(
            URI redirectUri, URI apiBaseUrl, Duration readTimeout) {
        return new HubSpotOAuthProperties(
                "client", "do-not-render-client-secret", redirectUri, apiBaseUrl,
                URI.create("https://app.hubspot.com/oauth/authorize"),
                Duration.ofSeconds(1), readTimeout);
    }
}
