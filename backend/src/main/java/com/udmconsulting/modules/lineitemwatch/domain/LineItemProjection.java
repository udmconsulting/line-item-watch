package com.udmconsulting.modules.lineitemwatch.domain;

import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record LineItemProjection(
        Map<MonitoredLineItemProperty, ObservedValue> properties,
        Set<ProviderObjectId> associatedDealIds,
        boolean dealSetComplete,
        Instant providerCreatedAt,
        Instant providerUpdatedAt,
        Instant observedAt,
        Instant deletedAt,
        List<LineItemAuditEvent> auditEvents) {

    public LineItemProjection {
        Objects.requireNonNull(properties, "properties must not be null");
        EnumMap<MonitoredLineItemProperty, ObservedValue> copied =
                new EnumMap<>(MonitoredLineItemProperty.class);
        copied.putAll(properties);
        for (MonitoredLineItemProperty property : MonitoredLineItemProperty.values()) {
            copied.putIfAbsent(property, ObservedValue.unknown());
        }
        properties = Map.copyOf(copied);
        associatedDealIds = Set.copyOf(new LinkedHashSet<>(associatedDealIds));
        Objects.requireNonNull(observedAt, "observedAt must not be null");
        auditEvents = List.copyOf(auditEvents);
    }
}
