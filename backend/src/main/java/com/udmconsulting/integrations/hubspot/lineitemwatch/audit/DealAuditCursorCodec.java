package com.udmconsulting.integrations.hubspot.lineitemwatch.audit;

import com.udmconsulting.integrations.hubspot.config.HubSpotUiExtensionProperties;
import com.udmconsulting.modules.lineitemwatch.application.DealAuditQuery;
import com.udmconsulting.modules.lineitemwatch.domain.ProviderObjectId;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
final class DealAuditCursorCodec {

    private static final int VERSION_1 = 1;
    private static final int VERSION_2 = 2;
    private static final int LINE_ITEMS = 1;
    private static final int EVENTS = 2;
    private static final int SHA_256_BYTES = 32;
    private static final int MAX_CURSOR_LENGTH = 1024;
    private static final byte[] HMAC_DOMAIN =
            "line-item-watch-deal-audit-cursor-v2\0".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CONTEXT_DOMAIN =
            "line-item-watch-deal-audit-query-v2\0".getBytes(StandardCharsets.UTF_8);

    private final DealAuditCursorProperties.KeyRing keyRing;

    @Autowired
    DealAuditCursorCodec(
            DealAuditCursorProperties properties,
            HubSpotUiExtensionProperties uiExtensionProperties) {
        this(properties.keyRing(uiExtensionProperties.enabled()));
    }

    private DealAuditCursorCodec(DealAuditCursorProperties.KeyRing keyRing) {
        this.keyRing = keyRing;
    }

    static DealAuditCursorCodec forTests() {
        byte[] key = new byte[SHA_256_BYTES];
        Arrays.fill(key, (byte) 0x5a);
        return new DealAuditCursorCodec(new DealAuditCursorProperties.KeyRing(
                "test-key", key, java.util.Map.of("test-key", key)));
    }

    String encodeLineItems(
            ProviderObjectId dealId,
            DealAuditQuery.LineItemFilter filter,
            ProviderObjectId lineItemId) {
        return encode(LINE_ITEMS, dealId, contextHash(LINE_ITEMS, filter, null), output ->
                output.writeUTF(lineItemId.value()));
    }

    DealAuditQuery.LineItemCursor decodeLineItems(
            String value,
            ProviderObjectId dealId,
            DealAuditQuery.LineItemFilter filter) {
        return decode(value, LINE_ITEMS, dealId, contextHash(LINE_ITEMS, filter, null),
                input -> new DealAuditQuery.LineItemCursor(
                        new ProviderObjectId(input.readUTF())),
                !filter.active());
    }

    String encodeEvents(
            ProviderObjectId dealId,
            DealAuditQuery.EventFilter filter,
            DealAuditViewDto.EventPosition event) {
        return encode(EVENTS, dealId, contextHash(EVENTS, null, filter), output -> {
            output.writeLong(event.occurredAt().getEpochSecond());
            output.writeInt(event.occurredAt().getNano());
            output.write(event.semanticKey());
        });
    }

    DealAuditQuery.EventCursor decodeEvents(
            String value,
            ProviderObjectId dealId,
            DealAuditQuery.EventFilter filter) {
        return decode(value, EVENTS, dealId, contextHash(EVENTS, null, filter), input -> {
            Instant occurredAt = Instant.ofEpochSecond(input.readLong(), input.readInt());
            byte[] semanticKey = input.readNBytes(SHA_256_BYTES);
            if (semanticKey.length != SHA_256_BYTES) {
                throw new DealAuditRequestException("invalid event cursor key");
            }
            return new DealAuditQuery.EventCursor(occurredAt, semanticKey);
        }, !filter.active());
    }

    private String encode(
            int section,
            ProviderObjectId dealId,
            byte[] contextHash,
            Encoder positionEncoder) {
        if (!keyRing.enabled()) {
            throw new IllegalStateException("cursor integrity key is unavailable");
        }
        try {
            ByteArrayOutputStream payloadBytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(payloadBytes)) {
                output.writeByte(VERSION_2);
                output.writeByte(section);
                output.writeUTF(keyRing.activeKeyId());
                output.writeUTF(dealId.value());
                output.write(contextHash);
                positionEncoder.encode(output);
            }
            byte[] payload = payloadBytes.toByteArray();
            byte[] tag = hmac(keyRing.activeKey(), payload);
            byte[] cursor = Arrays.copyOf(payload, payload.length + tag.length);
            System.arraycopy(tag, 0, cursor, payload.length, tag.length);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(cursor);
        } catch (IOException exception) {
            throw new IllegalStateException("in-memory cursor encoding failed", exception);
        }
    }

    private <T> T decode(
            String value,
            int expectedSection,
            ProviderObjectId expectedDeal,
            byte[] expectedContext,
            Decoder<T> positionDecoder,
            boolean allowV1) {
        byte[] cursor = decodeBase64(value);
        if (cursor.length == 0) {
            throw new DealAuditRequestException("cursor is malformed");
        }
        int version = Byte.toUnsignedInt(cursor[0]);
        try {
            if (version == VERSION_1) {
                if (!allowV1) {
                    throw new DealAuditRequestException(
                            "legacy cursor cannot be used with filters");
                }
                try (DataInputStream input = new DataInputStream(
                        new ByteArrayInputStream(cursor))) {
                    requireV1Header(input, expectedSection, expectedDeal);
                    T decoded = positionDecoder.decode(input);
                    requireEnd(input);
                    return decoded;
                }
            }
            if (version != VERSION_2 || cursor.length <= SHA_256_BYTES) {
                throw new DealAuditRequestException("cursor version is invalid");
            }
            byte[] payload = Arrays.copyOf(cursor, cursor.length - SHA_256_BYTES);
            byte[] suppliedTag = Arrays.copyOfRange(
                    cursor, cursor.length - SHA_256_BYTES, cursor.length);
            String keyId;
            try (DataInputStream header = new DataInputStream(
                    new ByteArrayInputStream(payload))) {
                if (header.readUnsignedByte() != VERSION_2
                        || header.readUnsignedByte() != expectedSection) {
                    throw new DealAuditRequestException(
                            "cursor version or section is invalid");
                }
                keyId = header.readUTF();
            }
            byte[] key = keyRing.verificationKey(keyId);
            if (key == null || !MessageDigest.isEqual(hmac(key, payload), suppliedTag)) {
                throw new DealAuditRequestException("cursor integrity is invalid");
            }
            try (DataInputStream input = new DataInputStream(
                    new ByteArrayInputStream(payload))) {
                requireV2Header(
                        input, expectedSection, keyId, expectedDeal, expectedContext);
                T decoded = positionDecoder.decode(input);
                requireEnd(input);
                return decoded;
            }
        } catch (DealAuditRequestException exception) {
            throw exception;
        } catch (IllegalArgumentException | DateTimeException | IOException exception) {
            throw new DealAuditRequestException("cursor is malformed", exception);
        }
    }

    private static byte[] decodeBase64(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_CURSOR_LENGTH) {
            throw new DealAuditRequestException("cursor is missing or oversized");
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value)) {
                throw new DealAuditRequestException("cursor encoding is not canonical");
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new DealAuditRequestException("cursor is malformed", exception);
        }
    }

    private static void requireV1Header(
            DataInputStream input, int section, ProviderObjectId expectedDeal) throws IOException {
        if (input.readUnsignedByte() != VERSION_1 || input.readUnsignedByte() != section) {
            throw new DealAuditRequestException("cursor version or section is invalid");
        }
        if (!expectedDeal.value().equals(input.readUTF())) {
            throw new DealAuditRequestException("cursor Deal does not match request");
        }
    }

    private static void requireV2Header(
            DataInputStream input,
            int section,
            String authenticatedKeyId,
            ProviderObjectId expectedDeal,
            byte[] expectedContext) throws IOException {
        if (input.readUnsignedByte() != VERSION_2 || input.readUnsignedByte() != section) {
            throw new DealAuditRequestException("cursor version or section is invalid");
        }
        if (!authenticatedKeyId.equals(input.readUTF())) {
            throw new DealAuditRequestException("cursor key is invalid");
        }
        if (!expectedDeal.value().equals(input.readUTF())) {
            throw new DealAuditRequestException("cursor Deal does not match request");
        }
        byte[] actualContext = input.readNBytes(SHA_256_BYTES);
        if (actualContext.length != SHA_256_BYTES
                || !MessageDigest.isEqual(expectedContext, actualContext)) {
            throw new DealAuditRequestException("cursor query does not match request");
        }
    }

    private static byte[] contextHash(
            int section,
            DealAuditQuery.LineItemFilter lineItemFilter,
            DealAuditQuery.EventFilter eventFilter) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(CONTEXT_DOMAIN);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeByte(section);
                if (section == LINE_ITEMS) {
                    writeNullable(output, lineItemFilter.canonicalSearch());
                } else {
                    writeNullable(output, eventFilter.eventType() == null
                            ? null : eventFilter.eventType().name());
                    writeNullable(output, eventFilter.field() == null
                            ? null : eventFilter.field().apiName());
                    writeNullable(output, eventFilter.lineItemId() == null
                            ? null : eventFilter.lineItemId().value());
                    writeNullable(output, eventFilter.from() == null
                            ? null : eventFilter.from().toString());
                    writeNullable(output, eventFilter.to() == null
                            ? null : eventFilter.to().toString());
                }
            }
            return digest.digest(bytes.toByteArray());
        } catch (NoSuchAlgorithmException | IOException exception) {
            throw new IllegalStateException("cursor context hashing failed", exception);
        }
    }

    private static void writeNullable(DataOutputStream output, String value) throws IOException {
        output.writeBoolean(value != null);
        if (value != null) {
            output.writeUTF(value);
        }
    }

    private static byte[] hmac(byte[] key, byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update(HMAC_DOMAIN);
            return mac.doFinal(payload);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }

    private static void requireEnd(DataInputStream input) throws IOException {
        if (input.read() != -1) {
            throw new DealAuditRequestException("cursor contains trailing data");
        }
    }

    @FunctionalInterface
    private interface Encoder {
        void encode(DataOutputStream output) throws IOException;
    }

    @FunctionalInterface
    private interface Decoder<T> {
        T decode(DataInputStream input) throws IOException;
    }
}
