package com.udmconsulting.modules.lineitemwatch.domain;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record LineItemAuditEvent(
        byte[] semanticKey,
        LineItemAuditType type,
        Instant occurredAt,
        MonitoredLineItemProperty property,
        ObservedValue before,
        ObservedValue after,
        ProviderObjectId dealId,
        Set<UUID> sourceSignalIds,
        Set<ProviderObjectId> dealContext) {

    public LineItemAuditEvent {
        Objects.requireNonNull(semanticKey, "semanticKey must not be null");
        if (semanticKey.length != 32) {
            throw new IllegalArgumentException("semanticKey must contain 32 bytes");
        }
        semanticKey = semanticKey.clone();
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(before, "before must not be null");
        Objects.requireNonNull(after, "after must not be null");
        Objects.requireNonNull(sourceSignalIds, "sourceSignalIds must not be null");
        Objects.requireNonNull(dealContext, "dealContext must not be null");
        sourceSignalIds = Set.copyOf(new LinkedHashSet<>(sourceSignalIds));
        dealContext = Set.copyOf(new LinkedHashSet<>(dealContext));

        if (type == LineItemAuditType.PROPERTY_CHANGED && property == null) {
            throw new IllegalArgumentException("property change requires a property");
        }
        if (type != LineItemAuditType.PROPERTY_CHANGED && property != null) {
            throw new IllegalArgumentException("only property change may identify a property");
        }
        boolean association = type == LineItemAuditType.DEAL_ASSOCIATED
                || type == LineItemAuditType.DEAL_DISASSOCIATED;
        if (association != (dealId != null)) {
            throw new IllegalArgumentException("only association events require a Deal");
        }
        boolean validTransition = switch (type) {
            case PROPERTY_CHANGED -> before.state() != ObservedValue.State.PRESENT
                    && after.state() != ObservedValue.State.PRESENT;
            case DEAL_ASSOCIATED -> before.state() != ObservedValue.State.PRESENT
                    && before.state() != ObservedValue.State.VALUE
                    && after.state() == ObservedValue.State.PRESENT;
            case DEAL_DISASSOCIATED -> before.state() != ObservedValue.State.ABSENT
                    && before.state() != ObservedValue.State.VALUE
                    && after.state() == ObservedValue.State.ABSENT;
            case CREATED -> before.state() == ObservedValue.State.ABSENT
                    && after.state() == ObservedValue.State.PRESENT;
            case DELETED -> before.state() != ObservedValue.State.ABSENT
                    && before.state() != ObservedValue.State.VALUE
                    && after.state() == ObservedValue.State.ABSENT;
        };
        if (!validTransition) {
            throw new IllegalArgumentException("audit event state transition is invalid for its type");
        }
    }

    @Override
    public byte[] semanticKey() {
        return semanticKey.clone();
    }
}
