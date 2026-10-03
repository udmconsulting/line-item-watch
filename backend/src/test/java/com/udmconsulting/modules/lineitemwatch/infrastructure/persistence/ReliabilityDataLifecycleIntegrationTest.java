package com.udmconsulting.modules.lineitemwatch.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.udmconsulting.modules.lineitemwatch.application.DealLineItemObservations;
import com.udmconsulting.modules.lineitemwatch.application.LineItemChangeSignalStore;
import com.udmconsulting.modules.lineitemwatch.application.LineItemReconciliationSource.ReconciliationObservation;
import com.udmconsulting.modules.lineitemwatch.application.LineItemReliability;
import com.udmconsulting.modules.lineitemwatch.application.LineItemReliabilityStore;
import com.udmconsulting.modules.lineitemwatch.application.LineItemSignalProcessingStore;
import com.udmconsulting.modules.lineitemwatch.application.LineItemSnapshotStore;
import com.udmconsulting.modules.lineitemwatch.application.ReliabilityMaintenance;
import com.udmconsulting.modules.lineitemwatch.application.ReliabilityMaintenanceStore;
import com.udmconsulting.modules.lineitemwatch.application.ReliabilityMaintenanceStore.RetentionCutoffs;
import com.udmconsulting.modules.lineitemwatch.application.ReliabilityOperationException;
import com.udmconsulting.modules.lineitemwatch.application.ReliabilityOperationStore;
import com.udmconsulting.modules.lineitemwatch.application.ReliabilityOperationStore.OperationType;
import com.udmconsulting.modules.lineitemwatch.application.ReliabilityOperationStore.Request;
import com.udmconsulting.modules.lineitemwatch.application.ReliabilityOperationStore.ScopeType;
import com.udmconsulting.modules.lineitemwatch.domain.AssociationAction;
import com.udmconsulting.modules.lineitemwatch.domain.BillingStart;
import com.udmconsulting.modules.lineitemwatch.domain.LineItemChangeSignal;
import com.udmconsulting.modules.lineitemwatch.domain.LineItemChangeSignalType;
import com.udmconsulting.modules.lineitemwatch.domain.LineItemObservation;
import com.udmconsulting.modules.lineitemwatch.domain.MonitoredLineItemProperty;
import com.udmconsulting.modules.lineitemwatch.domain.ProviderDeduplicationKey;
import com.udmconsulting.modules.lineitemwatch.domain.ProviderObjectId;
import com.udmconsulting.platform.activity.application.ActivityContext;
import com.udmconsulting.platform.activity.domain.ActivityActor;
import com.udmconsulting.platform.activity.domain.ActivityActorSource;
import com.udmconsulting.platform.activity.domain.ActivityActorType;
import com.udmconsulting.platform.connection.application.PlatformConnectionService;
import com.udmconsulting.platform.connection.domain.ConnectionStatus;
import com.udmconsulting.platform.connection.domain.ExternalAccountId;
import com.udmconsulting.platform.connection.domain.PlatformConnection;
import com.udmconsulting.platform.connection.domain.Provider;
import com.udmconsulting.platform.entitlement.application.EntitlementService;
import com.udmconsulting.platform.module.domain.ProductModule;
import com.udmconsulting.platform.supportability.OperationalErrorCode;
import com.udmconsulting.platform.tenant.application.TenantService;
import com.udmconsulting.platform.tenant.domain.Tenant;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = {
        "line-item-watch.processing.enabled=false",
        "line-item-watch.reliability.enabled=false"
})
@Testcontainers
class ReliabilityDataLifecycleIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("hubspot.oauth.client-id", () -> "test-client-id");
        registry.add("hubspot.oauth.client-secret", () -> "test-client-secret");
        registry.add("hubspot.oauth.redirect-uri", () -> "http://localhost:8080/callback");
        registry.add("hubspot.oauth.api-base-url", () -> "http://localhost:9999");
        registry.add("hubspot.oauth.authorization-base-url",
                () -> "https://app.hubspot.com/oauth/authorize");
        registry.add("hubspot.credentials.key-id", () -> "test-key-1");
        registry.add("hubspot.credentials.encryption-key", () ->
                Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Autowired TenantService tenants;
    @Autowired PlatformConnectionService connections;
    @Autowired EntitlementService entitlements;
    @Autowired LineItemSnapshotStore snapshots;
    @Autowired LineItemChangeSignalStore signals;
    @Autowired LineItemSignalProcessingStore processing;
    @Autowired LineItemReliabilityStore reliability;
    @Autowired ReliabilityOperationStore operations;
    @Autowired ReliabilityMaintenanceStore maintenanceStore;
    @Autowired ReliabilityMaintenance maintenance;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clearData() {
        jdbc.update("DELETE FROM tenant");
    }

    @Test
    void lifecycleClosesAndReopensObservationWithoutErasingTheGap() {
        Fixture fixture = fixture("reliability-lifecycle");
        Instant lostAt = Instant.now().plusSeconds(60);

        reliability.connectionChanged(fixture.tenant().id(), fixture.connection().id(),
                ConnectionStatus.ACTIVE, ConnectionStatus.REAUTH_REQUIRED, lostAt);
        LineItemReliability paused = reliability.read(
                fixture.tenant().id(), fixture.connection().id());
        assertThat(paused.ingestionState()).isEqualTo(LineItemReliability.IngestionState.PAUSED);
        assertThat(paused.coverageState()).isEqualTo(
                LineItemReliability.CoverageState.POSSIBLE_GAP);
        assertThat(paused.possibleGapSince()).isEqualTo(lostAt);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM line_item_watch_observation_period
                WHERE tenant_id = ? AND connection_id = ? AND ended_at = ?
                """, Long.class, fixture.tenant().id().value(),
                fixture.connection().id().value(), Timestamp.from(lostAt))).isEqualTo(1L);

        reliability.connectionChanged(fixture.tenant().id(), fixture.connection().id(),
                ConnectionStatus.REAUTH_REQUIRED, ConnectionStatus.ACTIVE,
                lostAt.plusSeconds(60));
        LineItemReliability resumed = reliability.read(
                fixture.tenant().id(), fixture.connection().id());
        assertThat(resumed.ingestionState()).isEqualTo(
                LineItemReliability.IngestionState.OBSERVING);
        assertThat(resumed.coverageState()).isEqualTo(
                LineItemReliability.CoverageState.POSSIBLE_GAP);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM line_item_watch_observation_period
                WHERE tenant_id = ? AND connection_id = ? AND ended_at IS NULL
                """, Long.class, fixture.tenant().id().value(),
                fixture.connection().id().value())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                SELECT start_reason FROM line_item_watch_observation_period
                WHERE tenant_id = ? AND connection_id = ? AND ended_at IS NULL
                """, String.class, fixture.tenant().id().value(),
                fixture.connection().id().value())).isEqualTo("REAUTHORIZED");
    }

    @Test
    void scheduledReconciliationIsTrackedScopeBoundedAndDeduplicated() {
        Fixture fixture = trackedFixture("scheduled-reconciliation");
        Instant now = Instant.parse("2026-09-30T12:00:00Z");

        assertThat(operations.scheduleDueReconciliations(now.minusSeconds(1), 10, now))
                .isEqualTo(1);
        assertThat(operations.scheduleDueReconciliations(now.minusSeconds(1), 10, now))
                .isZero();
        var claimed = operations.claimNext(now, Duration.ofMinutes(2)).orElseThrow();
        assertThat(claimed.operationType()).isEqualTo(OperationType.RECONCILE);
        assertThat(claimed.scopeType()).isEqualTo(ScopeType.TENANT);
        assertThat(operations.expandReconciliationTenant(claimed, 100, now)).isEqualTo(1);
        assertThat(jdbc.queryForList("""
                SELECT external_deal_id FROM line_item_watch_reliability_operation
                WHERE scope_type = 'DEAL'
                """, String.class)).containsExactly("1001");
    }

    @Test
    void expiredOperationLeaseIsRecoveredWithTheSameOpaqueOperation() {
        Fixture fixture = trackedFixture("lease-recovery");
        UUID lineItemId = lineItemId();
        Instant now = Instant.parse("2026-09-30T12:00:00Z");
        UUID operationId = operations.request(new Request(
                fixture.tenant().id(), fixture.connection().id(), OperationType.REPLAY,
                ScopeType.LINE_ITEM, lineItemId, null), now);

        var first = operations.claimNext(now, Duration.ofMinutes(2)).orElseThrow();
        assertThat(operations.claimNext(now.plusSeconds(30), Duration.ofMinutes(2))).isEmpty();
        var recovered = operations.claimNext(now.plusSeconds(121), Duration.ofMinutes(2))
                .orElseThrow();
        assertThat(recovered.id()).isEqualTo(operationId).isEqualTo(first.id());
        assertThat(recovered.attempt()).isEqualTo(2);
        assertThat(recovered.claimToken()).isNotEqualTo(first.claimToken());
    }

    @Test
    void terminalSignalRequeueIsCompareAndSetIdempotentAndPreservesOrdering() {
        Fixture fixture = fixture("terminal-requeue");
        Instant occurredAt = Instant.now().minusSeconds(2);
        LineItemChangeSignal failed = signal(fixture, "failed", occurredAt, "broken");
        LineItemChangeSignal later = signal(
                fixture, "later", occurredAt.plusSeconds(1), "later");
        signals.capture(fixture.tenant().id(), fixture.connection().id(), List.of(failed, later));
        jdbc.update("""
                UPDATE line_item_watch_signal_processing
                SET status = 'FAILED', attempt_count = 8, next_attempt_at = ?, failed_at = ?,
                    last_error_code = 'RETRY_EXHAUSTED',
                    failure_disposition = 'RETRY_EXHAUSTED', updated_at = ?
                WHERE signal_id = ?
                """, Timestamp.from(occurredAt), Timestamp.from(occurredAt.plusSeconds(10)),
                Timestamp.from(occurredAt.plusSeconds(10)), failed.id());

        maintenance.requeue(fixture.tenant().id(), fixture.connection().id(),
                failed.id(), operator());
        maintenance.requeue(fixture.tenant().id(), fixture.connection().id(),
                failed.id(), operator());

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM application_activity_audit
                WHERE action = 'LINE_ITEM_SIGNAL_REQUEUED'
                """, Long.class)).isEqualTo(1L);
        assertThat(processing.claimNext(Instant.now().plusSeconds(20),
                Duration.ofMinutes(2), 8).orElseThrow().signalId()).isEqualTo(failed.id());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM line_item_watch_change_signal WHERE id IN (?, ?)
                """, Long.class, failed.id(), later.id())).isEqualTo(2L);
    }

    @Test
    void reconciliationRepairsDriftWithoutFabricatingSemanticHistory() {
        Fixture fixture = trackedFixture("reconciliation-drift");
        Instant observedAt = Instant.parse("2026-09-30T13:00:00Z");
        long auditBefore = count("line_item_watch_audit_event");
        var claimed = claimReconciliation(fixture, observedAt);
        long generation = credentialGeneration(fixture);
        LineItemObservation current = observation(
                "Provider current", observedAt, observedAt.plusSeconds(1), Set.of("1001"));

        var result = operations.reconcileDeal(claimed,
                new ReconciliationObservation(
                        new DealLineItemObservations(new ProviderObjectId("1001"),
                                List.of(current)), generation),
                observedAt.plusSeconds(2));

        assertThat(result.propertyDrift()).isEqualTo(1);
        assertThat(result.associationDrift()).isZero();
        assertThat(snapshotName("LATEST")).isEqualTo("Provider current");
        assertThat(count("line_item_watch_audit_event")).isEqualTo(auditBefore);
        assertThat(jdbc.queryForList("""
                SELECT finding_type FROM line_item_watch_reconciliation_finding
                """, String.class)).containsExactly("PROPERTY_DRIFT");
    }

    @Test
    void noDriftReconciliationIsIdempotentAndDoesNotCreateFindings() {
        Fixture fixture = trackedFixture("reconciliation-no-drift");
        Instant providerUpdated = Instant.parse("2026-09-30T11:00:00Z");
        Instant now = Instant.parse("2026-09-30T12:00:00Z");
        reliability.markPossibleGap(
                fixture.tenant().id(), fixture.connection().id(), now.minusSeconds(1));
        var claimed = claimReconciliation(fixture, now);

        var result = operations.reconcileDeal(claimed,
                providerRead("Initial", providerUpdated, credentialGeneration(fixture)),
                now.plusSeconds(1));

        assertThat(result.driftRepaired()).isFalse();
        assertThat(result.stateConflicts()).isZero();
        assertThat(count("line_item_watch_reconciliation_finding")).isZero();
        assertThat(count("line_item_watch_audit_event")).isZero();
        assertThat(operations.succeed(claimed, now.plusSeconds(2))).isTrue();
        maintenance.acknowledgeGap(
                fixture.tenant().id(), fixture.connection().id(), operator());
        assertThat(jdbc.queryForObject("""
                SELECT possible_gap_since IS NOT NULL AND gap_acknowledged_at IS NOT NULL
                FROM line_item_watch_reliability_state
                WHERE tenant_id = ? AND connection_id = ?
                """, Boolean.class, fixture.tenant().id().value(),
                fixture.connection().id().value())).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM application_activity_audit
                WHERE action = 'LINE_ITEM_GAP_ACKNOWLEDGED'
                """, Long.class)).isEqualTo(1L);
    }

    @Test
    void reconciliationRecordsAssociationUnknownAbsentAndInaccessibleFindings() {
        Fixture fixture = trackedFixture("reconciliation-finding-types");
        Instant now = Instant.parse("2026-09-30T12:30:00Z");
        var drift = claimReconciliation(fixture, now);
        LineItemObservation associatedDifferently = observation(
                "2001", "Initial", now, now.plusSeconds(1), Set.of("1001", "1002"));
        LineItemObservation newlyDiscovered = observation(
                "2002", "Discovered", now, now.plusSeconds(1), Set.of("1001"));

        var result = operations.reconcileDeal(drift, new ReconciliationObservation(
                new DealLineItemObservations(new ProviderObjectId("1001"),
                        List.of(associatedDifferently, newlyDiscovered)),
                credentialGeneration(fixture)), now.plusSeconds(2));
        assertThat(result.associationDrift()).isEqualTo(1);
        assertThat(result.locallyUnknown()).isEqualTo(1);
        assertThat(operations.succeed(drift, now.plusSeconds(3))).isTrue();

        var absent = claimReconciliation(fixture, now.plusSeconds(4));
        operations.recordReconciliationFailure(
                absent, "PROVIDER_OBJECT_ABSENT", now.plusSeconds(5));
        assertThat(operations.succeed(absent, now.plusSeconds(6))).isTrue();

        var inaccessible = claimReconciliation(fixture, now.plusSeconds(7));
        operations.recordReconciliationFailure(
                inaccessible, "PROVIDER_OBJECT_INACCESSIBLE", now.plusSeconds(8));
        assertThat(operations.succeed(inaccessible, now.plusSeconds(9))).isTrue();

        assertThat(jdbc.queryForList("""
                SELECT finding_type FROM line_item_watch_reconciliation_finding
                """, String.class)).contains(
                        "ASSOCIATION_DRIFT",
                        "LOCALLY_UNKNOWN_PROVIDER_OBJECT",
                        "PROVIDER_OBJECT_ABSENT",
                        "PROVIDER_OBJECT_INACCESSIBLE");
    }

    @Test
    void webhookProcessedAfterProviderReadWinsReconciliationCommitFence() {
        Fixture fixture = trackedFixture("reconciliation-webhook-race");
        Instant providerReadAt = Instant.now();
        var claimed = claimReconciliation(fixture, providerReadAt);
        ReconciliationObservation staleProviderRead = providerRead(
                "Provider value", providerReadAt.minusSeconds(1),
                credentialGeneration(fixture));

        LineItemChangeSignal webhook = signal(
                fixture, "webhook-race", providerReadAt.plusSeconds(10), "Webhook value");
        signals.capture(fixture.tenant().id(), fixture.connection().id(), List.of(webhook));
        var processingClaim = processing.claimNext(
                providerReadAt.plusSeconds(20), Duration.ofMinutes(2), 8).orElseThrow();
        processing.process(processingClaim, providerReadAt.plusSeconds(21));

        var result = operations.reconcileDeal(
                claimed, staleProviderRead, providerReadAt.plusSeconds(22));

        assertThat(result.stateConflicts()).isEqualTo(1);
        assertThat(snapshotName("LATEST")).isEqualTo("Webhook value");
        assertThat(jdbc.queryForList("""
                SELECT finding_type FROM line_item_watch_reconciliation_finding
                """, String.class)).containsExactly("PROVIDER_STATE_CONFLICT");
    }

    @Test
    void staleCredentialAndProviderTimestampCommitsAreRejectedWithoutOverwrite() {
        Fixture fixture = trackedFixture("reconciliation-races");
        Instant now = Instant.parse("2026-09-30T14:00:00Z");
        long generation = credentialGeneration(fixture);
        var staleCredential = claimReconciliation(fixture, now);
        jdbc.update("""
                UPDATE platform_connection SET credential_generation = credential_generation + 1
                WHERE tenant_id = ? AND id = ?
                """, fixture.tenant().id().value(), fixture.connection().id().value());

        assertThatThrownBy(() -> operations.reconcileDeal(staleCredential,
                providerRead("Stale credential", now.plusSeconds(10), generation),
                now.plusSeconds(11)))
                .isInstanceOf(ReliabilityOperationException.class)
                .extracting(error -> ((ReliabilityOperationException) error).errorCode())
                .isEqualTo(OperationalErrorCode.RECONCILIATION_CONFLICT);
        assertThat(snapshotName("LATEST")).isEqualTo("Initial");

        operations.retry(staleCredential, OperationalErrorCode.RECONCILIATION_CONFLICT,
                now.plusSeconds(12), Duration.ZERO, staleCredential.attempt());
        var timestampConflict = claimReconciliation(fixture, now.plusSeconds(13));
        var result = operations.reconcileDeal(timestampConflict,
                providerRead("Conflicting provider",
                        Instant.parse("2026-09-30T10:59:00Z"), generation + 1),
                now.plusSeconds(14));
        assertThat(result.stateConflicts()).isEqualTo(1);
        assertThat(snapshotName("LATEST")).isEqualTo("Initial");
        assertThat(jdbc.queryForList("""
                SELECT finding_type FROM line_item_watch_reconciliation_finding
                ORDER BY detected_at
                """, String.class)).contains("PROVIDER_STATE_CONFLICT");
    }

    @Test
    void disconnectAndEntitlementRemovalWinningTheCommitRaceAreRejected() {
        Fixture fixture = trackedFixture("reconciliation-lifecycle-races");
        Instant now = Instant.parse("2026-09-30T14:30:00Z");
        long generation = credentialGeneration(fixture);
        var disconnectRace = claimReconciliation(fixture, now);
        jdbc.update("""
                UPDATE platform_connection SET status = 'DISCONNECTED', status_changed_at = ?
                WHERE tenant_id = ? AND id = ?
                """, Timestamp.from(now.plusSeconds(1)), fixture.tenant().id().value(),
                fixture.connection().id().value());

        assertThatThrownBy(() -> operations.reconcileDeal(disconnectRace,
                providerRead("Must not commit", now.plusSeconds(2), generation),
                now.plusSeconds(3)))
                .isInstanceOf(ReliabilityOperationException.class)
                .extracting(error -> ((ReliabilityOperationException) error).errorCode())
                .isEqualTo(OperationalErrorCode.RECONCILIATION_CONFLICT);
        assertThat(snapshotName("LATEST")).isEqualTo("Initial");

        operations.retry(disconnectRace, OperationalErrorCode.RECONCILIATION_CONFLICT,
                now.plusSeconds(4), Duration.ZERO, disconnectRace.attempt());
        jdbc.update("""
                UPDATE platform_connection SET status = 'ACTIVE', status_changed_at = ?
                WHERE tenant_id = ? AND id = ?
                """, Timestamp.from(now.plusSeconds(5)), fixture.tenant().id().value(),
                fixture.connection().id().value());
        var entitlementRace = claimReconciliation(fixture, now.plusSeconds(6));
        jdbc.update("""
                DELETE FROM tenant_entitlement
                WHERE tenant_id = ? AND product_module = 'LINE_ITEM_WATCH'
                """, fixture.tenant().id().value());

        assertThatThrownBy(() -> operations.reconcileDeal(entitlementRace,
                providerRead("Must not commit", now.plusSeconds(7), generation),
                now.plusSeconds(8)))
                .isInstanceOf(ReliabilityOperationException.class)
                .extracting(error -> ((ReliabilityOperationException) error).errorCode())
                .isEqualTo(OperationalErrorCode.RECONCILIATION_CONFLICT);
        assertThat(snapshotName("LATEST")).isEqualTo("Initial");
    }

    @Test
    void replayAnchorRestoresProjectionAndRetentionDeletesOnlyAnchoredProcessedEvidence() {
        Fixture fixture = trackedFixture("anchor-retention");
        Instant signalAt = Instant.now();
        LineItemChangeSignal change = signal(fixture, "anchor-change", signalAt, "Anchored");
        signals.capture(fixture.tenant().id(), fixture.connection().id(), List.of(change));
        var processingClaim = processing.claimNext(
                signalAt.plusSeconds(10), Duration.ofMinutes(2), 8).orElseThrow();
        processing.process(processingClaim, signalAt.plusSeconds(11));
        UUID lineItemId = lineItemId();

        var anchor = maintenance.anchor(
                fixture.tenant().id(), fixture.connection().id(), lineItemId);
        assertThat(anchor.trusted()).isTrue();
        assertThat(anchor.verified()).isTrue();
        jdbc.update("""
                UPDATE line_item_watch_snapshot SET name = 'Corrupt derived value'
                WHERE line_item_id = ? AND snapshot_kind = 'LATEST'
                """, lineItemId);
        replay(fixture, lineItemId, signalAt.plusSeconds(20));
        assertThat(snapshotName("LATEST")).isEqualTo("Anchored");

        RetentionCutoffs cutoffs = new RetentionCutoffs(
                signalAt.plusSeconds(30), null, null, null);
        assertThat(maintenance.preview(
                fixture.tenant().id(), fixture.connection().id(), cutoffs)
                .processedSignals()).isEqualTo(1);
        var retained = maintenance.retain(
                fixture.tenant().id(), fixture.connection().id(), cutoffs,
                true, 1, operator());
        assertThat(retained.processedSignals()).isEqualTo(1);
        assertThat(count("line_item_watch_change_signal")).isZero();
        assertThat(count("line_item_watch_audit_event")).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT retention_limited FROM line_item_watch_line_item_reliability
                WHERE line_item_id = ?
                """, Boolean.class, lineItemId)).isTrue();

        jdbc.update("""
                UPDATE line_item_watch_snapshot SET name = 'Corrupt again'
                WHERE line_item_id = ? AND snapshot_kind = 'LATEST'
                """, lineItemId);
        replay(fixture, lineItemId, signalAt.plusSeconds(40));
        assertThat(snapshotName("LATEST")).isEqualTo("Anchored");
    }

    @Test
    void insufficientReplayEvidenceKeepsTheTrustedProjectionIntact() {
        Fixture fixture = trackedFixture("insufficient-replay");
        UUID lineItemId = lineItemId();
        jdbc.update("""
                DELETE FROM line_item_watch_snapshot
                WHERE line_item_id = ? AND snapshot_kind IN ('BASELINE', 'OBSERVED')
                """, lineItemId);
        var claimed = requestAndClaim(fixture, new Request(
                fixture.tenant().id(), fixture.connection().id(), OperationType.REPLAY,
                ScopeType.LINE_ITEM, lineItemId, null), Instant.parse("2026-09-30T16:00:00Z"));

        assertThatThrownBy(() -> operations.replayLineItem(
                claimed, Instant.parse("2026-09-30T16:00:01Z")))
                .isInstanceOf(ReliabilityOperationException.class)
                .extracting(error -> ((ReliabilityOperationException) error).errorCode())
                .isEqualTo(OperationalErrorCode.INSUFFICIENT_RETAINED_EVIDENCE);
        assertThat(snapshotName("LATEST")).isEqualTo("Initial");
    }

    @Test
    void maintenanceRejectsCrossTenantOwnershipAndEmptyRetentionPolicy() {
        Fixture owner = trackedFixture("owner");
        Fixture other = fixture("other");

        assertThatThrownBy(() -> maintenance.inspect(
                other.tenant().id(), owner.connection().id()))
                .isInstanceOf(ReliabilityOperationException.class);
        assertThatThrownBy(() -> maintenance.preview(
                owner.tenant().id(), owner.connection().id(),
                new RetentionCutoffs(null, null, null, null)))
                .isInstanceOf(ReliabilityOperationException.class)
                .extracting(error -> ((ReliabilityOperationException) error).errorCode())
                .isEqualTo(OperationalErrorCode.RETENTION_FAILED);
    }

    private Fixture trackedFixture(String account) {
        Fixture fixture = fixture(account);
        Instant providerUpdated = Instant.parse("2026-09-30T11:00:00Z");
        snapshots.establish(fixture.tenant().id(), fixture.connection().id(),
                List.of(observation("Initial", providerUpdated,
                        providerUpdated.plusSeconds(1), Set.of("1001"))));
        return fixture;
    }

    private Fixture fixture(String account) {
        Tenant tenant = tenants.create();
        PlatformConnection registered = connections.register(
                tenant.id(), Provider.HUBSPOT, new ExternalAccountId(account));
        jdbc.update("""
                UPDATE platform_connection
                SET status = 'ACTIVE', status_changed_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, registered.id().value());
        entitlements.enable(tenant.id(), ProductModule.LINE_ITEM_WATCH);
        PlatformConnection active = connections.findForTenant(tenant.id(), registered.id())
                .orElseThrow();
        return new Fixture(tenant, active);
    }

    private ReliabilityOperationStore.ClaimedOperation claimReconciliation(
            Fixture fixture, Instant now) {
        return requestAndClaim(fixture, new Request(
                fixture.tenant().id(), fixture.connection().id(), OperationType.RECONCILE,
                ScopeType.DEAL, null, new ProviderObjectId("1001")), now);
    }

    private ReliabilityOperationStore.ClaimedOperation requestAndClaim(
            Fixture fixture, Request request, Instant now) {
        UUID id = operations.request(request, now);
        var claimed = operations.claimNext(now, Duration.ofMinutes(2)).orElseThrow();
        assertThat(claimed.id()).isEqualTo(id);
        return claimed;
    }

    private void replay(Fixture fixture, UUID lineItemId, Instant now) {
        var claimed = requestAndClaim(fixture, new Request(
                fixture.tenant().id(), fixture.connection().id(), OperationType.REPLAY,
                ScopeType.LINE_ITEM, lineItemId, null), now);
        operations.replayLineItem(claimed, now.plusSeconds(1));
        assertThat(operations.succeed(claimed, now.plusSeconds(2))).isTrue();
    }

    private ReconciliationObservation providerRead(
            String name, Instant providerUpdatedAt, long generation) {
        return new ReconciliationObservation(
                new DealLineItemObservations(new ProviderObjectId("1001"),
                        List.of(observation(name, providerUpdatedAt,
                                providerUpdatedAt.plusSeconds(1), Set.of("1001")))),
                generation);
    }

    private LineItemObservation observation(
            String name, Instant providerUpdatedAt, Instant observedAt, Set<String> dealIds) {
        return observation("2001", name, providerUpdatedAt, observedAt, dealIds);
    }

    private LineItemObservation observation(
            String lineItemId,
            String name,
            Instant providerUpdatedAt,
            Instant observedAt,
            Set<String> dealIds) {
        return new LineItemObservation(
                new ProviderObjectId(lineItemId), name, BigDecimal.ONE,
                new BigDecimal("100.00"), null, null, "monthly",
                BillingStart.unspecified(), null,
                providerUpdatedAt.minusSeconds(60), providerUpdatedAt, observedAt,
                dealIds.stream().map(ProviderObjectId::new)
                        .collect(java.util.stream.Collectors.toSet()));
    }

    private LineItemChangeSignal signal(
            Fixture fixture, String key, Instant occurredAt, String value) {
        return new LineItemChangeSignal(
                UUID.nameUUIDFromBytes((fixture.connection().id() + key)
                        .getBytes(StandardCharsets.UTF_8)),
                fixture.tenant().id(), fixture.connection().id(), "event-" + key,
                "subscription", new ProviderDeduplicationKey(digest(key)),
                new ProviderObjectId("2001"), LineItemChangeSignalType.PROPERTY_CHANGED,
                occurredAt, occurredAt.plusSeconds(1), MonitoredLineItemProperty.NAME,
                value, null, (AssociationAction) null, null, null);
    }

    private UUID lineItemId() {
        return jdbc.queryForObject("""
                SELECT id FROM line_item_watch_line_item
                WHERE external_line_item_id = '2001'
                """, UUID.class);
    }

    private long credentialGeneration(Fixture fixture) {
        return jdbc.queryForObject("""
                SELECT credential_generation FROM platform_connection
                WHERE tenant_id = ? AND id = ?
                """, Long.class, fixture.tenant().id().value(),
                fixture.connection().id().value());
    }

    private String snapshotName(String kind) {
        return jdbc.queryForObject("""
                SELECT name FROM line_item_watch_snapshot WHERE snapshot_kind = ?
                """, String.class, kind);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static ActivityContext operator() {
        return new ActivityContext(
                new ActivityActor(ActivityActorType.OPERATOR,
                        ActivityActorSource.APPLICATION, "p8-test-operator"),
                UUID.randomUUID());
    }

    private record Fixture(Tenant tenant, PlatformConnection connection) {
    }
}
