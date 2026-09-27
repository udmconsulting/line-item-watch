package com.udmconsulting.modules.lineitemwatch.domain;

import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record LineItemProjectionCheckpoint(
        SnapshotKind kind,
        Instant providerCreatedAt,
        Instant providerUpdatedAt,
        Instant observedAt,
        Map<MonitoredLineItemProperty, ObservedValue> properties,
        Set<ProviderObjectId> associatedDealIds) {

    public LineItemProjectionCheckpoint {
        if (kind != SnapshotKind.BASELINE && kind != SnapshotKind.OBSERVED) {
            throw new IllegalArgumentException("checkpoint kind must be BASELINE or OBSERVED");
        }
        Objects.requireNonNull(providerCreatedAt, "providerCreatedAt must not be null");
        Objects.requireNonNull(providerUpdatedAt, "providerUpdatedAt must not be null");
        Objects.requireNonNull(observedAt, "observedAt must not be null");
        Objects.requireNonNull(properties, "properties must not be null");
        Objects.requireNonNull(associatedDealIds, "associatedDealIds must not be null");
        EnumMap<MonitoredLineItemProperty, ObservedValue> copied =
                new EnumMap<>(MonitoredLineItemProperty.class);
        copied.putAll(properties);
        if (copied.size() != MonitoredLineItemProperty.values().length
                || copied.values().stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("checkpoint must know every monitored property");
        }
        properties = Map.copyOf(copied);
        associatedDealIds = Set.copyOf(new LinkedHashSet<>(associatedDealIds));
    }
}
