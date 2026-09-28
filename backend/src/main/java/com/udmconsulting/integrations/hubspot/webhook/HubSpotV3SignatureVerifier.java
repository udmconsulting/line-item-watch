package com.udmconsulting.integrations.hubspot.webhook;

import com.udmconsulting.integrations.hubspot.config.HubSpotOAuthProperties;
import com.udmconsulting.integrations.hubspot.config.HubSpotWebhookProperties;
import com.udmconsulting.integrations.hubspot.authentication.HubSpotRequestAuthenticationException;
import com.udmconsulting.integrations.hubspot.authentication.HubSpotV3RequestSignatureVerifier;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "hubspot.webhook", name = "enabled", havingValue = "true")
final class HubSpotV3SignatureVerifier {

    private final byte[] canonicalUri;
    private final HubSpotV3RequestSignatureVerifier verifier;

    HubSpotV3SignatureVerifier(
            HubSpotOAuthProperties oauthProperties,
            HubSpotWebhookProperties webhookProperties,
            java.time.Clock clock) {
        this.canonicalUri = webhookProperties.publicUri().toString().getBytes(StandardCharsets.UTF_8);
        this.verifier = new HubSpotV3RequestSignatureVerifier(oauthProperties, clock);
    }

    void verify(String method, String signatureHeader, String timestampHeader, byte[] rawBody) {
        if (!"POST".equals(method)) {
            throw new HubSpotWebhookAuthenticationException("unexpected HTTP method");
        }
        if (rawBody == null) {
            throw new HubSpotWebhookAuthenticationException("missing webhook body");
        }
        try {
            verifier.verify(
                    method,
                    new String(canonicalUri, StandardCharsets.UTF_8),
                    rawBody,
                    signatureHeader,
                    timestampHeader);
        } catch (HubSpotRequestAuthenticationException exception) {
            throw new HubSpotWebhookAuthenticationException("webhook authentication failed");
        }
    }
}
