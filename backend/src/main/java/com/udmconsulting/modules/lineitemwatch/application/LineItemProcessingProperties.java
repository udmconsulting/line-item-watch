package com.udmconsulting.modules.lineitemwatch.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("line-item-watch.processing")
public record LineItemProcessingProperties(
        boolean enabled,
        Duration pollDelay,
        int maxPerPoll,
        Duration leaseDuration,
        int maxAttempts,
        Duration baseBackoff,
        Duration maxBackoff) {

    public LineItemProcessingProperties {
        requirePositive(pollDelay, "pollDelay");
        requirePositive(leaseDuration, "leaseDuration");
        requirePositive(baseBackoff, "baseBackoff");
        requirePositive(maxBackoff, "maxBackoff");
        if (maxPerPoll < 1 || maxAttempts < 1) {
            throw new IllegalArgumentException("processing counts must be positive");
        }
        if (maxBackoff.compareTo(baseBackoff) < 0) {
            throw new IllegalArgumentException("maxBackoff must not be less than baseBackoff");
        }
    }

    public Duration retryDelay(int attempt) {
        long multiplier = 1L << Math.min(Math.max(attempt - 1, 0), 30);
        try {
            Duration calculated = baseBackoff.multipliedBy(multiplier);
            return calculated.compareTo(maxBackoff) > 0 ? maxBackoff : calculated;
        } catch (ArithmeticException exception) {
            return maxBackoff;
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
