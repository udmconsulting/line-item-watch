package com.udmconsulting.platform.supportability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public final class ApplicationOperationMetrics {

    public static final String METRIC_NAME = "application.operation.duration";

    private final MeterRegistry registry;

    public ApplicationOperationMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry);
    }

    public Sample start(ApplicationOperation operation) {
        return new Sample(operation, System.nanoTime());
    }

    public void record(
            ApplicationOperation operation,
            OperationOutcome outcome,
            OperationalErrorCode errorCode,
            Duration duration) {
        Timer.builder(METRIC_NAME)
                .tag("component", operation.component())
                .tag("operation", operation.operation())
                .tag("outcome", outcome.name())
                .tag("error_code", errorCode.name())
                .register(registry)
                .record(duration.isNegative() ? Duration.ZERO : duration);
    }

    public final class Sample {

        private final ApplicationOperation operation;
        private final long startedAt;
        private boolean stopped;

        private Sample(ApplicationOperation operation, long startedAt) {
            this.operation = Objects.requireNonNull(operation);
            this.startedAt = startedAt;
        }

        public void stop(OperationOutcome outcome, OperationalErrorCode errorCode) {
            if (stopped) {
                return;
            }
            stopped = true;
            record(operation, outcome, errorCode, Duration.ofNanos(System.nanoTime() - startedAt));
        }
    }
}
