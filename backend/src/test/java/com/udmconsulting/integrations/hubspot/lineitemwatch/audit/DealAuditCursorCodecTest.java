package com.udmconsulting.integrations.hubspot.lineitemwatch.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.udmconsulting.modules.lineitemwatch.domain.ProviderObjectId;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class DealAuditCursorCodecTest {

    private final DealAuditCursorCodec codec = new DealAuditCursorCodec();

    @Test
    void cursorsAreOpaqueVersionedSectionAndDealBoundPositions() {
        ProviderObjectId deal = new ProviderObjectId("1001");
        byte[] semanticKey = new byte[32];
        Arrays.fill(semanticKey, (byte) 0xA5);
        Instant occurredAt = Instant.parse("2026-09-28T12:34:56.123456Z");

        String lineItems = codec.encodeLineItems(deal, new ProviderObjectId("line-9"));
        String events = codec.encodeEvents(
                deal, new DealAuditViewDto.EventPosition(occurredAt, semanticKey));

        assertThat(codec.decodeLineItems(lineItems, deal).lineItemId().value()).isEqualTo("line-9");
        assertThat(codec.decodeEvents(events, deal).occurredAt()).isEqualTo(occurredAt);
        assertThat(codec.decodeEvents(events, deal).semanticKey()).containsExactly(semanticKey);
        assertThatThrownBy(() -> codec.decodeEvents(lineItems, deal))
                .isInstanceOf(DealAuditRequestException.class);
        assertThatThrownBy(() -> codec.decodeLineItems(lineItems, new ProviderObjectId("1002")))
                .isInstanceOf(DealAuditRequestException.class);
        assertThatThrownBy(() -> codec.decodeLineItems(lineItems + "AA", deal))
                .isInstanceOf(DealAuditRequestException.class);
    }

    @Test
    void rejectsOutOfRangeEventTimestampAsMalformedClientInput() {
        ProviderObjectId deal = new ProviderObjectId("1001");
        String encoded = codec.encodeEvents(
                deal, new DealAuditViewDto.EventPosition(Instant.EPOCH, new byte[32]));
        byte[] payload = Base64.getUrlDecoder().decode(encoded);
        ByteBuffer.wrap(payload, 8, Long.BYTES).putLong(Long.MAX_VALUE);
        String malformed = Base64.getUrlEncoder().withoutPadding().encodeToString(payload);

        assertThatThrownBy(() -> codec.decodeEvents(malformed, deal))
                .isExactlyInstanceOf(DealAuditRequestException.class);
    }
}
