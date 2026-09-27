package com.udmconsulting.modules.lineitemwatch.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(
        prefix = "line-item-watch.processing",
        name = "enabled",
        havingValue = "true")
public final class LineItemSignalWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(LineItemSignalWorker.class);

    private final LineItemSignalProcessingStore store;
    private final LineItemProcessingProperties properties;
    private final LineItemProcessingMetrics metrics;
    private final Clock clock;

    public LineItemSignalWorker(
            LineItemSignalProcessingStore store,
            LineItemProcessingProperties properties,
            LineItemProcessingMetrics metrics,
            Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.properties = Objects.requireNonNull(properties);
        this.metrics = Objects.requireNonNull(metrics);
        this.clock = Objects.requireNonNull(clock);
    }

    @Scheduled(fixedDelayString = "${line-item-watch.processing.poll-delay:1s}")
    public void poll() {
        for (int processed = 0; processed < properties.maxPerPoll(); processed++) {
            Instant now = clock.instant();
            var claim = store.claimNext(now, properties.leaseDuration(), properties.maxAttempts());
            if (claim.isEmpty()) {
                break;
            }
            process(claim.orElseThrow());
        }
        Instant now = clock.instant();
        metrics.update(store.statistics(now), now);
    }

    private void process(ClaimedLineItemSignal claim) {
        metrics.claimed(claim.reclaimed());
        try {
            Instant completedAt = clock.instant();
            LineItemSignalProcessingStore.ProcessingResult result =
                    store.process(claim, completedAt);
            metrics.processed(claim.receivedAt(), completedAt);
            LOGGER.info(
                    "Line Item signal processed tenantId={} connectionId={} signalId={} "
                            + "attempt={} auditEvents={} deleted={} sparse={}",
                    claim.tenantId(),
                    claim.connectionId(),
                    claim.signalId(),
                    claim.attempt(),
                    result.auditEvents(),
                    result.deleted(),
                    result.sparse());
        } catch (RuntimeException exception) {
            Failure failure = classify(exception);
            Instant failedAt = clock.instant();
            var recorded = store.recordFailure(
                    claim,
                    failure.errorCode(),
                    failure.retryable(),
                    failedAt,
                    properties.retryDelay(claim.attempt()),
                    properties.maxAttempts());
            if (recorded.recorded()) {
                if (recorded.terminal()) {
                    metrics.failed();
                } else {
                    metrics.retried();
                }
            }
            LOGGER.warn(
                    "Line Item signal processing failed tenantId={} connectionId={} signalId={} "
                            + "attempt={} errorCode={} terminal={} exceptionType={}",
                    claim.tenantId(),
                    claim.connectionId(),
                    claim.signalId(),
                    claim.attempt(),
                    failure.errorCode(),
                    recorded.terminal(),
                    exception.getClass().getSimpleName());
        }
    }

    private static Failure classify(RuntimeException exception) {
        if (exception instanceof SignalProcessingException processing) {
            return new Failure(processing.errorCode(), processing.retryable());
        }
        if (exception instanceof TransientDataAccessException
                || exception instanceof ConcurrencyFailureException) {
            return new Failure("TRANSIENT_DATABASE", true);
        }
        return new Failure("INTERNAL_PROCESSING", true);
    }

    private record Failure(String errorCode, boolean retryable) {
    }
}
