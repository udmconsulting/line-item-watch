package com.udmconsulting.platform.credential.application;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
public final class CredentialKeyRotationMetrics {

    private final AtomicLong previousKeyRemaining = new AtomicLong();

    public CredentialKeyRotationMetrics(MeterRegistry registry) {
        Gauge.builder("platform.credential.rewrap.remaining", previousKeyRemaining, AtomicLong::get)
                .register(registry);
    }

    void remaining(long count) {
        previousKeyRemaining.set(Math.max(0, count));
    }
}
