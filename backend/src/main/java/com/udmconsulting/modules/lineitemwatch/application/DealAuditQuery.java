package com.udmconsulting.modules.lineitemwatch.application;

import com.udmconsulting.modules.lineitemwatch.domain.ProviderObjectId;
import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.tenant.domain.TenantId;
import java.time.Instant;
import java.util.Objects;

public record DealAuditQuery(
        TenantId tenantId,
        PlatformConnectionId connectionId,
        ProviderObjectId dealId,
        int lineItemsLimit,
        LineItemCursor lineItemsCursor,
        int eventsLimit,
        EventCursor eventsCursor) {

    public static final int MAX_PAGE_SIZE = 20;

    public DealAuditQuery {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(connectionId, "connectionId must not be null");
        Objects.requireNonNull(dealId, "dealId must not be null");
        validateLimit(lineItemsLimit, "lineItemsLimit");
        validateLimit(eventsLimit, "eventsLimit");
        if (lineItemsLimit == 0 && eventsLimit == 0) {
            throw new IllegalArgumentException("at least one page section must be requested");
        }
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
