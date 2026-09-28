package com.udmconsulting.platform.supportability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ApplicationOperationMetricsTest {

    @Test
    void emitsOnlyBoundedOwnedTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ApplicationOperationMetrics metrics = new ApplicationOperationMetrics(registry);

        metrics.record(
                ApplicationOperation.DEAL_AUDIT_READ,
                OperationOutcome.FAILED,
                OperationalErrorCode.DATABASE_UNAVAILABLE,
                Duration.ofMillis(12));

        var meter = registry.find(ApplicationOperationMetrics.METRIC_NAME).timer();
        assertThat(meter).isNotNull();
        assertThat(meter.count()).isEqualTo(1);
        assertThat(meter.getId().getTags())
                .extracting(io.micrometer.core.instrument.Tag::getKey)
                .containsExactlyInAnyOrder("component", "operation", "outcome", "error_code");
        assertThat(meter.getId().getTag("component")).isEqualTo("line_item_watch");
        assertThat(meter.getId().getTag("operation")).isEqualTo("deal_audit_read");
        assertThat(meter.getId().getTag("outcome")).isIn(
                Set.of(java.util.Arrays.stream(OperationOutcome.values())
                        .map(Enum::name).toArray(String[]::new)));
        assertThat(meter.getId().getTag("error_code")).isIn(
                Set.of(java.util.Arrays.stream(OperationalErrorCode.values())
                        .map(Enum::name).toArray(String[]::new)));
    }
}
