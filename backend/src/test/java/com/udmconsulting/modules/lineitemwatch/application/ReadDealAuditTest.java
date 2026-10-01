package com.udmconsulting.modules.lineitemwatch.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.udmconsulting.modules.lineitemwatch.domain.LineItemAuditType;
import com.udmconsulting.modules.lineitemwatch.domain.LineItemHistoryCoverage;
import com.udmconsulting.modules.lineitemwatch.domain.MonitoredLineItemProperty;
import com.udmconsulting.modules.lineitemwatch.domain.ObservedValue;
import com.udmconsulting.modules.lineitemwatch.domain.ProviderObjectId;
import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.tenant.domain.TenantId;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReadDealAuditTest {

    private static final Instant OBSERVED_FROM = Instant.parse("2026-09-20T10:00:00Z");
    private final DealAuditViewRepository repository = mock(DealAuditViewRepository.class);
    private final ReadDealAudit useCase = new ReadDealAudit(repository);

    @Test
    void derivesCurrentAndDeletedMembershipWithoutCollapsingUnknown() {
        DealAuditQuery query = query(20, 20);
        when(repository.readLineItems(query)).thenReturn(new DealAuditViewRepository.StoredPage<>(List.of(
                stored("present", null, true, false, null, completeProperties()),
                stored("absent-complete", null, false, true, null, completeProperties()),
                stored("absent-event", null, false, false,
                        LineItemAuditType.DEAL_DISASSOCIATED, completeProperties()),
                stored("unknown", null, false, false, null, completeProperties()),
                stored("deleted", OBSERVED_FROM.plusSeconds(30), false, false,
                        LineItemAuditType.DEAL_ASSOCIATED, completeProperties())), false));
        when(repository.readEvents(query)).thenReturn(DealAuditViewRepository.StoredPage.empty());
        when(repository.readReliability(query)).thenReturn(LineItemReliability.unknown());

        DealAuditView result = useCase.read(query);

        assertThat(result.lineItems().items())
                .extracting(DealAuditView.LineItemSummary::currentMembership)
                .containsExactly(
                        DealAuditView.Membership.PRESENT,
                        DealAuditView.Membership.ABSENT,
                        DealAuditView.Membership.ABSENT,
                        DealAuditView.Membership.UNKNOWN,
                        DealAuditView.Membership.NOT_APPLICABLE);
        assertThat(result.lineItems().items().get(3).historyCoverage().hasUnknownState()).isTrue();
        assertThat(result.lineItems().items().get(4).membershipAtDeletion())
                .isEqualTo(DealAuditView.Membership.PRESENT);
    }

    @Test
    void anyUnknownPropertyDrivesCoverageUnknownFlag() {
        DealAuditQuery query = query(1, 0);
        Map<MonitoredLineItemProperty, ObservedValue> values = completeProperties();
        values.put(MonitoredLineItemProperty.PRICE, ObservedValue.unknown());
        when(repository.readLineItems(query)).thenReturn(new DealAuditViewRepository.StoredPage<>(
                List.of(stored("line-1", null, true, true, null, values)), false));
        when(repository.readReliability(query)).thenReturn(LineItemReliability.unknown());

        DealAuditView.LineItemSummary item = useCase.read(query).lineItems().items().getFirst();

        assertThat(item.historyCoverage().mode())
                .isEqualTo(LineItemHistoryCoverage.Mode.BASELINE_ANCHORED);
        assertThat(item.historyCoverage().observedFrom()).isEqualTo(OBSERVED_FROM);
        assertThat(item.historyCoverage().hasUnknownState()).isTrue();
        verify(repository, never()).readEvents(query);
    }

    @Test
    void zeroLimitSkipsOnlyItsIndependentPageDomain() {
        DealAuditQuery query = query(0, 1);
        when(repository.readEvents(query)).thenReturn(DealAuditViewRepository.StoredPage.empty());
        when(repository.readReliability(query)).thenReturn(LineItemReliability.unknown());

        DealAuditView result = useCase.read(query);

        assertThat(result.lineItems().items()).isEmpty();
        verify(repository, never()).readLineItems(query);
        verify(repository).readEvents(query);
    }

    private static DealAuditViewRepository.StoredLineItem stored(
            String id,
            Instant deletedAt,
            boolean requestedDealPresent,
            boolean dealSetComplete,
            LineItemAuditType latestAssociation,
            Map<MonitoredLineItemProperty, ObservedValue> values) {
        return new DealAuditViewRepository.StoredLineItem(
                new ProviderObjectId(id),
                deletedAt,
                requestedDealPresent,
                dealSetComplete,
                latestAssociation,
                values,
                new LineItemHistoryCoverage(
                        LineItemHistoryCoverage.Mode.BASELINE_ANCHORED, OBSERVED_FROM));
    }

    private static Map<MonitoredLineItemProperty, ObservedValue> completeProperties() {
        EnumMap<MonitoredLineItemProperty, ObservedValue> values =
                new EnumMap<>(MonitoredLineItemProperty.class);
        for (MonitoredLineItemProperty property : MonitoredLineItemProperty.values()) {
            values.put(property, ObservedValue.absent());
        }
        return values;
    }

    private static DealAuditQuery query(int lineItemsLimit, int eventsLimit) {
        return new DealAuditQuery(
                new TenantId(UUID.randomUUID()),
                new PlatformConnectionId(UUID.randomUUID()),
                new ProviderObjectId("1001"),
                lineItemsLimit,
                null,
                eventsLimit,
                null);
    }
}
