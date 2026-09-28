package com.udmconsulting.modules.lineitemwatch.domain;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

public final class LineItemProjectionReconstructor {

    private static final String KEY_FORMAT = "line-item-watch-audit-v1";
    private static final Comparator<LineItemChangeSignal> STABLE_SIGNAL_ORDER =
            Comparator.comparing(
                    (LineItemChangeSignal signal) -> signal.providerDeduplicationKey().value(),
                    Arrays::compareUnsigned);

    public LineItemProjection reconstruct(
            ProviderObjectId lineItemId,
            List<LineItemProjectionCheckpoint> checkpoints,
            List<LineItemChangeSignal> signals) {
        Objects.requireNonNull(lineItemId, "lineItemId must not be null");
        Objects.requireNonNull(checkpoints, "checkpoints must not be null");
        Objects.requireNonNull(signals, "signals must not be null");
        if (checkpoints.isEmpty() && signals.isEmpty()) {
            throw new IllegalArgumentException("projection requires an observation or signal");
        }
        if (signals.stream().anyMatch(signal -> !lineItemId.equals(signal.lineItemId()))) {
            throw new IllegalArgumentException("all signals must belong to the projected Line Item");
        }

        State state = new State();
        TreeMap<Instant, TimeBucket> timeline = new TreeMap<>();
        checkpoints.forEach(checkpoint -> timeline
                .computeIfAbsent(checkpoint.observedAt(), ignored -> new TimeBucket())
                .checkpoints.add(checkpoint));
        signals.forEach(signal -> timeline
                .computeIfAbsent(signal.occurredAt(), ignored -> new TimeBucket())
                .signals.add(signal));

        for (Map.Entry<Instant, TimeBucket> entry : timeline.entrySet()) {
            Instant occurredAt = entry.getKey();
            TimeBucket bucket = entry.getValue();
            bucket.checkpoints.stream()
                    .sorted(Comparator.comparing(LineItemProjectionCheckpoint::kind))
                    .forEach(state::applyCheckpoint);
            if (state.lifecycle == Lifecycle.DELETED) {
                continue;
            }

            List<LineItemChangeSignal> ordered = bucket.signals.stream()
                    .sorted(STABLE_SIGNAL_ORDER)
                    .toList();
            int firstEventAtTimestamp = state.events.size();
            Set<ProviderObjectId> sameTimeDealContext = new LinkedHashSet<>(state.presentDeals());
            applyCreated(lineItemId, occurredAt, ordered, state);
            applyProperties(lineItemId, occurredAt, ordered, state);
            applyAssociations(lineItemId, occurredAt, ordered, state);
            sameTimeDealContext.addAll(state.presentDeals());
            ordered.stream()
                    .filter(signal -> signal.type() == LineItemChangeSignalType.ASSOCIATION_CHANGED)
                    .map(LineItemChangeSignal::dealId)
                    .forEach(sameTimeDealContext::add);
            state.events.subList(firstEventAtTimestamp, state.events.size())
                    .forEach(event -> event.addDealContext(sameTimeDealContext));
            applyDeleted(lineItemId, occurredAt, ordered, state);
        }

        Instant observedAt = state.observedAt;
        if (observedAt == null) {
            observedAt = signals.stream()
                    .map(LineItemChangeSignal::receivedAt)
                    .min(Instant::compareTo)
                    .orElseThrow();
        }
        Instant coverageObservedFrom = state.baselineObservedFrom == null
                ? earliestEvidence(checkpoints, signals)
                : state.baselineObservedFrom;
        return new LineItemProjection(
                state.properties,
                state.presentDeals(),
                state.dealSetComplete,
                state.providerCreatedAt,
                state.providerUpdatedAt,
                observedAt,
                state.deletedAt,
                new LineItemHistoryCoverage(
                        state.baselineObservedFrom == null
                                ? LineItemHistoryCoverage.Mode.SIGNAL_FIRST
                                : LineItemHistoryCoverage.Mode.BASELINE_ANCHORED,
                        coverageObservedFrom),
                state.events.stream().map(MutableEvent::immutable).toList());
    }

    private static Instant earliestEvidence(
            List<LineItemProjectionCheckpoint> checkpoints,
            List<LineItemChangeSignal> signals) {
        return java.util.stream.Stream.concat(
                        checkpoints.stream().map(LineItemProjectionCheckpoint::observedAt),
                        signals.stream().map(LineItemChangeSignal::occurredAt))
                .min(Instant::compareTo)
                .orElseThrow();
    }

    private static void applyCreated(
            ProviderObjectId lineItemId,
            Instant occurredAt,
            List<LineItemChangeSignal> signals,
            State state) {
        List<LineItemChangeSignal> created = ofType(signals, LineItemChangeSignalType.CREATED);
        if (created.isEmpty()) {
            return;
        }
        state.observe(created);
        if (state.lifecycle == Lifecycle.UNKNOWN) {
            state.lifecycle = Lifecycle.PRESENT;
            state.providerCreatedAt = occurredAt;
            MutableEvent event = event(
                    lineItemId,
                    LineItemAuditType.CREATED,
                    occurredAt,
                    null,
                    ObservedValue.absent(),
                    ObservedValue.present(),
                    null,
                    created,
                    state.presentDeals());
            state.events.add(event);
            state.creationEvent = event;
        } else if (state.creationEvent != null) {
            state.creationEvent.addSources(created);
        }
    }

    private static void applyProperties(
            ProviderObjectId lineItemId,
            Instant occurredAt,
            List<LineItemChangeSignal> signals,
            State state) {
        Map<MonitoredLineItemProperty, List<LineItemChangeSignal>> grouped =
                new EnumMap<>(MonitoredLineItemProperty.class);
        signals.stream()
                .filter(signal -> signal.type() == LineItemChangeSignalType.PROPERTY_CHANGED)
                .forEach(signal -> grouped
                        .computeIfAbsent(signal.property(), ignored -> new ArrayList<>())
                        .add(signal));
        if (grouped.isEmpty()) {
            return;
        }
        state.observe(grouped.values().stream().flatMap(List::stream).toList());
        if (state.lifecycle == Lifecycle.UNKNOWN) {
            state.lifecycle = Lifecycle.PRESENT;
        }

        Map<MonitoredLineItemProperty, ObservedValue> before = new EnumMap<>(
                MonitoredLineItemProperty.class);
        Map<MonitoredLineItemProperty, ObservedValue> resolved = new EnumMap<>(
                MonitoredLineItemProperty.class);
        grouped.forEach((property, propertySignals) -> {
            before.put(property, state.properties.get(property));
            LinkedHashSet<ObservedValue> distinct = new LinkedHashSet<>();
            propertySignals.forEach(signal -> distinct.add(
                    LineItemPropertyValues.normalize(property, signal.propertyValue())));
            resolved.put(property, distinct.size() == 1
                    ? distinct.iterator().next()
                    : ObservedValue.unknown());
        });
        applyResolvedProperties(state.properties, grouped.keySet(), resolved);
        state.providerUpdatedAt = later(state.providerUpdatedAt, occurredAt);

        for (MonitoredLineItemProperty property : MonitoredLineItemProperty.values()) {
            List<LineItemChangeSignal> propertySignals = grouped.get(property);
            if (propertySignals == null) {
                continue;
            }
            state.events.add(event(
                    lineItemId,
                    LineItemAuditType.PROPERTY_CHANGED,
                    occurredAt,
                    property,
                    before.get(property),
                    resolved.get(property),
                    null,
                    propertySignals,
                    state.presentDeals()));
        }
    }

    private static void applyResolvedProperties(
            Map<MonitoredLineItemProperty, ObservedValue> state,
            Set<MonitoredLineItemProperty> signalled,
            Map<MonitoredLineItemProperty, ObservedValue> resolved) {
        Set<MonitoredLineItemProperty> billingStartProperties = Set.of(
                MonitoredLineItemProperty.BILLING_START_DATE,
                MonitoredLineItemProperty.BILLING_START_DELAY_DAYS,
                MonitoredLineItemProperty.BILLING_START_DELAY_MONTHS);
        List<MonitoredLineItemProperty> signalledBillingStart = signalled.stream()
                .filter(billingStartProperties::contains)
                .toList();
        long values = signalledBillingStart.stream()
                .filter(property -> resolved.get(property).state() == ObservedValue.State.VALUE)
                .count();
        boolean hasUnknown = signalledBillingStart.stream()
                .anyMatch(property -> resolved.get(property).state() == ObservedValue.State.UNKNOWN);
        boolean ambiguousComposite = values > 1 || hasUnknown;

        signalled.stream()
                .filter(property -> !billingStartProperties.contains(property))
                .forEach(property -> LineItemPropertyValues.apply(
                        state, property, resolved.get(property)));
        if (ambiguousComposite) {
            billingStartProperties.forEach(property -> state.put(property, ObservedValue.unknown()));
            signalledBillingStart.forEach(property -> resolved.put(property, ObservedValue.unknown()));
            return;
        }

        signalledBillingStart.stream()
                .filter(property -> resolved.get(property).state() != ObservedValue.State.VALUE)
                .forEach(property -> LineItemPropertyValues.apply(
                        state, property, resolved.get(property)));
        signalledBillingStart.stream()
                .filter(property -> resolved.get(property).state() == ObservedValue.State.VALUE)
                .findFirst()
                .ifPresent(property -> LineItemPropertyValues.apply(
                        state, property, resolved.get(property)));
    }

    private static void applyAssociations(
            ProviderObjectId lineItemId,
            Instant occurredAt,
            List<LineItemChangeSignal> signals,
            State state) {
        Map<ProviderObjectId, List<LineItemChangeSignal>> grouped = new LinkedHashMap<>();
        signals.stream()
                .filter(signal -> signal.type() == LineItemChangeSignalType.ASSOCIATION_CHANGED)
                .forEach(signal -> grouped
                        .computeIfAbsent(signal.dealId(), ignored -> new ArrayList<>())
                        .add(signal));
        grouped.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(ProviderObjectId::value)))
                .forEach(entry -> applyAssociationGroup(
                        lineItemId, occurredAt, entry.getKey(), entry.getValue(), state));
    }

    private static void applyAssociationGroup(
            ProviderObjectId lineItemId,
            Instant occurredAt,
            ProviderObjectId dealId,
            List<LineItemChangeSignal> signals,
            State state) {
        state.observe(signals);
        if (state.lifecycle == Lifecycle.UNKNOWN) {
            state.lifecycle = Lifecycle.PRESENT;
        }
        Set<AssociationAction> actions = signals.stream()
                .map(LineItemChangeSignal::associationAction)
                .collect(java.util.stream.Collectors.toSet());
        if (actions.size() != 1) {
            state.dealStates.put(dealId, Membership.UNKNOWN);
            state.dealSetComplete = false;
            state.lastAssociationEvent.remove(dealId);
            return;
        }

        AssociationAction action = actions.iterator().next();
        Membership beforeMembership = state.membership(dealId);
        Membership afterMembership = action == AssociationAction.ADDED
                ? Membership.PRESENT
                : Membership.ABSENT;
        if (beforeMembership == afterMembership) {
            MutableEvent prior = state.lastAssociationEvent.get(dealId);
            if (prior != null && prior.after.equals(afterMembership.observedValue())) {
                prior.addSources(signals);
            }
            return;
        }

        Set<ProviderObjectId> context = new LinkedHashSet<>(state.presentDeals());
        state.dealStates.put(dealId, afterMembership);
        context.addAll(state.presentDeals());
        context.add(dealId);
        MutableEvent event = event(
                lineItemId,
                action == AssociationAction.ADDED
                        ? LineItemAuditType.DEAL_ASSOCIATED
                        : LineItemAuditType.DEAL_DISASSOCIATED,
                occurredAt,
                null,
                beforeMembership.observedValue(),
                afterMembership.observedValue(),
                dealId,
                signals,
                context);
        state.events.add(event);
        state.lastAssociationEvent.put(dealId, event);
    }

    private static void applyDeleted(
            ProviderObjectId lineItemId,
            Instant occurredAt,
            List<LineItemChangeSignal> signals,
            State state) {
        List<LineItemChangeSignal> deleted = ofType(signals, LineItemChangeSignalType.DELETED);
        if (deleted.isEmpty()) {
            return;
        }
        state.observe(deleted);
        ObservedValue before = state.lifecycle == Lifecycle.PRESENT
                ? ObservedValue.present()
                : ObservedValue.unknown();
        state.events.add(event(
                lineItemId,
                LineItemAuditType.DELETED,
                occurredAt,
                null,
                before,
                ObservedValue.absent(),
                null,
                deleted,
                state.presentDeals()));
        state.lifecycle = Lifecycle.DELETED;
        state.deletedAt = occurredAt;
    }

    private static List<LineItemChangeSignal> ofType(
            List<LineItemChangeSignal> signals, LineItemChangeSignalType type) {
        return signals.stream().filter(signal -> signal.type() == type).toList();
    }

    private static MutableEvent event(
            ProviderObjectId lineItemId,
            LineItemAuditType type,
            Instant occurredAt,
            MonitoredLineItemProperty property,
            ObservedValue before,
            ObservedValue after,
            ProviderObjectId dealId,
            List<LineItemChangeSignal> sources,
            Set<ProviderObjectId> dealContext) {
        return new MutableEvent(
                semanticKey(lineItemId, type, occurredAt, property, after, dealId),
                type,
                occurredAt,
                property,
                before,
                after,
                dealId,
                sources.stream().map(LineItemChangeSignal::id).collect(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new)),
                new LinkedHashSet<>(dealContext));
    }

    private static byte[] semanticKey(
            ProviderObjectId lineItemId,
            LineItemAuditType type,
            Instant occurredAt,
            MonitoredLineItemProperty property,
            ObservedValue after,
            ProviderObjectId dealId) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                write(output, KEY_FORMAT);
                write(output, lineItemId.value());
                write(output, type.name());
                write(output, occurredAt.toString());
                write(output, property == null ? "" : property.providerName());
                write(output, after.state().name());
                write(output, after.value() == null ? "" : after.value());
                write(output, dealId == null ? "" : dealId.value());
            }
            return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
        } catch (IOException exception) {
            throw new IllegalStateException("in-memory semantic key encoding failed", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void write(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static Instant later(Instant first, Instant second) {
        return first == null || second.isAfter(first) ? second : first;
    }

    private enum Lifecycle {
        UNKNOWN,
        PRESENT,
        DELETED
    }

    private enum Membership {
        UNKNOWN,
        ABSENT,
        PRESENT;

        ObservedValue observedValue() {
            return switch (this) {
                case UNKNOWN -> ObservedValue.unknown();
                case ABSENT -> ObservedValue.absent();
                case PRESENT -> ObservedValue.present();
            };
        }
    }

    private static final class State {
        private final EnumMap<MonitoredLineItemProperty, ObservedValue> properties =
                new EnumMap<>(MonitoredLineItemProperty.class);
        private final Map<ProviderObjectId, Membership> dealStates = new HashMap<>();
        private final Map<ProviderObjectId, MutableEvent> lastAssociationEvent = new HashMap<>();
        private final List<MutableEvent> events = new ArrayList<>();
        private Lifecycle lifecycle = Lifecycle.UNKNOWN;
        private boolean dealSetComplete;
        private Instant providerCreatedAt;
        private Instant providerUpdatedAt;
        private Instant observedAt;
        private Instant deletedAt;
        private Instant baselineObservedFrom;
        private MutableEvent creationEvent;

        private State() {
            for (MonitoredLineItemProperty property : MonitoredLineItemProperty.values()) {
                properties.put(property, ObservedValue.unknown());
            }
        }

        private void applyCheckpoint(LineItemProjectionCheckpoint checkpoint) {
            if (lifecycle == Lifecycle.DELETED) {
                return;
            }
            if (checkpoint.kind() == SnapshotKind.BASELINE && baselineObservedFrom == null) {
                baselineObservedFrom = checkpoint.observedAt();
            }
            properties.clear();
            properties.putAll(checkpoint.properties());
            dealStates.clear();
            checkpoint.associatedDealIds().forEach(deal -> dealStates.put(deal, Membership.PRESENT));
            lastAssociationEvent.clear();
            dealSetComplete = true;
            lifecycle = Lifecycle.PRESENT;
            providerCreatedAt = checkpoint.providerCreatedAt();
            providerUpdatedAt = checkpoint.providerUpdatedAt();
            observedAt = later(observedAt, checkpoint.observedAt());
        }

        private void observe(List<LineItemChangeSignal> signals) {
            for (LineItemChangeSignal signal : signals) {
                observedAt = later(observedAt, signal.receivedAt());
            }
        }

        private Membership membership(ProviderObjectId dealId) {
            Membership explicit = dealStates.get(dealId);
            return explicit == null
                    ? (dealSetComplete ? Membership.ABSENT : Membership.UNKNOWN)
                    : explicit;
        }

        private Set<ProviderObjectId> presentDeals() {
            LinkedHashSet<ProviderObjectId> present = new LinkedHashSet<>();
            dealStates.entrySet().stream()
                    .filter(entry -> entry.getValue() == Membership.PRESENT)
                    .map(Map.Entry::getKey)
                    .sorted(Comparator.comparing(ProviderObjectId::value))
                    .forEach(present::add);
            return present;
        }
    }

    private static final class TimeBucket {
        private final List<LineItemProjectionCheckpoint> checkpoints = new ArrayList<>();
        private final List<LineItemChangeSignal> signals = new ArrayList<>();
    }

    private static final class MutableEvent {
        private final byte[] semanticKey;
        private final LineItemAuditType type;
        private final Instant occurredAt;
        private final MonitoredLineItemProperty property;
        private final ObservedValue before;
        private final ObservedValue after;
        private final ProviderObjectId dealId;
        private final Set<UUID> sourceSignalIds;
        private final Set<ProviderObjectId> dealContext;

        private MutableEvent(
                byte[] semanticKey,
                LineItemAuditType type,
                Instant occurredAt,
                MonitoredLineItemProperty property,
                ObservedValue before,
                ObservedValue after,
                ProviderObjectId dealId,
                Set<UUID> sourceSignalIds,
                Set<ProviderObjectId> dealContext) {
            this.semanticKey = semanticKey;
            this.type = type;
            this.occurredAt = occurredAt;
            this.property = property;
            this.before = before;
            this.after = after;
            this.dealId = dealId;
            this.sourceSignalIds = sourceSignalIds;
            this.dealContext = dealContext;
        }

        private void addSources(List<LineItemChangeSignal> signals) {
            signals.stream().map(LineItemChangeSignal::id).forEach(sourceSignalIds::add);
        }

        private void addDealContext(Set<ProviderObjectId> deals) {
            dealContext.addAll(deals);
        }

        private LineItemAuditEvent immutable() {
            return new LineItemAuditEvent(
                    semanticKey,
                    type,
                    occurredAt,
                    property,
                    before,
                    after,
                    dealId,
                    sourceSignalIds,
                    dealContext);
        }
    }
}
