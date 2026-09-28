package com.udmconsulting.modules.lineitemwatch.application;

import com.udmconsulting.modules.lineitemwatch.domain.LineItemAuditType;
import com.udmconsulting.modules.lineitemwatch.domain.LineItemHistoryCoverage;
import com.udmconsulting.modules.lineitemwatch.domain.MonitoredLineItemProperty;
import com.udmconsulting.modules.lineitemwatch.domain.ObservedValue;
import com.udmconsulting.modules.lineitemwatch.domain.ProviderObjectId;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface DealAuditViewRepository {

    StoredPage<StoredLineItem> readLineItems(DealAuditQuery query);

    StoredPage<DealAuditView.AuditEvent> readEvents(DealAuditQuery query);

    record StoredPage<T>(List<T> items, boolean hasMore) {
        public StoredPage {
            items = List.copyOf(items);
        }

        public static <T> StoredPage<T> empty() {
            return new StoredPage<>(List.of(), false);
        }
    }

    record StoredLineItem(
            ProviderObjectId lineItemId,
            Instant deletedAt,
            boolean requestedDealPresent,
            boolean dealSetComplete,
            LineItemAuditType latestAssociationType,
            Map<MonitoredLineItemProperty, ObservedValue> properties,
            LineItemHistoryCoverage historyCoverage) {
    }
}
