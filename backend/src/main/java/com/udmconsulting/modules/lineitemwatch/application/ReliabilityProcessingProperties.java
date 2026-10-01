package com.udmconsulting.modules.lineitemwatch.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "line-item-watch.reliability")
public record ReliabilityProcessingProperties(
        boolean enabled,
        Duration pollDelay,
        int maxPerPoll,
        int expansionPageSize,
        Duration leaseDuration,
        int maxAttempts,
        Duration baseBackoff,
        Duration maxBackoff,
        Duration reconciliationInterval,
        int reconciliationScheduleBatchSize) {

    public ReliabilityProcessingProperties {
        pollDelay = positive(pollDelay, Duration.ofSeconds(5));
        maxPerPoll = positive(maxPerPoll, 10);
        expansionPageSize = positive(expansionPageSize, 100);
        leaseDuration = positive(leaseDuration, Duration.ofMinutes(5));
        maxAttempts = positive(maxAttempts, 6);
        baseBackoff = positive(baseBackoff, Duration.ofSeconds(10));
        maxBackoff = positive(maxBackoff, Duration.ofMinutes(15));
        reconciliationInterval = positive(reconciliationInterval, Duration.ofHours(6));
        reconciliationScheduleBatchSize = positive(
                reconciliationScheduleBatchSize, Math.min(expansionPageSize, 100));
    }

    private static int positive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private static Duration positive(Duration value, Duration fallback) {
        return value != null && !value.isNegative() && !value.isZero() ? value : fallback;
    }
}
