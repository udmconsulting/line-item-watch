package com.udmconsulting.integrations.hubspot.lineitemwatch.audit;

import com.udmconsulting.modules.lineitemwatch.application.DealAuditQuery;
import com.udmconsulting.modules.lineitemwatch.domain.LineItemAuditType;
import com.udmconsulting.modules.lineitemwatch.domain.MonitoredLineItemProperty;
import com.udmconsulting.modules.lineitemwatch.domain.ProviderObjectId;
import jakarta.servlet.http.HttpServletRequest;
import java.time.DateTimeException;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
final class DealAuditQueryFactory {

    private static final int DEFAULT_LINE_ITEMS_LIMIT = 10;
    private static final int DEFAULT_EVENTS_LIMIT = 20;
    private final DealAuditCursorCodec cursorCodec;

    DealAuditQueryFactory(DealAuditCursorCodec cursorCodec) {
        this.cursorCodec = cursorCodec;
    }

    QueryInput create(String dealIdValue, HttpServletRequest request) {
        if (dealIdValue == null || !dealIdValue.matches("[1-9][0-9]{0,254}")) {
            throw new DealAuditRequestException("Deal ID is invalid");
        }
        ProviderObjectId dealId = new ProviderObjectId(dealIdValue);
        int lineItemsLimit = limit(request, "lineItemsLimit", DEFAULT_LINE_ITEMS_LIMIT);
        int eventsLimit = limit(request, "eventsLimit", DEFAULT_EVENTS_LIMIT);
        if (lineItemsLimit == 0 && eventsLimit == 0) {
            throw new DealAuditRequestException("at least one section must be requested");
        }
        String lineItemsCursorValue = optional(request, "lineItemsCursor");
        String eventsCursorValue = optional(request, "eventsCursor");
        DealAuditQuery.LineItemFilter lineItemFilter = new DealAuditQuery.LineItemFilter(
                search(request));
        DealAuditQuery.EventFilter eventFilter = eventFilter(request);
        return new QueryInput(
                dealId,
                lineItemsLimit,
                lineItemFilter,
                lineItemsCursorValue == null
                        ? null : cursorCodec.decodeLineItems(
                                lineItemsCursorValue, dealId, lineItemFilter),
                eventsLimit,
                eventFilter,
                eventsCursorValue == null
                        ? null : cursorCodec.decodeEvents(
                                eventsCursorValue, dealId, eventFilter));
    }

    private static String search(HttpServletRequest request) {
        String[] values = request.getParameterMap().get("lineItemSearch");
        if (values == null) {
            return null;
        }
        if (values.length != 1 || values[0] == null) {
            throw new DealAuditRequestException("request parameter is duplicated");
        }
        String normalized = values[0].strip();
        if (normalized.isEmpty()) {
            return null;
        }
        int length = normalized.codePointCount(0, normalized.length());
        if (length < 2 || length > 100) {
            throw new DealAuditRequestException(
                    "Line Item search must contain between 2 and 100 characters");
        }
        return normalized;
    }

    private static DealAuditQuery.EventFilter eventFilter(HttpServletRequest request) {
        LineItemAuditType eventType = eventType(optional(request, "eventType"));
        MonitoredLineItemProperty field = field(optional(request, "field"));
        if (field != null && eventType == null) {
            eventType = LineItemAuditType.PROPERTY_CHANGED;
        }
        ProviderObjectId lineItemId = providerId(optional(request, "lineItemId"));
        Instant from = instant(optional(request, "from"));
        Instant to = instant(optional(request, "to"));
        try {
            return new DealAuditQuery.EventFilter(eventType, field, lineItemId, from, to);
        } catch (IllegalArgumentException exception) {
            throw new DealAuditRequestException("event filters are invalid", exception);
        }
    }

    private static LineItemAuditType eventType(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LineItemAuditType.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new DealAuditRequestException("event type is unsupported", exception);
        }
    }

    private static MonitoredLineItemProperty field(String value) {
        if (value == null) {
            return null;
        }
        return MonitoredLineItemProperty.fromApiName(value)
                .orElseThrow(() -> new DealAuditRequestException(
                        "changed field is unsupported"));
    }

    private static ProviderObjectId providerId(String value) {
        if (value == null) {
            return null;
        }
        if (!value.matches("[1-9][0-9]{0,254}")) {
            throw new DealAuditRequestException("Line Item ID is invalid");
        }
        return new ProviderObjectId(value);
    }

    private static Instant instant(String value) {
        if (value == null) {
            return null;
        }
        if (value.length() > 64) {
            throw new DealAuditRequestException("timestamp is oversized");
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeException exception) {
            throw new DealAuditRequestException("timestamp is invalid", exception);
        }
    }

    private static int limit(HttpServletRequest request, String name, int defaultValue) {
        String value = optional(request, name);
        if (value == null) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0 || parsed > DealAuditQuery.MAX_PAGE_SIZE) {
                throw new DealAuditRequestException("page limit is outside the allowed range");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new DealAuditRequestException("page limit is invalid", exception);
        }
    }

    private static String optional(HttpServletRequest request, String name) {
        String[] values = request.getParameterMap().get(name);
        if (values == null) {
            return null;
        }
        if (values.length != 1 || values[0] == null || values[0].isBlank()) {
            throw new DealAuditRequestException("request parameter is duplicated or blank");
        }
        return values[0];
    }

    record QueryInput(
            ProviderObjectId dealId,
            int lineItemsLimit,
            DealAuditQuery.LineItemFilter lineItemFilter,
            DealAuditQuery.LineItemCursor lineItemsCursor,
            int eventsLimit,
            DealAuditQuery.EventFilter eventFilter,
            DealAuditQuery.EventCursor eventsCursor) {

        QueryInput(
                ProviderObjectId dealId,
                int lineItemsLimit,
                DealAuditQuery.LineItemCursor lineItemsCursor,
                int eventsLimit,
                DealAuditQuery.EventCursor eventsCursor) {
            this(
                    dealId,
                    lineItemsLimit,
                    DealAuditQuery.LineItemFilter.none(),
                    lineItemsCursor,
                    eventsLimit,
                    DealAuditQuery.EventFilter.none(),
                    eventsCursor);
        }
    }
}
