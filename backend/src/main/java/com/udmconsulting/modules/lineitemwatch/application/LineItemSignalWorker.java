package com.udmconsulting.modules.lineitemwatch.application;

import com.udmconsulting.platform.supportability.ApplicationOperation;
import com.udmconsulting.platform.supportability.ApplicationOperationMetrics;
import com.udmconsulting.platform.supportability.DiagnosticContext;
import com.udmconsulting.platform.supportability.OperationOutcome;
import com.udmconsulting.platform.supportability.OperationalErrorCode;
import com.udmconsulting.platform.supportability.SafeDiagnosticException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
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
    private final ApplicationOperationMetrics operationMetrics;
    private final Clock clock;

    public LineItemSignalWorker(
            LineItemSignalProcessingStore store,
            LineItemProcessingProperties properties,
            LineItemProcessingMetrics metrics,
            ApplicationOperationMetrics operationMetrics,
            Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.properties = Objects.requireNonNull(properties);
        this.metrics = Objects.requireNonNull(metrics);
        this.operationMetrics = Objects.requireNonNull(operationMetrics);
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
        UUID operationId = UUID.randomUUID();
        try (DiagnosticContext.Scope ignored = DiagnosticContext.withOperationId(operationId)) {
            ApplicationOperationMetrics.Sample operation =
                    operationMetrics.start(ApplicationOperation.LINE_ITEM_SIGNAL_PROCESS);
            metrics.claimed(claim.reclaimed());
            try {
                Instant completedAt = clock.instant();
                LineItemSignalProcessingStore.ProcessingResult result =
                        store.process(claim, completedAt);
                metrics.processed(claim.receivedAt(), completedAt);
                operation.stop(OperationOutcome.SUCCESS, OperationalErrorCode.NONE);
                LOGGER.atInfo()
                        .addKeyValue("component", "line_item_watch")
                        .addKeyValue("operation", "signal_process")
                        .addKeyValue("result", "SUCCESS")
                        .addKeyValue("tenantRef", claim.tenantId().value())
                        .addKeyValue("connectionRef", claim.connectionId().value())
                        .addKeyValue("signalRef", claim.signalId())
                        .addKeyValue("attempt", claim.attempt())
                        .addKeyValue("auditEventCount", result.auditEvents())
                        .addKeyValue("deleted", result.deleted())
                        .addKeyValue("sparse", result.sparse())
                        .log("Line Item signal processed");
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
                OperationalErrorCode operationalCode = operationalCode(failure.errorCode());
                operation.stop(
                        recorded.terminal() ? OperationOutcome.FAILED : OperationOutcome.RETRY,
                        operationalCode);
                var event = (operationalCode == OperationalErrorCode.INTERNAL_PROCESSING
                                ? LOGGER.atError() : LOGGER.atWarn())
                        .addKeyValue("component", "line_item_watch")
                        .addKeyValue("operation", "signal_process")
                        .addKeyValue("result", recorded.terminal() ? "FAILED" : "RETRY")
                        .addKeyValue("errorCode", failure.errorCode())
                        .addKeyValue("tenantRef", claim.tenantId().value())
                        .addKeyValue("connectionRef", claim.connectionId().value())
                        .addKeyValue("signalRef", claim.signalId())
                        .addKeyValue("attempt", claim.attempt());
                if (operationalCode == OperationalErrorCode.INTERNAL_PROCESSING) {
                    event.setCause(SafeDiagnosticException.from(exception));
                }
                event.log("Line Item signal processing failed");
            }
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

    private static OperationalErrorCode operationalCode(String errorCode) {
        try {
            return OperationalErrorCode.valueOf(errorCode);
        } catch (IllegalArgumentException exception) {
            return OperationalErrorCode.INTERNAL_PROCESSING;
        }
    }

    private record Failure(String errorCode, boolean retryable) {
    }
}
