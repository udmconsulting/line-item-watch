package com.udmconsulting.integrations.hubspot.webhook;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.udmconsulting.integrations.hubspot.config.HubSpotOAuthProperties;
import com.udmconsulting.integrations.hubspot.config.HubSpotWebhookProperties;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HubSpotV3SignatureVerifierTest {

    private static final String SECRET = "fixed-synthetic-webhook-secret";
    private static final String URI_VALUE =
            "https://webhook.example.test/integrations/hubspot/webhooks";
    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");
    private static final String TIMESTAMP = Long.toString(NOW.toEpochMilli());

    private HubSpotV3SignatureVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = verifier(URI_VALUE);
    }

    @Test
    void verifiesExactRawBody() throws Exception {
        byte[] body = "[{\"eventId\":1, \"propertyValue\":\"value\"}]"
                .getBytes(StandardCharsets.UTF_8);
        String signature = signature("POST", URI_VALUE, body, TIMESTAMP);

        assertThatCode(() -> verifier.verify("POST", signature, TIMESTAMP, body))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> verifier.verify(
                "POST",
                signature,
                TIMESTAMP,
                "[{\"propertyValue\":\"value\",\"eventId\":1}]"
                        .getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(HubSpotWebhookAuthenticationException.class);
        assertThatThrownBy(() -> verifier.verify(
                "POST",
                signature,
                TIMESTAMP,
                "[{\"eventId\":1,\"propertyValue\":\"value\"}]"
                        .getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(HubSpotWebhookAuthenticationException.class);
    }

    @Test
    void rejectsAlteredUriAndMethod() throws Exception {
        byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
        String signature = signature("POST", URI_VALUE, body, TIMESTAMP);

        assertThatThrownBy(() -> verifier("https://other.example.test/integrations/hubspot/webhooks")
                .verify("POST", signature, TIMESTAMP, body))
                .isInstanceOf(HubSpotWebhookAuthenticationException.class);
        assertThatThrownBy(() -> verifier.verify("PUT", signature, TIMESTAMP, body))
                .isInstanceOf(HubSpotWebhookAuthenticationException.class);
    }

    @Test
    void rejectsMissingMalformedAndWrongLengthSignatures() {
        byte[] body = "[]".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> verifier.verify("POST", null, TIMESTAMP, body))
                .isInstanceOf(HubSpotWebhookAuthenticationException.class);
        assertThatThrownBy(() -> verifier.verify("POST", "%%%", TIMESTAMP, body))
                .isInstanceOf(HubSpotWebhookAuthenticationException.class);
        assertThatThrownBy(() -> verifier.verify(
                "POST", Base64.getEncoder().encodeToString(new byte[31]), TIMESTAMP, body))
                .isInstanceOf(HubSpotWebhookAuthenticationException.class);
        assertThatThrownBy(() -> verifier.verify(
                "POST", Base64.getEncoder().withoutPadding().encodeToString(new byte[32]), TIMESTAMP, body))
                .isInstanceOf(HubSpotWebhookAuthenticationException.class);
        assertThatThrownBy(() -> verifier.verify(
                "POST", Base64.getEncoder().encodeToString(new byte[32]), TIMESTAMP, body))
                .isInstanceOf(HubSpotWebhookAuthenticationException.class);
    }

    @Test
    void rejectsMissingMalformedStaleAndFutureTimestamps() throws Exception {
        byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
        String valid = signature("POST", URI_VALUE, body, TIMESTAMP);

        assertThatThrownBy(() -> verifier.verify("POST", valid, null, body))
                .isInstanceOf(HubSpotWebhookAuthenticationException.class);
        assertThatThrownBy(() -> verifier.verify("POST", valid, "not-a-number", body))
                .isInstanceOf(HubSpotWebhookAuthenticationException.class);
        assertThatThrownBy(() -> verifier.verify("POST", valid, "-1", body))
                .isInstanceOf(HubSpotWebhookAuthenticationException.class);

        String stale = Long.toString(NOW.minus(Duration.ofMinutes(5)).minusMillis(1).toEpochMilli());
        String future = Long.toString(NOW.plus(Duration.ofMinutes(5)).plusMillis(1).toEpochMilli());
        assertThatThrownBy(() -> verifier.verify(
                "POST", signature("POST", URI_VALUE, body, stale), stale, body))
                .isInstanceOf(HubSpotWebhookAuthenticationException.class);
        assertThatThrownBy(() -> verifier.verify(
                "POST", signature("POST", URI_VALUE, body, future), future, body))
                .isInstanceOf(HubSpotWebhookAuthenticationException.class);
    }

    private static HubSpotV3SignatureVerifier verifier(String publicUri) {
        HubSpotOAuthProperties oauth = new HubSpotOAuthProperties(
                "client-id",
                SECRET,
                URI.create("http://localhost:8080/integrations/hubspot/oauth/callback"),
                URI.create("http://localhost:9999"),
                URI.create("https://app.hubspot.com/oauth/authorize"),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1));
        return new HubSpotV3SignatureVerifier(
                oauth,
                new HubSpotWebhookProperties(true, URI.create(publicUri)),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static String signature(
            String method, String uri, byte[] body, String timestamp) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update(method.getBytes(StandardCharsets.UTF_8));
        mac.update(uri.getBytes(StandardCharsets.UTF_8));
        mac.update(body);
        mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(mac.doFinal());
    }
}
