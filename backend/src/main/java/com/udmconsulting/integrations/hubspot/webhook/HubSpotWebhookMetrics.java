package com.udmconsulting.integrations.hubspot.webhook;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
final class HubSpotWebhookMetrics {

    private final Map<Failure, Counter> failures = new EnumMap<>(Failure.class);

    HubSpotWebhookMetrics(MeterRegistry registry) {
        for (Failure failure : Failure.values()) {
            failures.put(failure, Counter.builder("hubspot.webhook.ingestion.failures")
                    .tag("error_code", failure.name())
                    .register(registry));
        }
    }

    void failed(Failure failure) {
        failures.get(failure).increment();
    }

    enum Failure {
        BODY_READ,
        DATABASE,
        INTERNAL
    }
}
