package com.udmconsulting.modules.lineitemwatch.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
public final class ReliabilityMetrics {

    private final Map<LineItemReliability.ReconciliationOutcome, Counter> reconciliation;
    private final Map<ReplayOutcome, Counter> replay;
    private final AtomicLong reconciliationAgeSeconds = new AtomicLong();
    private final AtomicLong suspectedGapCount = new AtomicLong();
    private final AtomicLong exhaustedSignalCount = new AtomicLong();

    public ReliabilityMetrics(MeterRegistry registry) {
        Objects.requireNonNull(registry);
        reconciliation = new EnumMap<>(LineItemReliability.ReconciliationOutcome.class);
        for (LineItemReliability.ReconciliationOutcome outcome
                : LineItemReliability.ReconciliationOutcome.values()) {
            reconciliation.put(outcome, Counter.builder("line_item_watch.reconciliation.result")
                    .tag("outcome", outcome.name()).register(registry));
        }
        replay = new EnumMap<>(ReplayOutcome.class);
        for (ReplayOutcome outcome : ReplayOutcome.values()) {
            replay.put(outcome, Counter.builder("line_item_watch.replay.result")
                    .tag("outcome", outcome.name()).register(registry));
        }
        Gauge.builder("line_item_watch.reconciliation.age_seconds",
                reconciliationAgeSeconds, AtomicLong::get).register(registry);
        Gauge.builder("line_item_watch.reliability.suspected_gaps",
                suspectedGapCount, AtomicLong::get).register(registry);
        Gauge.builder("line_item_watch.processing.exhausted_signals",
                exhaustedSignalCount, AtomicLong::get).register(registry);
    }

    public void reconciled(LineItemReliability.ReconciliationOutcome outcome, Instant at) {
        reconciliation.get(outcome).increment();
        reconciliationAgeSeconds.set(0);
    }

    public void replayed(ReplayOutcome outcome) {
        replay.get(outcome).increment();
    }

    public void updateAge(Instant lastReconciledAt, Instant now) {
        reconciliationAgeSeconds.set(lastReconciledAt == null ? 0
                : Math.max(0, Duration.between(lastReconciledAt, now).toSeconds()));
    }

    public void updateCounts(long gaps, long exhausted) {
        suspectedGapCount.set(Math.max(0, gaps));
        exhaustedSignalCount.set(Math.max(0, exhausted));
    }

    public enum ReplayOutcome { SUCCEEDED, FAILED, INSUFFICIENT_EVIDENCE }
}
