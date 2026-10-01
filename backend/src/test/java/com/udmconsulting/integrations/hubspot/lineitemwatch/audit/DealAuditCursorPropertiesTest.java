package com.udmconsulting.integrations.hubspot.lineitemwatch.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class DealAuditCursorPropertiesTest {

    @Test
    void isOptionalOnlyWhenTheAuditEndpointIsDisabled() {
        assertThat(new DealAuditCursorProperties(null, null, null, null)
                .keyRing(false).enabled()).isFalse();
        assertThat(new DealAuditCursorProperties(
                null, null, "unused-old", key((byte) 1))
                .keyRing(false).enabled()).isFalse();
        assertThatThrownBy(() -> new DealAuditCursorProperties(null, null, null, null)
                .keyRing(true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new DealAuditCursorProperties(
                null, null, "old", key((byte) 1)).keyRing(true))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void validatesDedicatedKeyIdsPairsLengthsAndCanonicalEncoding() {
        assertThatThrownBy(() -> new DealAuditCursorProperties(
                "unsafe id", key((byte) 1), null, null).keyRing(true))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new DealAuditCursorProperties(
                "active", Base64.getEncoder().encodeToString(new byte[16]), null, null)
                .keyRing(true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new DealAuditCursorProperties(
                "active", key((byte) 1).replace("=", ""), null, null).keyRing(true))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new DealAuditCursorProperties(
                "same", key((byte) 1), "same", key((byte) 2)).keyRing(true))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new DealAuditCursorProperties(
                "active", key((byte) 1), "old", null).keyRing(true))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void redactsBothConfiguredKeys() {
        DealAuditCursorProperties properties = new DealAuditCursorProperties(
                "active", key((byte) 1), "old", key((byte) 2));

        assertThat(properties.toString())
                .contains("activeKey=<redacted>", "previousKey=<redacted>")
                .doesNotContain(key((byte) 1), key((byte) 2));
    }

    private static String key(byte value) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, value);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
