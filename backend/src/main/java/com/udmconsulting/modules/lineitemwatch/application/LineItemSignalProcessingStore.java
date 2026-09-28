package com.udmconsulting.modules.lineitemwatch.application;

import com.udmconsulting.platform.supportability.OperationalErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public interface LineItemSignalProcessingStore {

    Optional<ClaimedLineItemSignal> claimNext(Instant now, Duration leaseDuration, int maxAttempts);

    ProcessingResult process(ClaimedLineItemSignal claim, Instant processedAt);

    FailureResult recordFailure(
            ClaimedLineItemSignal claim,
            OperationalErrorCode errorCode,
            boolean retryable,
            Instant failedAt,
            Duration retryDelay,
            int maxAttempts);

    ProcessingStatistics statistics(Instant now);

    record ProcessingResult(int auditEvents, boolean deleted, boolean sparse) {
    }

    record FailureResult(boolean recorded, boolean terminal) {
    }

    record ProcessingStatistics(long backlog, long claimed, long failed, Instant oldestUnprocessedAt) {
    }
}
