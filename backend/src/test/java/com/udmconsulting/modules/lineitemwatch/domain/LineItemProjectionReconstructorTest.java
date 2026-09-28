package com.udmconsulting.modules.lineitemwatch.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.tenant.domain.TenantId;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LineItemProjectionReconstructorTest {

    private static final TenantId TENANT = new TenantId(UUID.randomUUID());
    private static final PlatformConnectionId CONNECTION =
            new PlatformConnectionId(UUID.randomUUID());
    private static final ProviderObjectId LINE_ITEM = new ProviderObjectId("line-1");
    private static final ProviderObjectId DEAL = new ProviderObjectId("deal-1");
    private final LineItemProjectionReconstructor reconstructor =
            new LineItemProjectionReconstructor();

    @Test
    void signalFirstPropertyStartsUnknownAndDeletionFreezesCleanup() {
        Instant createdAt = Instant.parse("2026-09-27T12:00:00Z");
        LineItemProjection result = reconstructor.reconstruct(
                LINE_ITEM,
                List.of(),
                List.of(
                        created("created", createdAt),
                        property("name", createdAt.plusSeconds(1), MonitoredLineItemProperty.NAME, "Observed"),
                        association("add-19", createdAt.plusSeconds(2), AssociationAction.ADDED, "19"),
                        deleted("deleted", createdAt.plusSeconds(3)),
                        association("remove-20", createdAt.plusSeconds(4), AssociationAction.REMOVED, "20")));

        LineItemAuditEvent property = result.auditEvents().stream()
                .filter(event -> event.type() == LineItemAuditType.PROPERTY_CHANGED)
                .findFirst().orElseThrow();
        assertThat(property.before()).isEqualTo(ObservedValue.unknown());
        assertThat(property.after()).isEqualTo(ObservedValue.value("Observed"));
        assertThat(result.auditEvents()).extracting(LineItemAuditEvent::type)
                .containsExactly(
                        LineItemAuditType.CREATED,
                        LineItemAuditType.PROPERTY_CHANGED,
                        LineItemAuditType.DEAL_ASSOCIATED,
                        LineItemAuditType.DELETED);
        assertThat(result.associatedDealIds()).containsExactly(DEAL);
        assertThat(result.dealSetComplete()).isFalse();
        assertThat(result.deletedAt()).isEqualTo(createdAt.plusSeconds(3));
    }

    @Test
    void directionalAddsAtDifferentTimesBecomeOneTransitionWithBothSources() {
        LineItemChangeSignal first = association(
                "add-19", Instant.parse("2026-09-27T12:00:00Z"), AssociationAction.ADDED, "19");
        LineItemChangeSignal second = association(
                "add-20", Instant.parse("2026-09-27T12:00:01Z"), AssociationAction.ADDED, "20");

        LineItemProjection result = reconstructor.reconstruct(
                LINE_ITEM, List.of(), List.of(first, second));

        assertThat(result.auditEvents()).hasSize(1);
        assertThat(result.auditEvents().getFirst().type())
                .isEqualTo(LineItemAuditType.DEAL_ASSOCIATED);
        assertThat(result.auditEvents().getFirst().sourceSignalIds())
                .containsExactlyInAnyOrder(first.id(), second.id());
    }

    @Test
    void addRemoveAddAreThreeRealTransitions() {
        LineItemProjection result = reconstructor.reconstruct(
                LINE_ITEM,
                List.of(),
                List.of(
                        association("add-1", Instant.parse("2026-09-27T12:00:00Z"), AssociationAction.ADDED, "19"),
                        association("remove", Instant.parse("2026-09-27T12:01:00Z"), AssociationAction.REMOVED, "20"),
                        association("add-2", Instant.parse("2026-09-27T12:02:00Z"), AssociationAction.ADDED, "19")));

        assertThat(result.auditEvents()).extracting(LineItemAuditEvent::type)
                .containsExactly(
                        LineItemAuditType.DEAL_ASSOCIATED,
                        LineItemAuditType.DEAL_DISASSOCIATED,
                        LineItemAuditType.DEAL_ASSOCIATED);
    }

    @Test
    void createdPropertyAndDeletedAtSameTimestampUseLifecyclePrecedence() {
        Instant occurredAt = Instant.parse("2026-09-27T12:00:00Z");

        LineItemProjection result = reconstructor.reconstruct(
                LINE_ITEM,
                List.of(),
                List.of(
                        deleted("delete", occurredAt),
                        property("quantity", occurredAt, MonitoredLineItemProperty.QUANTITY, "2.00"),
                        created("create", occurredAt)));

        assertThat(result.auditEvents()).extracting(LineItemAuditEvent::type)
                .containsExactly(
                        LineItemAuditType.CREATED,
                        LineItemAuditType.PROPERTY_CHANGED,
                        LineItemAuditType.DELETED);
        assertThat(result.properties().get(MonitoredLineItemProperty.QUANTITY))
                .isEqualTo(ObservedValue.value("2"));
        assertThat(result.deletedAt()).isEqualTo(occurredAt);
    }

    @Test
    void sameTimestampAssociationDoesNotDependOnPropertyApplicationOrderForDealContext() {
        Instant occurredAt = Instant.parse("2026-09-27T12:00:00Z");

        LineItemProjection result = reconstructor.reconstruct(
                LINE_ITEM,
                List.of(),
                List.of(
                        property("name", occurredAt, MonitoredLineItemProperty.NAME, "Observed"),
                        association("add", occurredAt, AssociationAction.ADDED, "unexpected-type")));

        LineItemAuditEvent propertyEvent = result.auditEvents().stream()
                .filter(event -> event.type() == LineItemAuditType.PROPERTY_CHANGED)
                .findFirst()
                .orElseThrow();
        assertThat(propertyEvent.dealContext()).containsExactly(DEAL);
    }

    @Test
    void conflictingPropertyValuesAtSameTimestampProduceUnknown() {
        Instant occurredAt = Instant.parse("2026-09-27T12:00:00Z");

        LineItemProjection result = reconstructor.reconstruct(
                LINE_ITEM,
                List.of(),
                List.of(
                        property("first", occurredAt, MonitoredLineItemProperty.QUANTITY, "2"),
                        property("second", occurredAt, MonitoredLineItemProperty.QUANTITY, "3")));

        assertThat(result.properties().get(MonitoredLineItemProperty.QUANTITY))
                .isEqualTo(ObservedValue.unknown());
        assertThat(result.auditEvents()).singleElement().satisfies(event -> {
            assertThat(event.before()).isEqualTo(ObservedValue.unknown());
            assertThat(event.after()).isEqualTo(ObservedValue.unknown());
            assertThat(event.sourceSignalIds()).hasSize(2);
        });
    }

    @Test
    void mutuallyExclusiveBillingStartValuesAtSameTimestampDoNotUseEnumOrder() {
        Instant occurredAt = Instant.parse("2026-09-27T12:00:00Z");

        LineItemProjection result = reconstructor.reconstruct(
                LINE_ITEM,
                List.of(),
                List.of(
                        property("date", occurredAt,
                                MonitoredLineItemProperty.BILLING_START_DATE, "2026-10-01"),
                        property("days", occurredAt,
                                MonitoredLineItemProperty.BILLING_START_DELAY_DAYS, "14")));

        assertThat(result.properties().get(MonitoredLineItemProperty.BILLING_START_DATE))
                .isEqualTo(ObservedValue.unknown());
        assertThat(result.properties().get(MonitoredLineItemProperty.BILLING_START_DELAY_DAYS))
                .isEqualTo(ObservedValue.unknown());
        assertThat(result.properties().get(MonitoredLineItemProperty.BILLING_START_DELAY_MONTHS))
                .isEqualTo(ObservedValue.unknown());
        assertThat(result.auditEvents()).extracting(LineItemAuditEvent::after)
                .containsOnly(ObservedValue.unknown());
    }

    @Test
    void emptyPropertySignalIsKnownAbsentRatherThanUnknown() {
        LineItemProjection result = reconstructor.reconstruct(
                LINE_ITEM,
                List.of(),
                List.of(property(
                        "clear", Instant.parse("2026-09-27T12:00:00Z"),
                        MonitoredLineItemProperty.NAME, "")));

        assertThat(result.properties().get(MonitoredLineItemProperty.NAME))
                .isEqualTo(ObservedValue.absent());
        assertThat(result.properties().get(MonitoredLineItemProperty.QUANTITY))
                .isEqualTo(ObservedValue.unknown());
        assertThat(result.auditEvents().getFirst().after())
                .isEqualTo(ObservedValue.absent());
    }

    @Test
    void baselineAnchorsKnownPredecessorAndOutOfOrderInputIsDeterministic() {
        Instant baselineTime = Instant.parse("2026-09-27T10:00:00Z");
        LineItemProjectionCheckpoint baseline = checkpoint(baselineTime, "Baseline");
        LineItemChangeSignal earlier = property(
                "earlier", baselineTime.plusSeconds(10), MonitoredLineItemProperty.NAME, "Earlier");
        LineItemChangeSignal later = property(
                "later", baselineTime.plusSeconds(20), MonitoredLineItemProperty.NAME, "Later");

        LineItemProjection chronological = reconstructor.reconstruct(
                LINE_ITEM, List.of(baseline), List.of(earlier, later));
        LineItemProjection reversed = reconstructor.reconstruct(
                LINE_ITEM, List.of(baseline), List.of(later, earlier));

        assertThat(chronological.properties()).isEqualTo(reversed.properties());
        assertThat(chronological.auditEvents().stream().map(LineItemAuditEvent::semanticKey).toList())
                .usingRecursiveComparison()
                .isEqualTo(reversed.auditEvents().stream().map(LineItemAuditEvent::semanticKey).toList());
        assertThat(chronological.auditEvents().getFirst().before())
                .isEqualTo(ObservedValue.value("Baseline"));
        assertThat(chronological.auditEvents().get(1).before())
                .isEqualTo(ObservedValue.value("Earlier"));
        assertThat(chronological.properties().get(MonitoredLineItemProperty.NAME))
                .isEqualTo(ObservedValue.value("Later"));
    }

    @Test
    void laterCompleteObservationDoesNotRewriteEarlierUnknownAudit() {
        Instant signalTime = Instant.parse("2026-09-27T10:00:00Z");
        LineItemChangeSignal early = property(
                "early", signalTime, MonitoredLineItemProperty.NAME, "Early");
        LineItemProjectionCheckpoint laterCheckpoint = checkpoint(
                signalTime.plusSeconds(60), "Checkpoint");

        LineItemProjection result = reconstructor.reconstruct(
                LINE_ITEM, List.of(laterCheckpoint), List.of(early));

        assertThat(result.auditEvents().getFirst().before()).isEqualTo(ObservedValue.unknown());
        assertThat(result.properties().get(MonitoredLineItemProperty.NAME))
                .isEqualTo(ObservedValue.value("Checkpoint"));
    }

    @Test
    void baselineIgnoredAfterEarlierDeletionDoesNotClaimBaselineAnchoredCoverage() {
        Instant baselineTime = Instant.parse("2026-09-27T10:00:00Z");
        Instant deletedAt = baselineTime.minusSeconds(60);

        LineItemProjection result = reconstructor.reconstruct(
                LINE_ITEM,
                List.of(checkpoint(baselineTime, "Stale baseline")),
                List.of(deleted("deleted-before-baseline", deletedAt)));

        assertThat(result.deletedAt()).isEqualTo(deletedAt);
        assertThat(result.historyCoverage().mode())
                .isEqualTo(LineItemHistoryCoverage.Mode.SIGNAL_FIRST);
        assertThat(result.historyCoverage().observedFrom()).isEqualTo(deletedAt);
        assertThat(result.properties().values()).containsOnly(ObservedValue.unknown());
    }

    private static LineItemProjectionCheckpoint checkpoint(Instant observedAt, String name) {
        LineItemObservation observation = new LineItemObservation(
                LINE_ITEM,
                name,
                BigDecimal.ONE,
                null,
                null,
                null,
                null,
                BillingStart.unspecified(),
                null,
                observedAt.minusSeconds(3600),
                observedAt.minusSeconds(1),
                observedAt,
                Set.of(DEAL));
        return new LineItemProjectionCheckpoint(
                SnapshotKind.BASELINE,
                observation.providerCreatedAt(),
                observation.providerUpdatedAt(),
                observation.observedAt(),
                LineItemPropertyValues.fromObservation(observation),
                observation.associatedDealIds());
    }

    private static LineItemChangeSignal created(String id, Instant occurredAt) {
        return signal(id, occurredAt, LineItemChangeSignalType.CREATED, null, null, null, null, null);
    }

    private static LineItemChangeSignal deleted(String id, Instant occurredAt) {
        return signal(id, occurredAt, LineItemChangeSignalType.DELETED, null, null, null, null, null);
    }

    private static LineItemChangeSignal property(
            String id,
            Instant occurredAt,
            MonitoredLineItemProperty property,
            String value) {
        return signal(id, occurredAt, LineItemChangeSignalType.PROPERTY_CHANGED,
                property, value, null, null, null);
    }

    private static LineItemChangeSignal association(
            String id,
            Instant occurredAt,
            AssociationAction action,
            String typeId) {
        return signal(id, occurredAt, LineItemChangeSignalType.ASSOCIATION_CHANGED,
                null, null, DEAL, action, typeId);
    }

    private static LineItemChangeSignal signal(
            String id,
            Instant occurredAt,
            LineItemChangeSignalType type,
            MonitoredLineItemProperty property,
            String value,
            ProviderObjectId deal,
            AssociationAction action,
            String associationTypeId) {
        return new LineItemChangeSignal(
                UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8)),
                TENANT,
                CONNECTION,
                "event-" + id,
                "subscription-1",
                new ProviderDeduplicationKey(digest(id)),
                LINE_ITEM,
                type,
                occurredAt,
                occurredAt.plusSeconds(1),
                property,
                value,
                deal,
                action,
                associationTypeId,
                associationTypeId == null ? null : "HUBSPOT_DEFINED");
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
