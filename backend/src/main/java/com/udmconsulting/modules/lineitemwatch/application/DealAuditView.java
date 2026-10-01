package com.udmconsulting.modules.lineitemwatch.application;

import com.udmconsulting.modules.lineitemwatch.domain.LineItemAuditType;
import com.udmconsulting.modules.lineitemwatch.domain.LineItemHistoryCoverage;
import com.udmconsulting.modules.lineitemwatch.domain.MonitoredLineItemProperty;
import com.udmconsulting.modules.lineitemwatch.domain.ObservedValue;
import com.udmconsulting.modules.lineitemwatch.domain.ProviderObjectId;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record DealAuditView(
        Page<LineItemSummary> lineItems,
        Page<AuditEvent> events,
        LineItemReliability reliability) {

    public DealAuditView(Page<LineItemSummary> lineItems, Page<AuditEvent> events) {
        this(lineItems, events, LineItemReliability.unknown());
    }

    public DealAuditView {
        Objects.requireNonNull(lineItems, "lineItems must not be null");
        Objects.requireNonNull(events, "events must not be null");
        Objects.requireNonNull(reliability, "reliability must not be null");
    }

    public record Page<T>(List<T> items, boolean hasMore) {
        public Page {
            items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
        }

        public static <T> Page<T> empty() {
            return new Page<>(List.of(), false);
        }
    }

    public record LineItemSummary(
            ProviderObjectId lineItemId,
            boolean deleted,
            Instant deletedAt,
            boolean historicalRelevance,
            Membership currentMembership,
            Membership membershipAtDeletion,
            Map<MonitoredLineItemProperty, ObservedValue> latest,
            HistoryCoverage historyCoverage) {

        public LineItemSummary {
            Objects.requireNonNull(lineItemId, "lineItemId must not be null");
            Objects.requireNonNull(currentMembership, "currentMembership must not be null");
            Objects.requireNonNull(latest, "latest must not be null");
            Objects.requireNonNull(historyCoverage, "historyCoverage must not be null");
            EnumMap<MonitoredLineItemProperty, ObservedValue> copied =
                    new EnumMap<>(MonitoredLineItemProperty.class);
            copied.putAll(latest);
            if (copied.size() != MonitoredLineItemProperty.values().length
                    || copied.values().stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException(
                        "latest must contain every monitored property with an explicit state");
            }
            latest = Map.copyOf(copied);
            if (deleted != (deletedAt != null)) {
                throw new IllegalArgumentException("deleted state and timestamp must agree");
            }
            if (deleted && currentMembership != Membership.NOT_APPLICABLE) {
                throw new IllegalArgumentException("deleted item membership must be not applicable");
            }
            if (deleted != (membershipAtDeletion != null)) {
                throw new IllegalArgumentException("only deleted items carry deletion membership");
            }
        }
    }

    public record HistoryCoverage(
            LineItemHistoryCoverage.Mode mode,
            Instant observedFrom,
            boolean hasUnknownState,
            Instant retainedFrom,
            boolean retentionLimited) {

        public HistoryCoverage(
                LineItemHistoryCoverage.Mode mode,
                Instant observedFrom,
                boolean hasUnknownState) {
            this(mode, observedFrom, hasUnknownState, observedFrom, false);
        }

        public HistoryCoverage {
            Objects.requireNonNull(mode, "mode must not be null");
            Objects.requireNonNull(observedFrom, "observedFrom must not be null");
            Objects.requireNonNull(retainedFrom, "retainedFrom must not be null");
            if (retainedFrom.isBefore(observedFrom)) {
                throw new IllegalArgumentException("retainedFrom must not precede observedFrom");
            }
        }
    }

    public record AuditEvent(
            byte[] semanticKey,
            ProviderObjectId lineItemId,
            ObservedValue latestRetainedLineItemName,
            LineItemAuditType type,
            Instant occurredAt,
            MonitoredLineItemProperty property,
            ObservedValue before,
            ObservedValue after) {

        public AuditEvent {
            Objects.requireNonNull(semanticKey, "semanticKey must not be null");
            if (semanticKey.length != 32) {
                throw new IllegalArgumentException("semanticKey must contain 32 bytes");
            }
            semanticKey = semanticKey.clone();
            Objects.requireNonNull(lineItemId, "lineItemId must not be null");
            Objects.requireNonNull(
                    latestRetainedLineItemName,
                    "latestRetainedLineItemName must not be null");
            Objects.requireNonNull(type, "type must not be null");
            Objects.requireNonNull(occurredAt, "occurredAt must not be null");
            Objects.requireNonNull(before, "before must not be null");
            Objects.requireNonNull(after, "after must not be null");
        }

        @Override
        public byte[] semanticKey() {
            return semanticKey.clone();
        }
    }

    public enum Membership {
        PRESENT,
        ABSENT,
        UNKNOWN,
        NOT_APPLICABLE
    }
}
