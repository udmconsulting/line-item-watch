package com.udmconsulting.integrations.hubspot.authentication;

import com.udmconsulting.integrations.hubspot.config.HubSpotOAuthProperties;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public final class HubSpotV3RequestSignatureVerifier {

    public static final Duration MAX_TIMESTAMP_SKEW = Duration.ofMinutes(5);
    private static final int SHA_256_BYTES = 32;

    private final byte[] clientSecret;
    private final Clock clock;

    public HubSpotV3RequestSignatureVerifier(HubSpotOAuthProperties properties, Clock clock) {
        this.clientSecret = properties.clientSecret().getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    public void verify(
            String method,
            String canonicalUri,
            byte[] rawBody,
            String signatureHeader,
            String timestampHeader) {
        if (method == null || method.isBlank() || canonicalUri == null || canonicalUri.isBlank()) {
            throw new HubSpotRequestAuthenticationException("missing signed request input");
        }
        if (signatureHeader == null || signatureHeader.isBlank()) {
            throw new HubSpotRequestAuthenticationException("missing request signature");
        }
        if (timestampHeader == null || timestampHeader.isBlank()) {
            throw new HubSpotRequestAuthenticationException("missing request timestamp");
        }
        byte[] body = rawBody == null ? new byte[0] : rawBody;
        long timestamp = parseTimestamp(timestampHeader);
        BigInteger skew = BigInteger.valueOf(timestamp)
                .subtract(BigInteger.valueOf(clock.millis()))
                .abs();
        if (skew.compareTo(BigInteger.valueOf(MAX_TIMESTAMP_SKEW.toMillis())) > 0) {
            throw new HubSpotRequestAuthenticationException("request timestamp is outside the allowed window");
        }

        byte[] supplied;
        try {
            supplied = Base64.getDecoder().decode(signatureHeader);
        } catch (IllegalArgumentException exception) {
            throw new HubSpotRequestAuthenticationException("malformed request signature");
        }
        if (supplied.length != SHA_256_BYTES
                || !Base64.getEncoder().encodeToString(supplied).equals(signatureHeader)) {
            throw new HubSpotRequestAuthenticationException("malformed request signature");
        }
        byte[] expected = hmac(method, canonicalUri, body, timestampHeader);
        if (!MessageDigest.isEqual(expected, supplied)) {
            throw new HubSpotRequestAuthenticationException("invalid request signature");
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
            throw new HubSpotRequestAuthenticationException("malformed request timestamp");
        }
    }

    private byte[] hmac(String method, String uri, byte[] body, String timestamp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(clientSecret, "HmacSHA256"));
            mac.update(method.getBytes(StandardCharsets.UTF_8));
            mac.update(uri.getBytes(StandardCharsets.UTF_8));
            mac.update(body);
            mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
            return mac.doFinal();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }
}
