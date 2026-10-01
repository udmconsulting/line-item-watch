package com.udmconsulting.integrations.hubspot.lineitemwatch.audit;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("line-item-watch.audit.cursor")
public record DealAuditCursorProperties(
        String activeKeyId,
        String activeKey,
        String previousKeyId,
        String previousKey) {

    private static final int KEY_BYTES = 32;

    KeyRing keyRing(boolean required) {
        if (!required) {
            return KeyRing.disabled();
        }
        boolean activePresent = hasText(activeKeyId) || hasText(activeKey);
        boolean previousPresent = hasText(previousKeyId) || hasText(previousKey);
        if (!activePresent) {
            throw new IllegalStateException(
                    "line-item-watch.audit.cursor active key ID and key are required when the UI extension is enabled");
        }
        requireKeyId(activeKeyId, "active-key-id");
        byte[] decodedActive = decode(activeKey, "active-key");

        Map<String, byte[]> verification = new LinkedHashMap<>();
        verification.put(activeKeyId, decodedActive);
        if (previousPresent) {
            requireKeyId(previousKeyId, "previous-key-id");
            if (activeKeyId.equals(previousKeyId)) {
                throw new IllegalStateException("cursor active and previous key IDs must differ");
            }
            verification.put(previousKeyId, decode(previousKey, "previous-key"));
        }
        return new KeyRing(activeKeyId, decodedActive, verification);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static void requireKeyId(String value, String name) {
        if (!hasText(value) || !value.matches("[A-Za-z0-9._-]{1,32}")) {
            throw new IllegalStateException(
                    "line-item-watch.audit.cursor." + name + " must be 1-32 safe characters");
        }
    }

    private static byte[] decode(String value, String name) {
        if (!hasText(value)) {
            throw new IllegalStateException("line-item-watch.audit.cursor." + name + " is required");
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            if (decoded.length != KEY_BYTES) {
                throw new IllegalStateException(
                        "line-item-watch.audit.cursor." + name + " must decode to 32 bytes");
            }
            if (!Base64.getEncoder().encodeToString(decoded).equals(value)) {
                throw new IllegalStateException(
                        "line-item-watch.audit.cursor." + name + " must be canonical base64");
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "line-item-watch.audit.cursor." + name + " must be canonical base64", exception);
        }
    }

    @Override
    public String toString() {
        return "DealAuditCursorProperties[activeKeyId=" + activeKeyId
                + ", activeKey=<redacted>, previousKeyId=" + previousKeyId
                + ", previousKey=<redacted>]";
    }

    record KeyRing(String activeKeyId, byte[] activeKey, Map<String, byte[]> verificationKeys) {
        KeyRing {
            activeKey = activeKey == null ? null : activeKey.clone();
            Map<String, byte[]> copied = new LinkedHashMap<>();
            verificationKeys.forEach((key, value) -> copied.put(key, value.clone()));
            verificationKeys = Map.copyOf(copied);
        }

        static KeyRing disabled() {
            return new KeyRing(null, null, Map.of());
        }

        boolean enabled() {
            return activeKey != null;
        }

        @Override
        public byte[] activeKey() {
            return activeKey == null ? null : activeKey.clone();
        }

        byte[] verificationKey(String keyId) {
            byte[] key = verificationKeys.get(keyId);
            return key == null ? null : key.clone();
        }
    }
}
