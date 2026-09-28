package com.udmconsulting.integrations.hubspot.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Test;

class HubSpotUiExtensionPropertiesTest {

    @Test
    void disabledEndpointRequiresNoPublicConfiguration() {
        assertThatCode(() -> new HubSpotUiExtensionProperties(false, null, null))
                .doesNotThrowAnyException();
    }

    @Test
    void enabledEndpointRequiresHttpsOriginAndNumericAppId() {
        assertThatCode(() -> properties("https://api.example.test", "12345"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> properties("http://api.example.test", "12345"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> properties("https://api.example.test/base", "12345"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> properties("https://api.example.test?secret=value", "12345"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> properties("https://api.example.test", "app"))
                .isInstanceOf(IllegalStateException.class);
    }

    private static HubSpotUiExtensionProperties properties(String uri, String appId) {
        return new HubSpotUiExtensionProperties(true, URI.create(uri), appId);
    }
}
