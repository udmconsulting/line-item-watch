package com.udmconsulting.integrations.hubspot.lineitemwatch.audit;

import com.udmconsulting.modules.lineitemwatch.application.DealAuditQuery;
import com.udmconsulting.modules.lineitemwatch.domain.ProviderObjectId;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import org.springframework.stereotype.Component;

@Component
final class DealAuditCursorCodec {

    private static final int VERSION = 1;
    private static final int LINE_ITEMS = 1;
    private static final int EVENTS = 2;
    private static final int MAX_CURSOR_LENGTH = 1024;

    String encodeLineItems(ProviderObjectId dealId, ProviderObjectId lineItemId) {
        return encode(output -> {
            output.writeByte(VERSION);
            output.writeByte(LINE_ITEMS);
            output.writeUTF(dealId.value());
            output.writeUTF(lineItemId.value());
        });
    }

    DealAuditQuery.LineItemCursor decodeLineItems(String value, ProviderObjectId dealId) {
        return decode(value, input -> {
            requireHeader(input, LINE_ITEMS, dealId);
            DealAuditQuery.LineItemCursor cursor =
                    new DealAuditQuery.LineItemCursor(new ProviderObjectId(input.readUTF()));
            requireEnd(input);
            return cursor;
        });
    }

    String encodeEvents(ProviderObjectId dealId, DealAuditViewDto.EventPosition event) {
        return encode(output -> {
            output.writeByte(VERSION);
            output.writeByte(EVENTS);
            output.writeUTF(dealId.value());
            output.writeLong(event.occurredAt().getEpochSecond());
            output.writeInt(event.occurredAt().getNano());
            output.write(event.semanticKey());
        });
    }

    DealAuditQuery.EventCursor decodeEvents(String value, ProviderObjectId dealId) {
        return decode(value, input -> {
            requireHeader(input, EVENTS, dealId);
            Instant occurredAt = Instant.ofEpochSecond(input.readLong(), input.readInt());
            byte[] semanticKey = input.readNBytes(32);
            if (semanticKey.length != 32) {
                throw new DealAuditRequestException("invalid event cursor key");
            }
            requireEnd(input);
            return new DealAuditQuery.EventCursor(occurredAt, semanticKey);
        });
    }

    private static void requireHeader(
            DataInputStream input, int section, ProviderObjectId expectedDeal) throws IOException {
        if (input.readUnsignedByte() != VERSION || input.readUnsignedByte() != section) {
            throw new DealAuditRequestException("cursor version or section is invalid");
        }
        if (!expectedDeal.value().equals(input.readUTF())) {
            throw new DealAuditRequestException("cursor Deal does not match request");
        }
    }

    private static void requireEnd(DataInputStream input) throws IOException {
        if (input.read() != -1) {
            throw new DealAuditRequestException("cursor contains trailing data");
        }
    }

    private static String encode(Encoder encoder) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                encoder.encode(output);
            }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
        } catch (IOException exception) {
            throw new IllegalStateException("in-memory cursor encoding failed", exception);
        }
    }

    private static <T> T decode(String value, Decoder<T> decoder) {
        if (value == null || value.isBlank() || value.length() > MAX_CURSOR_LENGTH) {
            throw new DealAuditRequestException("cursor is missing or oversized");
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(value);
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
                return decoder.decode(input);
            }
        } catch (IllegalArgumentException | DateTimeException | IOException exception) {
            throw new DealAuditRequestException("cursor is malformed", exception);
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
