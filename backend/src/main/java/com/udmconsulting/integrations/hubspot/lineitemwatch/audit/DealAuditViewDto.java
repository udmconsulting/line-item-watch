package com.udmconsulting.integrations.hubspot.lineitemwatch.audit;

import com.udmconsulting.modules.lineitemwatch.application.DealAuditView;
import com.udmconsulting.modules.lineitemwatch.domain.MonitoredLineItemProperty;
import com.udmconsulting.modules.lineitemwatch.domain.ObservedValue;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

record DealAuditViewDto(
        String dealId,
        Section<LineItem> lineItems,
        Section<AuditEvent> events) {

    private static final int MAX_VALUE_CODE_POINTS = 512;
    private static final byte[] PUBLIC_EVENT_ID_DOMAIN =
            "line-item-watch-public-event-v1".getBytes(StandardCharsets.UTF_8);

    static DealAuditViewDto from(
            DealAuditQueryFactory.QueryInput input,
            DealAuditView view,
            DealAuditCursorCodec cursorCodec) {
        List<LineItem> lineItems = view.lineItems().items().stream()
                .map(LineItem::from)
                .toList();
        List<AuditEvent> events = view.events().items().stream()
                .map(AuditEvent::from)
                .toList();
        String nextLineItemCursor = view.lineItems().hasMore() && !view.lineItems().items().isEmpty()
                ? cursorCodec.encodeLineItems(
                        input.dealId(), view.lineItems().items().getLast().lineItemId())
                : null;
        DealAuditView.AuditEvent lastEvent = view.events().items().isEmpty()
                ? null : view.events().items().getLast();
        String nextEventCursor = view.events().hasMore() && lastEvent != null
                ? cursorCodec.encodeEvents(
                        input.dealId(), new EventPosition(lastEvent.occurredAt(), lastEvent.semanticKey()))
                : null;
        return new DealAuditViewDto(
                input.dealId().value(),
                new Section<>(lineItems, new Page(
                        input.lineItemsLimit(), view.lineItems().hasMore(), nextLineItemCursor)),
                new Section<>(events, new Page(
                        input.eventsLimit(), view.events().hasMore(), nextEventCursor)));
    }

    record Section<T>(List<T> items, Page page) {
    }

    record Page(int limit, boolean hasMore, String nextCursor) {
    }

    record LineItem(
            String lineItemId,
            boolean deleted,
            Instant deletedAt,
            boolean historicalRelevance,
            DealMembership dealMembership,
            Map<String, Value> latest,
            HistoryCoverage historyCoverage) {

        static LineItem from(DealAuditView.LineItemSummary source) {
            Map<String, Value> latest = new LinkedHashMap<>();
            for (MonitoredLineItemProperty property : MonitoredLineItemProperty.values()) {
                latest.put(property.apiName(), Value.from(source.latest().get(property)));
            }
            return new LineItem(
                    source.lineItemId().value(),
                    source.deleted(),
                    source.deletedAt(),
                    source.historicalRelevance(),
                    new DealMembership(
                            source.currentMembership().name(),
                            source.membershipAtDeletion() == null
                                    ? null : source.membershipAtDeletion().name()),
                    latest,
                    new HistoryCoverage(
                            source.historyCoverage().mode().name(),
                            source.historyCoverage().observedFrom(),
                            source.historyCoverage().hasUnknownState()));
        }
    }

    record DealMembership(String currentMembership, String membershipAtDeletion) {
    }

    record HistoryCoverage(String mode, Instant observedFrom, boolean hasUnknownState) {
    }

    record AuditEvent(
            String eventId,
            String lineItemId,
            String type,
            Instant occurredAt,
            String field,
            Value before,
            Value after) {

        static AuditEvent from(DealAuditView.AuditEvent source) {
            return new AuditEvent(
                    DealAuditViewDto.eventId(source.semanticKey()),
                    source.lineItemId().value(),
                    source.type().name(),
                    source.occurredAt(),
                    source.property() == null ? null : source.property().apiName(),
                    Value.from(source.before()),
                    Value.from(source.after()));
        }
    }

    record Value(String state, String value, boolean truncated) {
        static Value from(ObservedValue source) {
            String value = source.value();
            if (value == null || value.codePointCount(0, value.length()) <= MAX_VALUE_CODE_POINTS) {
                return new Value(source.state().name(), value, false);
            }
            int end = value.offsetByCodePoints(0, MAX_VALUE_CODE_POINTS);
            return new Value(source.state().name(), value.substring(0, end), true);
        }
    }

    record EventPosition(Instant occurredAt, byte[] semanticKey) {
        EventPosition {
            semanticKey = semanticKey.clone();
        }

        @Override
        public byte[] semanticKey() {
            return semanticKey.clone();
        }
    }

    private static String eventId(byte[] semanticKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(PUBLIC_EVENT_ID_DOMAIN);
            digest.update(semanticKey);
            return "evt_" + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
