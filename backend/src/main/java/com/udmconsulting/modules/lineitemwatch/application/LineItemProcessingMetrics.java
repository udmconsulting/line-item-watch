package com.udmconsulting.modules.lineitemwatch.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
public final class LineItemProcessingMetrics {

    private final Counter claimed;
    private final Counter processed;
    private final Counter retried;
    private final Counter failed;
    private final Counter reclaimed;
    private final Timer processingLatency;
    private final AtomicLong backlogGauge = new AtomicLong();
    private final AtomicLong activeGauge = new AtomicLong();
    private final AtomicLong failedGauge = new AtomicLong();
    private final AtomicLong oldestAgeSecondsGauge = new AtomicLong();

    public LineItemProcessingMetrics(MeterRegistry registry) {
        Objects.requireNonNull(registry);
        claimed = registry.counter("line_item_watch.processing.claimed");
        processed = registry.counter("line_item_watch.processing.processed");
        retried = registry.counter("line_item_watch.processing.retried");
        failed = registry.counter("line_item_watch.processing.failed");
        reclaimed = registry.counter("line_item_watch.processing.reclaimed");
        processingLatency = registry.timer("line_item_watch.processing.receipt_to_completion");
        Gauge.builder("line_item_watch.processing.backlog", backlogGauge, AtomicLong::get)
                .register(registry);
        Gauge.builder("line_item_watch.processing.active_claims", activeGauge, AtomicLong::get)
                .register(registry);
        Gauge.builder("line_item_watch.processing.terminal_failures", failedGauge, AtomicLong::get)
                .register(registry);
        Gauge.builder("line_item_watch.processing.oldest_unprocessed_age_seconds",
                        oldestAgeSecondsGauge, AtomicLong::get)
                .register(registry);
    }

    public void claimed(boolean wasReclaimed) {
        claimed.increment();
        if (wasReclaimed) {
            reclaimed.increment();
        }
    }

    public void processed(Instant receivedAt, Instant now) {
        processed.increment();
        Duration latency = Duration.between(receivedAt, now);
        processingLatency.record(latency.isNegative() ? Duration.ZERO : latency);
    }

    public void retried() {
        retried.increment();
    }

    public void failed() {
        failed.increment();
    }

    public void update(LineItemSignalProcessingStore.ProcessingStatistics statistics, Instant now) {
        backlogGauge.set(statistics.backlog());
        activeGauge.set(statistics.claimed());
        failedGauge.set(statistics.failed());
        Instant oldest = statistics.oldestUnprocessedAt();
        oldestAgeSecondsGauge.set(oldest == null
                ? 0
                : Math.max(0, Duration.between(oldest, now).toSeconds()));
    }
}
