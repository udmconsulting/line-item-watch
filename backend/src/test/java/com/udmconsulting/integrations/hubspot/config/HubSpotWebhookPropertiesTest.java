package com.udmconsulting.integrations.hubspot.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Test;

class HubSpotWebhookPropertiesTest {

    @Test
    void disabledReceiverDoesNotRequirePublicUri() {
        assertThatCode(() -> new HubSpotWebhookProperties(false, null))
                .doesNotThrowAnyException();
    }

    @Test
    void enabledReceiverAcceptsOnlyCanonicalHttpsEndpoint() {
        assertThatCode(() -> new HubSpotWebhookProperties(
                true,
                URI.create("https://webhook.example.test/integrations/hubspot/webhooks")))
                .doesNotThrowAnyException();

        for (String invalid : new String[] {
                "http://webhook.example.test/integrations/hubspot/webhooks",
                "https://user@webhook.example.test/integrations/hubspot/webhooks",
                "https://webhook.example.test/integrations/hubspot/webhooks?debug=true",
                "https://webhook.example.test/integrations/hubspot/webhooks#fragment",
                "https://webhook.example.test/integrations/hubspot/%77ebhooks",
                "https://webhook.example.test/integrations/hubspot/other"
        }) {
            assertThatThrownBy(() -> new HubSpotWebhookProperties(true, URI.create(invalid)))
                    .as("URI <%s>", invalid)
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> new HubSpotWebhookProperties(true, null))
                .isInstanceOf(IllegalStateException.class);
    }
}
