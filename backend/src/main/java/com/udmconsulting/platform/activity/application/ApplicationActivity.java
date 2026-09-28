package com.udmconsulting.platform.activity.application;

import com.udmconsulting.platform.activity.domain.ActivityAction;
import com.udmconsulting.platform.activity.domain.ActivityActor;
import com.udmconsulting.platform.activity.domain.ActivityResourceType;
import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.tenant.domain.TenantId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ApplicationActivity(
        UUID id,
        TenantId tenantId,
        PlatformConnectionId connectionId,
        Instant occurredAt,
        ActivityActor actor,
        ActivityAction action,
        ActivityResourceType resourceType,
        String resourceReference,
        String previousState,
        String resultingState,
        UUID correlationId) {

    public ApplicationActivity {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(resourceType, "resourceType must not be null");
        requireState(resourceReference, 64, "resourceReference");
        requireState(previousState, 32, "previousState");
        requireState(resultingState, 32, "resultingState");
        Objects.requireNonNull(correlationId, "correlationId must not be null");
    }

    private static void requireState(String value, int maxLength, String name) {
        if (value == null || value.isBlank() || value.length() > maxLength
                || !value.matches("[A-Z][A-Z0-9_-]*|[0-9a-fA-F-]{36}")) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }
}
