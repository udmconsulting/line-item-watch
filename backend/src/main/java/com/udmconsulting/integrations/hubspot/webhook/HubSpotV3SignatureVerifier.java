package com.udmconsulting.integrations.hubspot.webhook;

import com.udmconsulting.integrations.hubspot.config.HubSpotOAuthProperties;
import com.udmconsulting.integrations.hubspot.config.HubSpotWebhookProperties;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "hubspot.webhook", name = "enabled", havingValue = "true")
final class HubSpotV3SignatureVerifier {

    static final int SHA_256_BYTES = 32;

    private final byte[] clientSecret;
    private final byte[] canonicalUri;
    private final Clock clock;

    HubSpotV3SignatureVerifier(
            HubSpotOAuthProperties oauthProperties,
            HubSpotWebhookProperties webhookProperties,
            Clock clock) {
        this.clientSecret = oauthProperties.clientSecret().getBytes(StandardCharsets.UTF_8);
        this.canonicalUri = webhookProperties.publicUri().toString().getBytes(StandardCharsets.UTF_8);
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    void verify(String method, String signatureHeader, String timestampHeader, byte[] rawBody) {
        if (!"POST".equals(method)) {
            throw new HubSpotWebhookAuthenticationException("unexpected HTTP method");
        }
        if (signatureHeader == null || signatureHeader.isBlank()) {
            throw new HubSpotWebhookAuthenticationException("missing webhook signature");
        }
        if (timestampHeader == null || timestampHeader.isBlank()) {
            throw new HubSpotWebhookAuthenticationException("missing webhook timestamp");
        }
        Objects.requireNonNull(rawBody, "rawBody must not be null");

        long timestamp = parseTimestamp(timestampHeader);
        BigInteger skew = BigInteger.valueOf(timestamp)
                .subtract(BigInteger.valueOf(clock.millis()))
                .abs();
        if (skew.compareTo(BigInteger.valueOf(
                HubSpotWebhookProperties.MAX_TIMESTAMP_SKEW.toMillis())) > 0) {
            throw new HubSpotWebhookAuthenticationException("webhook timestamp is outside the allowed window");
        }

        byte[] suppliedSignature;
        try {
            suppliedSignature = Base64.getDecoder().decode(signatureHeader);
        } catch (IllegalArgumentException exception) {
            throw new HubSpotWebhookAuthenticationException("malformed webhook signature");
        }
        if (suppliedSignature.length != SHA_256_BYTES
                || !Base64.getEncoder().encodeToString(suppliedSignature).equals(signatureHeader)) {
            throw new HubSpotWebhookAuthenticationException("malformed webhook signature");
        }

        byte[] expectedSignature = hmac(rawBody, timestampHeader);
        if (!MessageDigest.isEqual(expectedSignature, suppliedSignature)) {
            throw new HubSpotWebhookAuthenticationException("invalid webhook signature");
        }
    }

    private long parseTimestamp(String value) {
        try {
            long timestamp = Long.parseLong(value);
            if (timestamp < 0) {
                throw new NumberFormatException("negative timestamp");
            }
            return timestamp;
        } catch (NumberFormatException exception) {
            throw new HubSpotWebhookAuthenticationException("malformed webhook timestamp");
        }
    }

    private byte[] hmac(byte[] rawBody, String timestampHeader) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(clientSecret, "HmacSHA256"));
            mac.update("POST".getBytes(StandardCharsets.UTF_8));
            mac.update(canonicalUri);
            mac.update(rawBody);
            mac.update(timestampHeader.getBytes(StandardCharsets.UTF_8));
            return mac.doFinal();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }
}
