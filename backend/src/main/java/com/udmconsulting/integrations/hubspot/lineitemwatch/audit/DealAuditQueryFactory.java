package com.udmconsulting.integrations.hubspot.lineitemwatch.audit;

import com.udmconsulting.modules.lineitemwatch.application.DealAuditQuery;
import com.udmconsulting.modules.lineitemwatch.domain.ProviderObjectId;
import jakarta.servlet.http.HttpServletRequest;
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
        return new QueryInput(
                dealId,
                lineItemsLimit,
                lineItemsCursorValue == null
                        ? null : cursorCodec.decodeLineItems(lineItemsCursorValue, dealId),
                eventsLimit,
                eventsCursorValue == null
                        ? null : cursorCodec.decodeEvents(eventsCursorValue, dealId));
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
            DealAuditQuery.LineItemCursor lineItemsCursor,
            int eventsLimit,
            DealAuditQuery.EventCursor eventsCursor) {
    }
}
