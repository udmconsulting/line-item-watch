package com.udmconsulting.platform.activity.application;

import com.udmconsulting.platform.activity.domain.ActivityActor;
import java.util.Objects;
import java.util.UUID;

public record ActivityContext(ActivityActor actor, UUID diagnosticId) {

    public ActivityContext {
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(diagnosticId, "diagnosticId must not be null");
    }
}
