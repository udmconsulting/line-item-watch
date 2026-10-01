package com.udmconsulting.integrations.hubspot.lineitemwatch.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.udmconsulting.integrations.hubspot.config.HubSpotUiExtensionProperties;
import com.udmconsulting.modules.lineitemwatch.application.DealAuditQuery;
import com.udmconsulting.modules.lineitemwatch.domain.LineItemAuditType;
import com.udmconsulting.modules.lineitemwatch.domain.MonitoredLineItemProperty;
import com.udmconsulting.modules.lineitemwatch.domain.ProviderObjectId;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class DealAuditCursorCodecTest {

    private static final DealAuditQuery.LineItemFilter NO_LINE_FILTER =
            DealAuditQuery.LineItemFilter.none();
    private static final DealAuditQuery.EventFilter NO_EVENT_FILTER =
            DealAuditQuery.EventFilter.none();
    private final DealAuditCursorCodec codec = DealAuditCursorCodec.forTests();

    @Test
    void v2CursorsAreOpaqueIntegrityProtectedAndBoundToSectionDealAndQuery() {
        ProviderObjectId deal = new ProviderObjectId("1001");
        byte[] semanticKey = new byte[32];
        Arrays.fill(semanticKey, (byte) 0xA5);
        Instant occurredAt = Instant.parse("2026-09-28T12:34:56.123456Z");
        DealAuditQuery.LineItemFilter lineFilter = new DealAuditQuery.LineItemFilter("Support");
        DealAuditQuery.EventFilter eventFilter = new DealAuditQuery.EventFilter(
                LineItemAuditType.PROPERTY_CHANGED,
                MonitoredLineItemProperty.QUANTITY,
                new ProviderObjectId("2002"),
                Instant.parse("2026-09-01T00:00:00Z"),
                Instant.parse("2026-10-01T00:00:00Z"));

        String lineItems = codec.encodeLineItems(
                deal, lineFilter, new ProviderObjectId("2009"));
        String events = codec.encodeEvents(
                deal, eventFilter, new DealAuditViewDto.EventPosition(occurredAt, semanticKey));

        assertThat(codec.decodeLineItems(lineItems, deal, lineFilter).lineItemId().value())
                .isEqualTo("2009");
        assertThat(codec.decodeLineItems(
                lineItems, deal, new DealAuditQuery.LineItemFilter("support")).lineItemId().value())
                .isEqualTo("2009");
        assertThat(codec.decodeEvents(events, deal, eventFilter).occurredAt()).isEqualTo(occurredAt);
        assertThat(codec.decodeEvents(events, deal, eventFilter).semanticKey())
                .containsExactly(semanticKey);

        assertThatThrownBy(() -> codec.decodeEvents(events, deal, NO_EVENT_FILTER))
                .isExactlyInstanceOf(DealAuditRequestException.class);
        assertThatThrownBy(() -> codec.decodeEvents(lineItems, deal, NO_EVENT_FILTER))
                .isExactlyInstanceOf(DealAuditRequestException.class);
        assertThatThrownBy(() -> codec.decodeLineItems(
                lineItems, new ProviderObjectId("1002"), lineFilter))
                .isExactlyInstanceOf(DealAuditRequestException.class);
        assertThatThrownBy(() -> codec.decodeLineItems(
                lineItems, deal, new DealAuditQuery.LineItemFilter("Other")))
                .isExactlyInstanceOf(DealAuditRequestException.class);

        byte[] tampered = Base64.getUrlDecoder().decode(events);
        tampered[tampered.length / 2] ^= 1;
        String tamperedValue = Base64.getUrlEncoder().withoutPadding().encodeToString(tampered);
        assertThatThrownBy(() -> codec.decodeEvents(tamperedValue, deal, eventFilter))
                .isExactlyInstanceOf(DealAuditRequestException.class);
    }

    @Test
    void everyEventFilterDimensionParticipatesInTheCursorContext() {
        ProviderObjectId deal = new ProviderObjectId("1001");
        DealAuditQuery.EventFilter filter = new DealAuditQuery.EventFilter(
                LineItemAuditType.PROPERTY_CHANGED,
                MonitoredLineItemProperty.NAME,
                new ProviderObjectId("2002"),
                Instant.parse("2026-09-01T00:00:00Z"),
                Instant.parse("2026-10-01T00:00:00Z"));
        String cursor = codec.encodeEvents(
                deal,
                filter,
                new DealAuditViewDto.EventPosition(Instant.EPOCH, new byte[32]));

        assertMismatch(cursor, deal, new DealAuditQuery.EventFilter(
                LineItemAuditType.CREATED, null, new ProviderObjectId("2002"),
                filter.from(), filter.to()));
        assertMismatch(cursor, deal, new DealAuditQuery.EventFilter(
                LineItemAuditType.PROPERTY_CHANGED, MonitoredLineItemProperty.PRICE,
                new ProviderObjectId("2002"), filter.from(), filter.to()));
        assertMismatch(cursor, deal, new DealAuditQuery.EventFilter(
                LineItemAuditType.PROPERTY_CHANGED, MonitoredLineItemProperty.NAME,
                new ProviderObjectId("2003"), filter.from(), filter.to()));
        assertMismatch(cursor, deal, new DealAuditQuery.EventFilter(
                LineItemAuditType.PROPERTY_CHANGED, MonitoredLineItemProperty.NAME,
                new ProviderObjectId("2002"), filter.from().plusSeconds(1), filter.to()));
        assertMismatch(cursor, deal, new DealAuditQuery.EventFilter(
                LineItemAuditType.PROPERTY_CHANGED, MonitoredLineItemProperty.NAME,
                new ProviderObjectId("2002"), filter.from(), filter.to().plusSeconds(1)));
    }

    @Test
    void previousKeyVerifiesOpenCursorsWhileNewCursorsUseTheActiveKey() {
        String oldKey = key((byte) 0x11);
        String newKey = key((byte) 0x22);
        DealAuditCursorCodec oldCodec = codec("old", oldKey, null, null);
        DealAuditCursorCodec rotated = codec("new", newKey, "old", oldKey);
        DealAuditCursorCodec newOnly = codec("new", newKey, null, null);
        ProviderObjectId deal = new ProviderObjectId("1001");

        String oldCursor = oldCodec.encodeLineItems(
                deal, NO_LINE_FILTER, new ProviderObjectId("2002"));
        String newCursor = rotated.encodeLineItems(
                deal, NO_LINE_FILTER, new ProviderObjectId("2003"));

        assertThat(rotated.decodeLineItems(oldCursor, deal, NO_LINE_FILTER).lineItemId().value())
                .isEqualTo("2002");
        assertThat(newOnly.decodeLineItems(newCursor, deal, NO_LINE_FILTER).lineItemId().value())
                .isEqualTo("2003");
        assertThatThrownBy(() -> newOnly.decodeLineItems(oldCursor, deal, NO_LINE_FILTER))
                .isExactlyInstanceOf(DealAuditRequestException.class);
    }

    @Test
    void legacyV1IsAcceptedOnlyForAnUnfilteredRequest() throws Exception {
        ProviderObjectId deal = new ProviderObjectId("1001");
        String cursor;
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeByte(1);
            output.writeByte(1);
            output.writeUTF(deal.value());
            output.writeUTF("2002");
            cursor = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
        }

        assertThat(codec.decodeLineItems(cursor, deal, NO_LINE_FILTER).lineItemId().value())
                .isEqualTo("2002");
        assertThatThrownBy(() -> codec.decodeLineItems(
                cursor, deal, new DealAuditQuery.LineItemFilter("item")))
                .isExactlyInstanceOf(DealAuditRequestException.class);
    }

    @Test
    void rejectsMalformedTrailingAndOversizedInput() {
        ProviderObjectId deal = new ProviderObjectId("1001");
        String cursor = codec.encodeEvents(
                deal,
                NO_EVENT_FILTER,
                new DealAuditViewDto.EventPosition(Instant.EPOCH, new byte[32]));

        assertThatThrownBy(() -> codec.decodeEvents("%%%", deal, NO_EVENT_FILTER))
                .isExactlyInstanceOf(DealAuditRequestException.class);
        assertThatThrownBy(() -> codec.decodeEvents("A".repeat(1025), deal, NO_EVENT_FILTER))
                .isExactlyInstanceOf(DealAuditRequestException.class);
        assertThatThrownBy(() -> codec.decodeEvents(cursor + "AA", deal, NO_EVENT_FILTER))
                .isExactlyInstanceOf(DealAuditRequestException.class);
    }

    private void assertMismatch(
            String cursor,
            ProviderObjectId deal,
            DealAuditQuery.EventFilter filter) {
        assertThatThrownBy(() -> codec.decodeEvents(cursor, deal, filter))
                .isExactlyInstanceOf(DealAuditRequestException.class);
    }

    private static DealAuditCursorCodec codec(
            String activeId,
            String activeKey,
            String previousId,
            String previousKey) {
        return new DealAuditCursorCodec(
                new DealAuditCursorProperties(activeId, activeKey, previousId, previousKey),
                new HubSpotUiExtensionProperties(
                        true, URI.create("https://api.example.test"), "123"));
    }

    private static String key(byte value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, value);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
