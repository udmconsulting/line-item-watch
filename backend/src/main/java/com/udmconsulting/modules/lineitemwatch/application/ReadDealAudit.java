package com.udmconsulting.modules.lineitemwatch.application;

import com.udmconsulting.modules.lineitemwatch.domain.LineItemAuditType;
import com.udmconsulting.modules.lineitemwatch.domain.ObservedValue;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public final class ReadDealAudit {

    private final DealAuditViewRepository repository;

    public ReadDealAudit(DealAuditViewRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    public DealAuditView read(DealAuditQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        DealAuditViewRepository.StoredPage<DealAuditViewRepository.StoredLineItem> storedItems =
                query.lineItemsLimit() == 0
                        ? DealAuditViewRepository.StoredPage.empty()
                        : repository.readLineItems(query);
        DealAuditViewRepository.StoredPage<DealAuditView.AuditEvent> storedEvents =
                query.eventsLimit() == 0
                        ? DealAuditViewRepository.StoredPage.empty()
                        : repository.readEvents(query);

        List<DealAuditView.LineItemSummary> items = storedItems.items().stream()
                .map(ReadDealAudit::summary)
                .toList();
        return new DealAuditView(
                new DealAuditView.Page<>(items, storedItems.hasMore()),
                new DealAuditView.Page<>(storedEvents.items(), storedEvents.hasMore()),
                repository.readReliability(query));
    }

    private static DealAuditView.LineItemSummary summary(
            DealAuditViewRepository.StoredLineItem stored) {
        DealAuditView.Membership knownMembership = membership(stored);
        boolean deleted = stored.deletedAt() != null;
        DealAuditView.Membership current = deleted
                ? DealAuditView.Membership.NOT_APPLICABLE
                : knownMembership;
        DealAuditView.Membership atDeletion = deleted ? knownMembership : null;
        boolean hasUnknownState = stored.properties().values().stream()
                        .anyMatch(value -> value.state() == ObservedValue.State.UNKNOWN)
                || knownMembership == DealAuditView.Membership.UNKNOWN;
        return new DealAuditView.LineItemSummary(
                stored.lineItemId(),
                deleted,
                stored.deletedAt(),
                true,
                current,
                atDeletion,
                stored.properties(),
                new DealAuditView.HistoryCoverage(
                        stored.historyCoverage().mode(),
                        stored.historyCoverage().observedFrom(),
                        hasUnknownState,
                        stored.retainedFrom(),
                        stored.retentionLimited()));
    }

    private static DealAuditView.Membership membership(
            DealAuditViewRepository.StoredLineItem stored) {
        if (stored.requestedDealPresent()) {
            return DealAuditView.Membership.PRESENT;
        }
        if (stored.dealSetComplete()
                || stored.latestAssociationType() == LineItemAuditType.DEAL_DISASSOCIATED) {
            return DealAuditView.Membership.ABSENT;
        }
        if (stored.latestAssociationType() == LineItemAuditType.DEAL_ASSOCIATED) {
            return DealAuditView.Membership.PRESENT;
        }
        return DealAuditView.Membership.UNKNOWN;
    }
}
