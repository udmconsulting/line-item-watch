package com.udmconsulting.modules.lineitemwatch.application;

import java.time.Instant;
import java.util.Objects;

public record LineItemReliability(
        IngestionState ingestionState,
        CoverageState coverageState,
        Instant possibleGapSince,
        Instant lastSignalObservedAt,
        Instant lastSuccessfullyProcessedAt,
        Instant lastReconciledAt,
        ReconciliationOutcome reconciliationOutcome) {

    public LineItemReliability {
        Objects.requireNonNull(ingestionState, "ingestionState must not be null");
        Objects.requireNonNull(coverageState, "coverageState must not be null");
        Objects.requireNonNull(reconciliationOutcome, "reconciliationOutcome must not be null");
        if ((coverageState == CoverageState.POSSIBLE_GAP) != (possibleGapSince != null)) {
            throw new IllegalArgumentException("possible gap state and timestamp must agree");
        }
    }

    public static LineItemReliability unknown() {
        return new LineItemReliability(
                IngestionState.OBSERVING,
                CoverageState.NO_KNOWN_GAP,
                null,
                null,
                null,
                null,
                ReconciliationOutcome.NOT_RUN);
    }

    public enum IngestionState { OBSERVING, PAUSED }

    public enum CoverageState { NO_KNOWN_GAP, POSSIBLE_GAP }

    public enum ReconciliationOutcome {
        NOT_RUN,
        SUCCEEDED,
        DRIFT_REPAIRED,
        UNAVAILABLE,
        CONFLICT
    }
}
