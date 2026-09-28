package com.udmconsulting.modules.lineitemwatch.domain;

import java.time.Instant;
import java.util.Objects;

public record LineItemHistoryCoverage(Mode mode, Instant observedFrom) {

    public LineItemHistoryCoverage {
        Objects.requireNonNull(mode, "mode must not be null");
        Objects.requireNonNull(observedFrom, "observedFrom must not be null");
    }

    public enum Mode {
        BASELINE_ANCHORED,
        SIGNAL_FIRST
    }
}
