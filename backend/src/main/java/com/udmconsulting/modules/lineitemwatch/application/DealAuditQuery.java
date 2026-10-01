package com.udmconsulting.modules.lineitemwatch.application;

import com.udmconsulting.modules.lineitemwatch.domain.ProviderObjectId;
import com.udmconsulting.modules.lineitemwatch.domain.LineItemAuditType;
import com.udmconsulting.modules.lineitemwatch.domain.MonitoredLineItemProperty;
import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.tenant.domain.TenantId;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

public record DealAuditQuery(
        TenantId tenantId,
        PlatformConnectionId connectionId,
        ProviderObjectId dealId,
        int lineItemsLimit,
        LineItemFilter lineItemFilter,
        LineItemCursor lineItemsCursor,
        int eventsLimit,
        EventFilter eventFilter,
        EventCursor eventsCursor) {

    public static final int MAX_PAGE_SIZE = 20;

    public DealAuditQuery {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(connectionId, "connectionId must not be null");
        Objects.requireNonNull(dealId, "dealId must not be null");
        Objects.requireNonNull(lineItemFilter, "lineItemFilter must not be null");
        Objects.requireNonNull(eventFilter, "eventFilter must not be null");
        validateLimit(lineItemsLimit, "lineItemsLimit");
        validateLimit(eventsLimit, "eventsLimit");
        if (lineItemsLimit == 0 && eventsLimit == 0) {
            throw new IllegalArgumentException("at least one page section must be requested");
        }
    }

    public DealAuditQuery(
            TenantId tenantId,
            PlatformConnectionId connectionId,
            ProviderObjectId dealId,
            int lineItemsLimit,
            LineItemCursor lineItemsCursor,
            int eventsLimit,
            EventCursor eventsCursor) {
        this(
                tenantId,
                connectionId,
                dealId,
                lineItemsLimit,
                LineItemFilter.none(),
                lineItemsCursor,
                eventsLimit,
                EventFilter.none(),
                eventsCursor);
    }

    private static void validateLimit(int value, String name) {
        if (value < 0 || value > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(name + " must be between 0 and " + MAX_PAGE_SIZE);
        }
    }

    public record LineItemCursor(ProviderObjectId lineItemId) {
        public LineItemCursor {
            Objects.requireNonNull(lineItemId, "lineItemId must not be null");
        }
    }

    public record LineItemFilter(String search) {
        public LineItemFilter {
            if (search != null && search.isBlank()) {
                throw new IllegalArgumentException("search must be null or non-blank");
            }
        }

        public static LineItemFilter none() {
            return new LineItemFilter(null);
        }

        public boolean active() {
            return search != null;
        }

        public String canonicalSearch() {
            return search == null ? null : search.toLowerCase(Locale.ROOT);
        }
    }

    public record EventFilter(
            LineItemAuditType eventType,
            MonitoredLineItemProperty field,
            ProviderObjectId lineItemId,
            Instant from,
            Instant to) {
        public EventFilter {
            if (field != null
                    && eventType != null
                    && eventType != LineItemAuditType.PROPERTY_CHANGED) {
                throw new IllegalArgumentException(
                        "field can only be combined with PROPERTY_CHANGED");
            }
            if (from != null && to != null && !from.isBefore(to)) {
                throw new IllegalArgumentException("from must be before to");
            }
        }

        public static EventFilter none() {
            return new EventFilter(null, null, null, null, null);
        }

        public boolean active() {
            return eventType != null || field != null || lineItemId != null || from != null || to != null;
        }
    }

    public record EventCursor(Instant occurredAt, byte[] semanticKey) {
        public EventCursor {
            Objects.requireNonNull(occurredAt, "occurredAt must not be null");
            Objects.requireNonNull(semanticKey, "semanticKey must not be null");
            if (semanticKey.length != 32) {
                throw new IllegalArgumentException("semanticKey must contain 32 bytes");
            }
            semanticKey = semanticKey.clone();
        }

        @Override
        public byte[] semanticKey() {
            return semanticKey.clone();
        }
    }
}
