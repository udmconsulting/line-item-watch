package com.udmconsulting.modules.lineitemwatch.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.udmconsulting.modules.lineitemwatch.application.ClaimedLineItemSignal;
import com.udmconsulting.modules.lineitemwatch.application.LineItemChangeSignalStore;
import com.udmconsulting.modules.lineitemwatch.application.LineItemSignalProcessingStore;
import com.udmconsulting.modules.lineitemwatch.application.LineItemSnapshotStore;
import com.udmconsulting.modules.lineitemwatch.application.SignalProcessingException;
import com.udmconsulting.modules.lineitemwatch.domain.AssociationAction;
import com.udmconsulting.modules.lineitemwatch.domain.BillingStart;
import com.udmconsulting.modules.lineitemwatch.domain.LineItemChangeSignal;
import com.udmconsulting.modules.lineitemwatch.domain.LineItemChangeSignalType;
import com.udmconsulting.modules.lineitemwatch.domain.LineItemObservation;
import com.udmconsulting.modules.lineitemwatch.domain.MonitoredLineItemProperty;
import com.udmconsulting.modules.lineitemwatch.domain.ProviderDeduplicationKey;
import com.udmconsulting.modules.lineitemwatch.domain.ProviderObjectId;
import com.udmconsulting.platform.connection.application.PlatformConnectionService;
import com.udmconsulting.platform.connection.domain.ExternalAccountId;
import com.udmconsulting.platform.connection.domain.PlatformConnection;
import com.udmconsulting.platform.connection.domain.Provider;
import com.udmconsulting.platform.entitlement.application.EntitlementService;
import com.udmconsulting.platform.module.domain.ProductModule;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers
class LineItemSignalProcessingPersistenceIntegrationTest {

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
        registry.add("hubspot.oauth.authorization-base-url", () -> "https://app.hubspot.com/oauth/authorize");
        registry.add("hubspot.credentials.key-id", () -> "test-key-1");
        registry.add("hubspot.credentials.encryption-key", () ->
                Base64.getEncoder().encodeToString(new byte[32]));
        registry.add("line-item-watch.processing.enabled", () -> "false");
    }

    @Autowired TenantService tenantService;
    @Autowired PlatformConnectionService connectionService;
    @Autowired EntitlementService entitlementService;
    @Autowired LineItemChangeSignalStore signalStore;
    @Autowired LineItemSignalProcessingStore processingStore;
    @Autowired LineItemSnapshotStore snapshotStore;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void clearData() {
        jdbcTemplate.update("DELETE FROM tenant");
    }

    @Test
    void migrationCreatesProcessingAndAuditTables() {
        assertThat(jdbcTemplate.queryForList("""
                SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'
                """, String.class)).contains(
                        "line_item_watch_signal_processing",
                        "line_item_watch_audit_event",
                        "line_item_watch_audit_event_source",
                        "line_item_watch_audit_event_deal_context");
        assertThat(jdbcTemplate.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_name = 'line_item_watch_snapshot'
                """, String.class)).contains(
                        "known_properties", "deal_set_complete", "deleted_at");
        assertThat(jdbcTemplate.queryForList("""
                SELECT indexname FROM pg_indexes
                WHERE schemaname = 'public' AND tablename = 'line_item_watch_change_signal'
                """, String.class)).contains(
                        "idx_line_item_watch_change_signal_line_item_occurrence");
    }

    @Test
    void migrationEnforcesSparseSnapshotAndSemanticAuditInvariants() {
        Fixture fixture = fixture("audit-state-constraints");
        Instant observedAt = Instant.parse("2026-09-27T10:00:00Z");
        snapshotStore.establish(
                fixture.tenant.id(), fixture.connection.id(),
                List.of(observation("line-1", "Initial", observedAt, observedAt)));
        UUID lineItemId = jdbcTemplate.queryForObject("""
                SELECT id FROM line_item_watch_line_item
                WHERE tenant_id = ? AND connection_id = ? AND external_line_item_id = 'line-1'
                """, UUID.class, fixture.tenant.id().value(), fixture.connection.id().value());

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO line_item_watch_audit_event (
                    id, tenant_id, connection_id, line_item_id, semantic_key,
                    event_type, occurred_at
                ) VALUES (?, ?, ?, ?, ?, 'CREATED', ?)
                """, UUID.randomUUID(), fixture.tenant.id().value(), fixture.connection.id().value(),
                lineItemId, new byte[32], Timestamp.from(observedAt)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO line_item_watch_audit_event (
                    id, tenant_id, connection_id, line_item_id, semantic_key,
                    event_type, occurred_at, before_state, after_state, external_deal_id
                ) VALUES (?, ?, ?, ?, ?, 'DEAL_ASSOCIATED', ?, 'PRESENT', 'ABSENT', 'deal-1')
                """, UUID.randomUUID(), fixture.tenant.id().value(), fixture.connection.id().value(),
                lineItemId, new byte[32], Timestamp.from(observedAt)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE line_item_watch_snapshot
                SET known_properties = array_remove(known_properties, 'name')
                WHERE line_item_id = ? AND snapshot_kind = 'LATEST'
                """, lineItemId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void signalFirstProjectionFreezesDeletionAndSurvivesRawEvidenceDeletion() {
        Fixture fixture = fixture("signal-first");
        Instant time = Instant.parse("2026-09-27T12:00:00Z");
        List<LineItemChangeSignal> signals = List.of(
                signal(fixture, "created", "line-1", time, LineItemChangeSignalType.CREATED,
                        null, null, null, null, null),
                signal(fixture, "name", "line-1", time.plusSeconds(1),
                        LineItemChangeSignalType.PROPERTY_CHANGED,
                        MonitoredLineItemProperty.NAME, "Observed", null, null, null),
                signal(fixture, "add-19", "line-1", time.plusSeconds(2),
                        LineItemChangeSignalType.ASSOCIATION_CHANGED,
                        null, null, "deal-1", AssociationAction.ADDED, "19"),
                signal(fixture, "add-20", "line-1", time.plusSeconds(3),
                        LineItemChangeSignalType.ASSOCIATION_CHANGED,
                        null, null, "deal-1", AssociationAction.ADDED, "20"),
                signal(fixture, "deleted", "line-1", time.plusSeconds(4),
                        LineItemChangeSignalType.DELETED,
                        null, null, null, null, null),
                signal(fixture, "remove-20", "line-1", time.plusSeconds(5),
                        LineItemChangeSignalType.ASSOCIATION_CHANGED,
                        null, null, "deal-1", AssociationAction.REMOVED, "20"));
        signalStore.capture(fixture.tenant.id(), fixture.connection.id(), signals);
        processAll(Instant.now().plusSeconds(1));

        assertThat(auditTypes()).containsExactly(
                "CREATED", "PROPERTY_CHANGED", "DEAL_ASSOCIATED", "DELETED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT before_state FROM line_item_watch_audit_event
                WHERE event_type = 'PROPERTY_CHANGED'
                """, String.class)).isEqualTo("UNKNOWN");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM line_item_watch_audit_event_source src
                JOIN line_item_watch_audit_event event ON event.id = src.audit_event_id
                WHERE event.event_type = 'DEAL_ASSOCIATED'
                """, Long.class)).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT deal_set_complete FROM line_item_watch_snapshot
                WHERE snapshot_kind = 'LATEST'
                """, Boolean.class)).isFalse();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT deleted_at IS NOT NULL FROM line_item_watch_snapshot
                WHERE snapshot_kind = 'LATEST'
                """, Boolean.class)).isTrue();
        assertThat(jdbcTemplate.queryForList("""
                SELECT external_deal_id FROM line_item_watch_snapshot_deal
                WHERE snapshot_kind = 'LATEST'
                """, String.class)).containsExactly("deal-1");

        long auditCount = count("line_item_watch_audit_event");
        long sourceCount = count("line_item_watch_audit_event_source");
        jdbcTemplate.update("""
                DELETE FROM line_item_watch_change_signal
                WHERE signal_type = 'ASSOCIATION_CHANGED'
                  AND association_action = 'ADDED' AND association_type_id = '20'
                """);
        jdbcTemplate.update("""
                UPDATE line_item_watch_signal_processing processing
                SET status = 'PENDING', attempt_count = 0,
                    next_attempt_at = CURRENT_TIMESTAMP,
                    claim_token = NULL, claimed_at = NULL, lease_until = NULL,
                    processed_at = NULL, failed_at = NULL, last_error_code = NULL,
                    updated_at = CURRENT_TIMESTAMP
                FROM line_item_watch_change_signal signal
                WHERE processing.signal_id = signal.id
                  AND signal.signal_type = 'ASSOCIATION_CHANGED'
                  AND signal.association_action = 'ADDED'
                """);
        processAll(Instant.now().plusSeconds(1));
        assertThat(count("line_item_watch_audit_event_source")).isEqualTo(sourceCount);

        jdbcTemplate.update("DELETE FROM line_item_watch_change_signal");
        assertThat(count("line_item_watch_audit_event")).isEqualTo(auditCount);
        assertThat(count("line_item_watch_audit_event_source")).isEqualTo(sourceCount);
        assertThat(count("line_item_watch_signal_processing")).isZero();
    }

    @Test
    void completeObservationUsesSharedProjectionAndCannotOverwriteLaterSignal() {
        Fixture fixture = fixture("shared-projection");
        Instant start = Instant.parse("2026-09-27T10:00:00Z");
        snapshotStore.establish(
                fixture.tenant.id(), fixture.connection.id(),
                List.of(observation("line-1", "Initial", start, start)));
        LineItemChangeSignal later = signal(
                fixture, "later", "line-1", start.plusSeconds(120),
                LineItemChangeSignalType.PROPERTY_CHANGED,
                MonitoredLineItemProperty.NAME, "Signal value", null, null, null);
        signalStore.capture(fixture.tenant.id(), fixture.connection.id(), List.of(later));
        processAll(Instant.now().plusSeconds(1));

        snapshotStore.establish(
                fixture.tenant.id(), fixture.connection.id(),
                List.of(observation(
                        "line-1", "Checkpoint value", start.plusSeconds(30), start.plusSeconds(60))));

        assertThat(snapshotName("OBSERVED")).isEqualTo("Checkpoint value");
        assertThat(snapshotName("LATEST")).isEqualTo("Signal value");
        assertThat(snapshotName("BASELINE")).isEqualTo("Initial");
    }

    @Test
    void concurrentObservationAndSignalProcessingUseTheSameLineItemLock() throws Exception {
        Fixture fixture = fixture("shared-lock");
        Instant start = Instant.parse("2026-09-27T10:00:00Z");
        snapshotStore.establish(
                fixture.tenant.id(), fixture.connection.id(),
                List.of(observation("line-1", "Initial", start, start)));
        signalStore.capture(fixture.tenant.id(), fixture.connection.id(), List.of(signal(
                fixture, "signal", "line-1", start.plusSeconds(120),
                LineItemChangeSignalType.PROPERTY_CHANGED,
                MonitoredLineItemProperty.NAME, "Signal value", null, null, null)));
        Instant now = Instant.now().plusSeconds(1);
        ClaimedLineItemSignal claim = claim(now);
        CountDownLatch startTogether = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> observationWork = executor.submit(() -> {
                await(startTogether);
                snapshotStore.establish(
                        fixture.tenant.id(), fixture.connection.id(),
                        List.of(observation(
                                "line-1", "Checkpoint", start.plusSeconds(30),
                                start.plusSeconds(60))));
            });
            Future<?> processingWork = executor.submit(() -> {
                await(startTogether);
                processingStore.process(claim, now);
            });
            startTogether.countDown();
            observationWork.get();
            processingWork.get();
        }

        assertThat(snapshotName("OBSERVED")).isEqualTo("Checkpoint");
        assertThat(snapshotName("LATEST")).isEqualTo("Signal value");
        assertThat(count("line_item_watch_audit_event")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM line_item_watch_signal_processing WHERE signal_id = ?
                """, String.class, claim.signalId())).isEqualTo("PROCESSED");
    }

    @Test
    void lateOlderSignalRepairsHistoryWithoutMovingLatestBackward() {
        Fixture fixture = fixture("out-of-order");
        Instant start = Instant.parse("2026-09-27T10:00:00Z");
        snapshotStore.establish(
                fixture.tenant.id(), fixture.connection.id(),
                List.of(observation("line-1", "Initial", start, start)));
        LineItemChangeSignal newer = signal(
                fixture, "newer", "line-1", start.plusSeconds(20),
                LineItemChangeSignalType.PROPERTY_CHANGED,
                MonitoredLineItemProperty.NAME, "Newer", null, null, null);
        signalStore.capture(fixture.tenant.id(), fixture.connection.id(), List.of(newer));
        processAll(Instant.now().plusSeconds(1));
        LineItemChangeSignal older = signal(
                fixture, "older", "line-1", start.plusSeconds(10),
                LineItemChangeSignalType.PROPERTY_CHANGED,
                MonitoredLineItemProperty.NAME, "Older", null, null, null);
        signalStore.capture(fixture.tenant.id(), fixture.connection.id(), List.of(older));
        processAll(Instant.now().plusSeconds(1));

        assertThat(snapshotName("LATEST")).isEqualTo("Newer");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT before_value FROM line_item_watch_audit_event
                WHERE event_type = 'PROPERTY_CHANGED' AND after_value = 'Newer'
                """, String.class)).isEqualTo("Older");
    }

    @Test
    void deterministicFailureBlocksSameLineItemButNotUnrelatedWork() {
        Fixture fixture = fixture("terminal-block");
        Instant time = Instant.parse("2026-09-27T12:00:00Z");
        signalStore.capture(fixture.tenant.id(), fixture.connection.id(), List.of(
                signal(fixture, "invalid", "line-1", time,
                        LineItemChangeSignalType.PROPERTY_CHANGED,
                        MonitoredLineItemProperty.QUANTITY, "not-a-number", null, null, null),
                signal(fixture, "blocked", "line-1", time.plusSeconds(1),
                        LineItemChangeSignalType.PROPERTY_CHANGED,
                        MonitoredLineItemProperty.QUANTITY, "2", null, null, null),
                signal(fixture, "unrelated", "line-2", time.plusSeconds(2),
                        LineItemChangeSignalType.PROPERTY_CHANGED,
                        MonitoredLineItemProperty.QUANTITY, "3", null, null, null)));

        Instant processingTime = Instant.now().plusSeconds(1);
        ClaimedLineItemSignal invalid = claim(processingTime);
        assertThatThrownBy(() -> processingStore.process(invalid, time.plusSeconds(10)))
                .isInstanceOf(SignalProcessingException.class)
                .satisfies(error -> assertThat(((SignalProcessingException) error).retryable()).isFalse());
        assertThat(processingStore.recordFailure(
                invalid, "INVALID_SIGNAL_VALUE", false, time.plusSeconds(10),
                Duration.ofSeconds(5), 8).terminal()).isTrue();

        ClaimedLineItemSignal unrelated = claim(processingTime.plusSeconds(1));
        assertThat(signalLineItem(unrelated.signalId())).isEqualTo("line-2");
        processingStore.process(unrelated, time.plusSeconds(11));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM line_item_watch_signal_processing p
                JOIN line_item_watch_change_signal s ON s.id = p.signal_id
                WHERE s.external_line_item_id = 'line-1' AND s.property_value = '2'
                """, String.class)).isEqualTo("PENDING");
    }

    @Test
    void staleClaimCannotCompleteAfterRetryWasScheduled() {
        Fixture fixture = fixture("stale-claim");
        Instant time = Instant.parse("2026-09-27T12:00:00Z");
        signalStore.capture(fixture.tenant.id(), fixture.connection.id(), List.of(
                signal(fixture, "signal", "line-1", time,
                        LineItemChangeSignalType.PROPERTY_CHANGED,
                        MonitoredLineItemProperty.NAME, "value", null, null, null)));
        Instant processingTime = Instant.now().plusSeconds(1);
        ClaimedLineItemSignal claim = claim(processingTime);
        processingStore.recordFailure(
                claim, "TRANSIENT_DATABASE", true, time.plusSeconds(1),
                Duration.ofSeconds(5), 8);

        assertThatThrownBy(() -> processingStore.process(claim, time.plusSeconds(2)))
                .isInstanceOf(SignalProcessingException.class)
                .satisfies(error -> assertThat(((SignalProcessingException) error).errorCode())
                        .isEqualTo("STALE_CLAIM"));
    }

    @Test
    void retryableFailureUsesBoundedBackoffAndBecomesTerminalAtAttemptLimit() {
        Fixture fixture = fixture("bounded-retry");
        Instant occurred = Instant.parse("2026-09-27T12:00:00Z");
        signalStore.capture(fixture.tenant.id(), fixture.connection.id(), List.of(
                signal(fixture, "signal", "line-1", occurred,
                        LineItemChangeSignalType.PROPERTY_CHANGED,
                        MonitoredLineItemProperty.NAME, "value", null, null, null)));
        Instant firstAttemptAt = Instant.now().plusSeconds(1);
        ClaimedLineItemSignal first = processingStore.claimNext(
                firstAttemptAt, Duration.ofMinutes(2), 2).orElseThrow();
        assertThat(processingStore.recordFailure(
                first, "TRANSIENT_DATABASE", true, firstAttemptAt,
                Duration.ofSeconds(5), 2)).isEqualTo(
                        new LineItemSignalProcessingStore.FailureResult(true, false));
        assertThat(processingStore.claimNext(
                firstAttemptAt.plusSeconds(4), Duration.ofMinutes(2), 2)).isEmpty();

        ClaimedLineItemSignal second = processingStore.claimNext(
                firstAttemptAt.plusSeconds(5), Duration.ofMinutes(2), 2).orElseThrow();
        assertThat(second.attempt()).isEqualTo(2);
        assertThat(processingStore.recordFailure(
                second, "TRANSIENT_DATABASE", true, firstAttemptAt.plusSeconds(5),
                Duration.ofSeconds(10), 2)).isEqualTo(
                        new LineItemSignalProcessingStore.FailureResult(true, true));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM line_item_watch_signal_processing WHERE signal_id = ?
                """, String.class, second.signalId())).isEqualTo("FAILED");
    }

    @Test
    void concurrentClaimsSkipLockedItemsAndNeverClaimTwoSignalsForOneLineItem() throws Exception {
        Fixture fixture = fixture("concurrent-line-item");
        Instant occurred = Instant.parse("2026-09-27T12:00:00Z");
        signalStore.capture(fixture.tenant.id(), fixture.connection.id(), List.of(
                signal(fixture, "older", "line-1", occurred,
                        LineItemChangeSignalType.PROPERTY_CHANGED,
                        MonitoredLineItemProperty.NAME, "Older", null, null, null),
                signal(fixture, "newer", "line-1", occurred.plusSeconds(1),
                        LineItemChangeSignalType.PROPERTY_CHANGED,
                        MonitoredLineItemProperty.NAME, "Newer", null, null, null),
                signal(fixture, "unrelated", "line-2", occurred.plusSeconds(2),
                        LineItemChangeSignalType.PROPERTY_CHANGED,
                        MonitoredLineItemProperty.NAME, "Other", null, null, null)));
        Instant now = Instant.now().plusSeconds(1);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<ClaimedLineItemSignal> firstClaim = executor.submit(() -> {
                await(start);
                return claim(now);
            });
            Future<ClaimedLineItemSignal> secondClaim = executor.submit(() -> {
                await(start);
                return claim(now);
            });
            start.countDown();
            ClaimedLineItemSignal first = firstClaim.get();
            ClaimedLineItemSignal second = secondClaim.get();
            assertThat(List.of(signalLineItem(first.signalId()), signalLineItem(second.signalId())))
                    .containsExactlyInAnyOrder("line-1", "line-2");
            processingStore.process(first, now);
            processingStore.process(second, now);
        }

        ClaimedLineItemSignal remaining = claim(now.plusSeconds(1));
        assertThat(signalLineItem(remaining.signalId())).isEqualTo("line-1");
        processingStore.process(remaining, now.plusSeconds(1));

        assertThat(snapshotName("line-1", "LATEST")).isEqualTo("Newer");
        assertThat(snapshotName("line-2", "LATEST")).isEqualTo("Other");
        assertThat(count("line_item_watch_audit_event")).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM line_item_watch_signal_processing WHERE status = 'PROCESSED'
                """, Long.class)).isEqualTo(3L);
    }

    @Test
    void rollbackAfterProjectionWriteLeavesClaimRecoverableAndNoPartialBusinessState() {
        Fixture fixture = fixture("crash-boundary");
        Instant occurred = Instant.parse("2026-09-27T12:00:00Z");
        signalStore.capture(fixture.tenant.id(), fixture.connection.id(), List.of(
                signal(fixture, "signal", "line-1", occurred,
                        LineItemChangeSignalType.PROPERTY_CHANGED,
                        MonitoredLineItemProperty.NAME, "Observed", null, null, null)));
        Instant claimedAt = Instant.now().plusSeconds(1);
        ClaimedLineItemSignal claim = claim(claimedAt);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            processingStore.process(claim, claimedAt);
            throw new IllegalStateException("simulated crash before outer commit");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(count("line_item_watch_line_item")).isZero();
        assertThat(count("line_item_watch_snapshot")).isZero();
        assertThat(count("line_item_watch_audit_event")).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM line_item_watch_signal_processing WHERE signal_id = ?
                """, String.class, claim.signalId())).isEqualTo("CLAIMED");

        ClaimedLineItemSignal recovered = processingStore.claimNext(
                claimedAt.plus(Duration.ofMinutes(3)), Duration.ofMinutes(2), 8).orElseThrow();
        assertThat(recovered.reclaimed()).isTrue();
        processingStore.process(recovered, claimedAt.plus(Duration.ofMinutes(3)));
        assertThat(snapshotName("LATEST")).isEqualTo("Observed");
    }

    private void processAll(Instant now) {
        int processed = 0;
        while (true) {
            var claim = processingStore.claimNext(now, Duration.ofMinutes(2), 8);
            if (claim.isEmpty()) {
                break;
            }
            processingStore.process(claim.orElseThrow(), now);
            processed++;
            if (processed > 100) {
                throw new IllegalStateException("processing did not converge");
            }
        }
    }

    private ClaimedLineItemSignal claim(Instant now) {
        return processingStore.claimNext(now, Duration.ofMinutes(2), 8).orElseThrow();
    }

    private Fixture fixture(String account) {
        Tenant tenant = tenantService.create();
        PlatformConnection connection = connectionService.register(
                tenant.id(), Provider.HUBSPOT, new ExternalAccountId(account));
        jdbcTemplate.update("""
                UPDATE platform_connection
                SET status = 'ACTIVE', status_changed_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, connection.id().value());
        entitlementService.enable(tenant.id(), ProductModule.LINE_ITEM_WATCH);
        return new Fixture(tenant, connection);
    }

    private LineItemObservation observation(
            String lineItemId, String name, Instant providerUpdatedAt, Instant observedAt) {
        return new LineItemObservation(
                new ProviderObjectId(lineItemId),
                name,
                BigDecimal.ONE,
                null,
                null,
                null,
                null,
                BillingStart.unspecified(),
                null,
                providerUpdatedAt.minusSeconds(60),
                providerUpdatedAt,
                observedAt,
                Set.of(new ProviderObjectId("deal-1")));
    }

    private LineItemChangeSignal signal(
            Fixture fixture,
            String key,
            String lineItemId,
            Instant occurredAt,
            LineItemChangeSignalType type,
            MonitoredLineItemProperty property,
            String propertyValue,
            String dealId,
            AssociationAction action,
            String associationTypeId) {
        return new LineItemChangeSignal(
                UUID.nameUUIDFromBytes((fixture.connection.id() + key).getBytes(StandardCharsets.UTF_8)),
                fixture.tenant.id(),
                fixture.connection.id(),
                "event-" + key,
                "subscription-1",
                new ProviderDeduplicationKey(digest(fixture.connection.id() + key)),
                new ProviderObjectId(lineItemId),
                type,
                occurredAt,
                occurredAt.plusSeconds(1),
                property,
                propertyValue,
                dealId == null ? null : new ProviderObjectId(dealId),
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

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private List<String> auditTypes() {
        return jdbcTemplate.queryForList("""
                SELECT event_type FROM line_item_watch_audit_event ORDER BY occurred_at, event_type
                """, String.class);
    }

    private String snapshotName(String kind) {
        return jdbcTemplate.queryForObject(
                "SELECT name FROM line_item_watch_snapshot WHERE snapshot_kind = ?",
                String.class, kind);
    }

    private String snapshotName(String lineItemId, String kind) {
        return jdbcTemplate.queryForObject("""
                SELECT snapshot.name
                FROM line_item_watch_snapshot snapshot
                JOIN line_item_watch_line_item item ON item.id = snapshot.line_item_id
                WHERE item.external_line_item_id = ? AND snapshot.snapshot_kind = ?
                """, String.class, lineItemId, kind);
    }

    private String signalLineItem(UUID signalId) {
        return jdbcTemplate.queryForObject(
                "SELECT external_line_item_id FROM line_item_watch_change_signal WHERE id = ?",
                String.class, signalId);
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private record Fixture(Tenant tenant, PlatformConnection connection) {
    }
}
