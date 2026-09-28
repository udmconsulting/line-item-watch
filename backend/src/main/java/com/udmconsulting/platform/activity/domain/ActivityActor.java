package com.udmconsulting.platform.activity.domain;

import java.util.Objects;

public record ActivityActor(
        ActivityActorType type,
        ActivityActorSource source,
        String reference) {

    public ActivityActor {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(source, "source must not be null");
        if (reference != null && (reference.isBlank() || reference.length() > 255)) {
            throw new IllegalArgumentException("actor reference must be nonblank and at most 255 characters");
        }
        if ((type == ActivityActorType.USER || type == ActivityActorType.OPERATOR)
                && reference == null) {
            throw new IllegalArgumentException("verified user/operator actor requires a reference");
        }
        if ((type == ActivityActorType.SYSTEM || type == ActivityActorType.UNATTRIBUTED)
                && reference != null) {
            throw new IllegalArgumentException("system/unattributed actor must not have a reference");
        }
        if (type == ActivityActorType.SYSTEM && source != ActivityActorSource.APPLICATION) {
            throw new IllegalArgumentException("system actor source must be the application");
        }
        if (type == ActivityActorType.PROVIDER && source != ActivityActorSource.HUBSPOT) {
            throw new IllegalArgumentException("provider actor source must be the provider");
        }
    }

    public static ActivityActor system() {
        return new ActivityActor(ActivityActorType.SYSTEM, ActivityActorSource.APPLICATION, null);
    }

    public static ActivityActor unattributed(ActivityActorSource source) {
        return new ActivityActor(ActivityActorType.UNATTRIBUTED, source, null);
    }
}
