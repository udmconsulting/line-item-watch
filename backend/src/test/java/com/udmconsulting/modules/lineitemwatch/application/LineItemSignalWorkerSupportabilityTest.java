package com.udmconsulting.modules.lineitemwatch.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.supportability.ApplicationOperationMetrics;
import com.udmconsulting.platform.supportability.DiagnosticContext;
import com.udmconsulting.platform.tenant.domain.TenantId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class LineItemSignalWorkerSupportabilityTest {

    @Test
    void processingErrorsCannotIntroduceUnboundedCodes() {
        assertThatThrownBy(() -> new SignalProcessingException(
                "PROVIDER_SUPPLIED_" + UUID.randomUUID(), true, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void eachClaimedAttemptGetsAnIndependentOperationIdAndKeepsSignalRefSeparate() {
        Instant now = Instant.parse("2026-09-28T10:00:00Z");
        ClaimedLineItemSignal first = claim(now, 1);
        ClaimedLineItemSignal second = claim(now, 2);
        StubStore store = new StubStore(first, second);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LineItemSignalWorker worker = new LineItemSignalWorker(
                store,
                new LineItemProcessingProperties(
                        true, Duration.ofSeconds(1), 2, Duration.ofMinutes(2), 8,
                        Duration.ofSeconds(5), Duration.ofMinutes(15)),
                new LineItemProcessingMetrics(registry),
                new ApplicationOperationMetrics(registry),
                Clock.fixed(now, ZoneOffset.UTC));

        Logger logger = (Logger) LoggerFactory.getLogger(LineItemSignalWorker.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        try {
            worker.poll();
        } finally {
            logger.detachAppender(events);
        }

        List<ILoggingEvent> successes = events.list.stream()
                .filter(event -> event.getFormattedMessage().equals("Line Item signal processed"))
                .toList();
        assertThat(successes).hasSize(2);
        assertThat(successes)
                .extracting(event -> event.getMDCPropertyMap().get(DiagnosticContext.OPERATION_ID))
                .allSatisfy(value -> assertThat(UUID.fromString(value)).isNotNull())
                .doesNotHaveDuplicates();
        assertThat(successes)
                .allSatisfy(event -> assertThat(event.getMDCPropertyMap())
                        .doesNotContainKey(DiagnosticContext.CORRELATION_ID));
        assertThat(successes.stream()
                .flatMap(event -> event.getKeyValuePairs().stream())
                .filter(pair -> pair.key.equals("signalRef"))
                .map(pair -> pair.value)
                .toList()).containsExactlyInAnyOrder(first.signalId(), second.signalId());
    }

    private static ClaimedLineItemSignal claim(Instant now, int attempt) {
        return new ClaimedLineItemSignal(
                UUID.randomUUID(), TenantId.newId(), PlatformConnectionId.newId(),
                UUID.randomUUID(), attempt, now.minusSeconds(30), false);
    }

    private static final class StubStore implements LineItemSignalProcessingStore {
        private final ArrayDeque<ClaimedLineItemSignal> claims;

        private StubStore(ClaimedLineItemSignal... claims) {
            this.claims = new ArrayDeque<>(List.of(claims));
        }

        @Override
        public Optional<ClaimedLineItemSignal> claimNext(
                Instant now, Duration leaseDuration, int maxAttempts) {
            return Optional.ofNullable(claims.poll());
        }

        @Override
        public ProcessingResult process(ClaimedLineItemSignal claim, Instant processedAt) {
            return new ProcessingResult(1, false, false);
        }

        @Override
        public FailureResult recordFailure(
                ClaimedLineItemSignal claim,
                String errorCode,
                boolean retryable,
                Instant failedAt,
                Duration retryDelay,
                int maxAttempts) {
            throw new AssertionError("unexpected failure");
        }

        @Override
        public ProcessingStatistics statistics(Instant now) {
            return new ProcessingStatistics(0, 0, 0, null);
        }
    }
}
