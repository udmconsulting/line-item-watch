package com.udmconsulting.modules.lineitemwatch.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LineItemSnapshotDomainTest {

    @Test
    void validatesOpaqueProviderIdsAndRecurringPeriods() {
        assertThat(new ProviderObjectId("486464823492").value()).isEqualTo("486464823492");
        assertThat(RecurringPeriod.parse("P24M").value()).isEqualTo(Period.ofMonths(24));
        assertThat(RecurringPeriod.parse("P24M").canonicalValue()).isEqualTo("P24M");

        assertThatThrownBy(() -> new ProviderObjectId(" 486464823492"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RecurringPeriod.parse("24 months"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void billingStartUsesOnlyOneDirectInputShape() {
        assertThat(BillingStart.on(LocalDate.parse("2026-10-01")).date())
                .isEqualTo(LocalDate.parse("2026-10-01"));
        assertThat(BillingStart.after(BillingStart.DelayUnit.DAYS, 14).delayCount())
                .isEqualTo(14);
        assertThat(BillingStart.unspecified().date()).isNull();

        assertThatThrownBy(() -> new BillingStart(
                LocalDate.parse("2026-10-01"), BillingStart.DelayUnit.MONTHS, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BillingStart.after(BillingStart.DelayUnit.DAYS, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void observationNormalizesBlankOptionalTextWithoutChangingExactDecimals() {
        LineItemObservation observation = new LineItemObservation(
                new ProviderObjectId("line-1"),
                "  Consulting  ",
                new BigDecimal("10.2500"),
                new BigDecimal("19.9900"),
                null,
                new BigDecimal("12.5000"),
                "  monthly  ",
                BillingStart.unspecified(),
                null,
                Instant.parse("2026-09-01T00:00:00Z"),
                Instant.parse("2026-09-02T00:00:00Z"),
                Instant.parse("2026-09-03T00:00:00Z"),
                Set.of(new ProviderObjectId("deal-1")));

        assertThat(observation.name()).isEqualTo("Consulting");
        assertThat(observation.billingFrequency()).isEqualTo("monthly");
        assertThat(observation.quantity()).isEqualByComparingTo("10.2500");
        assertThat(observation.unitPrice()).isEqualByComparingTo("19.9900");
    }
}
