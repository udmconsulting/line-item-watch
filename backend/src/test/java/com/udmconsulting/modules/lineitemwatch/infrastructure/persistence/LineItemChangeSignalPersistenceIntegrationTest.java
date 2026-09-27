package com.udmconsulting.modules.lineitemwatch.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.udmconsulting.modules.lineitemwatch.application.LineItemChangeSignalStore;
import com.udmconsulting.modules.lineitemwatch.domain.AssociationAction;
import com.udmconsulting.modules.lineitemwatch.domain.LineItemChangeSignal;
import com.udmconsulting.modules.lineitemwatch.domain.LineItemChangeSignalType;
import com.udmconsulting.modules.lineitemwatch.domain.MonitoredLineItemProperty;
import com.udmconsulting.modules.lineitemwatch.domain.ProviderDeduplicationKey;
import com.udmconsulting.modules.lineitemwatch.domain.ProviderObjectId;
import com.udmconsulting.platform.connection.application.PlatformConnectionService;
import com.udmconsulting.platform.connection.domain.ConnectionStatus;
import com.udmconsulting.platform.connection.domain.ExternalAccountId;
import com.udmconsulting.platform.connection.domain.PlatformConnection;
import com.udmconsulting.platform.connection.domain.Provider;
import com.udmconsulting.platform.entitlement.application.EntitlementService;
import com.udmconsulting.platform.module.domain.ProductModule;
import com.udmconsulting.platform.tenant.application.TenantService;
import com.udmconsulting.platform.tenant.domain.Tenant;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers
class LineItemChangeSignalPersistenceIntegrationTest {

    private static final Duration LOCK_WAIT_TIMEOUT = Duration.ofSeconds(10);
    private static final Instant OCCURRED_AT = Instant.parse("2026-09-27T11:59:00Z");
    private static final Instant RECEIVED_AT = Instant.parse("2026-09-27T12:00:00Z");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
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
        registry.add("hubspot.webhook.enabled", () -> "false");
    }

    @Autowired
    private TenantService tenantService;

    @Autowired
    private PlatformConnectionService connectionService;

    @Autowired
    private EntitlementService entitlementService;

    @Autowired
    private LineItemChangeSignalStore store;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void clearData() {
        jdbcTemplate.update("DELETE FROM tenant");
    }

    @Test
    void migrationCreatesSignalTableWithTenantConnectionDeduplication() {
        assertThat(jdbcTemplate.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_name = 'line_item_watch_change_signal'
                """, String.class)).contains(
                        "tenant_id",
                        "connection_id",
                        "provider_deduplication_key",
                        "external_line_item_id",
                        "property_value",
                        "external_deal_id");
        assertThat(jdbcTemplate.queryForList("""
                SELECT constraint_name
                FROM information_schema.table_constraints
                WHERE table_name = 'line_item_watch_change_signal'
                """, String.class)).contains(
                        "fk_line_item_watch_change_signal_connection",
                        "uq_line_item_watch_change_signal_dedup",
                        "chk_line_item_watch_change_signal_shape");
    }

    @Test
    void capturesExactEmptyPropertyValueDeletionAndDoesNotMutateDuplicate() {
        Fixture fixture = fixture("property-and-deletion");
        LineItemChangeSignal property = propertySignal(fixture, UUID.randomUUID(), key(1), "");

        LineItemChangeSignalStore.CaptureResult first = store.capture(
                fixture.tenant().id(), fixture.connection().id(), List.of(property));
        LineItemChangeSignal replayWithDifferentNonKeyData = propertySignal(
                fixture, UUID.randomUUID(), key(1), "must-not-replace-empty");
        LineItemChangeSignalStore.CaptureResult duplicate = store.capture(
                fixture.tenant().id(), fixture.connection().id(),
                List.of(replayWithDifferentNonKeyData));
        LineItemChangeSignal deletion = basicSignal(
                fixture, UUID.randomUUID(), key(2), LineItemChangeSignalType.DELETED);
        store.capture(fixture.tenant().id(), fixture.connection().id(), List.of(deletion));

        assertThat(first).isEqualTo(new LineItemChangeSignalStore.CaptureResult(1, 0, true));
        assertThat(duplicate).isEqualTo(new LineItemChangeSignalStore.CaptureResult(0, 1, true));
        assertThat(count()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM line_item_watch_signal_processing",
                Long.class)).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT property_value
                FROM line_item_watch_change_signal
                WHERE signal_type = 'PROPERTY_CHANGED'
                """, String.class)).isEmpty();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM line_item_watch_change_signal
                WHERE signal_type = 'DELETED'
                  AND property_name IS NULL
                  AND external_deal_id IS NULL
                """, Long.class)).isEqualTo(1L);
    }

    @Test
    void capturesAssociationMetadataAndDatabaseRejectsInvalidSignalShape() {
        Fixture fixture = fixture("association");
        LineItemChangeSignal association = new LineItemChangeSignal(
                UUID.randomUUID(),
                fixture.tenant().id(),
                fixture.connection().id(),
                "event-3",
                "subscription-1",
                key(3),
                new ProviderObjectId("line-1"),
                LineItemChangeSignalType.ASSOCIATION_CHANGED,
                OCCURRED_AT,
                RECEIVED_AT,
                null,
                null,
                new ProviderObjectId("deal-1"),
                AssociationAction.ADDED,
                "20",
                "HUBSPOT_DEFINED");

        store.capture(fixture.tenant().id(), fixture.connection().id(), List.of(association));

        assertThat(jdbcTemplate.queryForMap("""
                SELECT external_deal_id, association_action,
                       association_type_id, association_category
                FROM line_item_watch_change_signal
                """)).containsEntry("external_deal_id", "deal-1")
                .containsEntry("association_action", "ADDED")
                .containsEntry("association_type_id", "20")
                .containsEntry("association_category", "HUBSPOT_DEFINED");

        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE line_item_watch_change_signal
                SET property_name = 'name'
                WHERE id = ?
                """, association.id())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void oneConnectionBatchRollsBackAtomically() {
        Fixture fixture = fixture("atomic");
        UUID collidingPrimaryKey = UUID.randomUUID();
        LineItemChangeSignal first = basicSignal(
                fixture, collidingPrimaryKey, key(4), LineItemChangeSignalType.CREATED);
        LineItemChangeSignal second = basicSignal(
                fixture, collidingPrimaryKey, key(5), LineItemChangeSignalType.DELETED);

        assertThatThrownBy(() -> store.capture(
                fixture.tenant().id(), fixture.connection().id(), List.of(first, second)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(count()).isZero();
    }

    @Test
    void databaseRejectsCrossTenantConnectionProvenance() {
        Fixture owner = fixture("owner");
        Tenant other = tenantService.create();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO line_item_watch_change_signal (
                    id, tenant_id, connection_id, provider_event_id,
                    provider_subscription_id, provider_deduplication_key,
                    external_line_item_id, signal_type, occurred_at, received_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                other.id().value(),
                owner.connection().id().value(),
                "event-cross-tenant",
                "subscription-1",
                key(6).value(),
                "line-1",
                "CREATED",
                Timestamp.from(OCCURRED_AT),
                Timestamp.from(RECEIVED_AT))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sameDeduplicationKeyIsIndependentAcrossConnections() {
        Tenant tenant = tenantService.create();
        PlatformConnection first = activate(tenant, "first");
        PlatformConnection second = activate(tenant, "second");
        entitlementService.enable(tenant.id(), ProductModule.LINE_ITEM_WATCH);
        Fixture firstFixture = new Fixture(tenant, first);
        Fixture secondFixture = new Fixture(tenant, second);

        store.capture(tenant.id(), first.id(), List.of(basicSignal(
                firstFixture, UUID.randomUUID(), key(7), LineItemChangeSignalType.CREATED)));
        store.capture(tenant.id(), second.id(), List.of(basicSignal(
                secondFixture, UUID.randomUUID(), key(7), LineItemChangeSignalType.CREATED)));

        assertThat(count()).isEqualTo(2);
    }

    @Test
    void concurrentRedeliveryCapturesExactlyOneSignal() throws Exception {
        Fixture fixture = fixture("concurrent-redelivery");
        LineItemChangeSignal first = basicSignal(
                fixture, UUID.randomUUID(), key(8), LineItemChangeSignalType.CREATED);
        LineItemChangeSignal second = basicSignal(
                fixture, UUID.randomUUID(), key(8), LineItemChangeSignalType.CREATED);
        CountDownLatch start = new CountDownLatch(1);

        LineItemChangeSignalStore.CaptureResult firstResult;
        LineItemChangeSignalStore.CaptureResult secondResult;
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<LineItemChangeSignalStore.CaptureResult> firstFuture = executor.submit(() -> {
                await(start);
                return store.capture(fixture.tenant().id(), fixture.connection().id(), List.of(first));
            });
            Future<LineItemChangeSignalStore.CaptureResult> secondFuture = executor.submit(() -> {
                await(start);
                return store.capture(fixture.tenant().id(), fixture.connection().id(), List.of(second));
            });
            start.countDown();
            firstResult = firstFuture.get();
            secondResult = secondFuture.get();
        }

        assertThat(firstResult.captured() + secondResult.captured()).isEqualTo(1);
        assertThat(firstResult.duplicates() + secondResult.duplicates()).isEqualTo(1);
        assertThat(count()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM line_item_watch_signal_processing",
                Long.class)).isEqualTo(1L);
    }

    @Test
    void tenantAndConnectionDeletionCascadeSignals() {
        Fixture connectionFixture = fixture("connection-cascade");
        store.capture(
                connectionFixture.tenant().id(),
                connectionFixture.connection().id(),
                List.of(basicSignal(
                        connectionFixture, UUID.randomUUID(), key(9), LineItemChangeSignalType.CREATED)));
        jdbcTemplate.update(
                "DELETE FROM platform_connection WHERE id = ?",
                connectionFixture.connection().id().value());
        assertThat(count()).isZero();

        Fixture tenantFixture = fixture("tenant-cascade");
        store.capture(
                tenantFixture.tenant().id(),
                tenantFixture.connection().id(),
                List.of(basicSignal(
                        tenantFixture, UUID.randomUUID(), key(10), LineItemChangeSignalType.CREATED)));
        jdbcTemplate.update("DELETE FROM tenant WHERE id = ?", tenantFixture.tenant().id().value());
        assertThat(count()).isZero();
    }

    @Test
    void disconnectWinningAtCommitGuardPreventsSignalPersistence() throws Exception {
        Fixture fixture = fixture("disconnect-race");
        assertConcurrentCommitGuardRejects(fixture, (connection, current) -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE platform_connection
                    SET status = 'DISCONNECTED', status_changed_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """)) {
                statement.setObject(1, current.connection().id().value());
                assertThat(statement.executeUpdate()).isEqualTo(1);
            }
        });
    }

    @Test
    void reauthenticationWinningAtCommitGuardPreventsSignalPersistence() throws Exception {
        Fixture fixture = fixture("reauth-race");
        assertConcurrentCommitGuardRejects(fixture, (connection, current) -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE platform_connection
                    SET status = 'REAUTH_REQUIRED', status_changed_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """)) {
                statement.setObject(1, current.connection().id().value());
                assertThat(statement.executeUpdate()).isEqualTo(1);
            }
        });
    }

    @Test
    void entitlementRemovalWinningAtCommitGuardPreventsSignalPersistence() throws Exception {
        Fixture fixture = fixture("entitlement-race");
        assertConcurrentCommitGuardRejects(fixture, (connection, current) -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    DELETE FROM tenant_entitlement
                    WHERE tenant_id = ? AND product_module = 'LINE_ITEM_WATCH'
                    """)) {
                statement.setObject(1, current.tenant().id().value());
                assertThat(statement.executeUpdate()).isEqualTo(1);
            }
        });
    }

    private Fixture fixture(String accountId) {
        Tenant tenant = tenantService.create();
        PlatformConnection connection = activate(tenant, accountId);
        entitlementService.enable(tenant.id(), ProductModule.LINE_ITEM_WATCH);
        return new Fixture(tenant, connection);
    }

    private PlatformConnection activate(Tenant tenant, String accountId) {
        PlatformConnection registered = connectionService.register(
                tenant.id(), Provider.HUBSPOT, new ExternalAccountId(accountId));
        jdbcTemplate.update("""
                UPDATE platform_connection
                SET status = 'ACTIVE', status_changed_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, registered.id().value());
        PlatformConnection active = connectionService.findForTenant(tenant.id(), registered.id())
                .orElseThrow();
        assertThat(active.status()).isEqualTo(ConnectionStatus.ACTIVE);
        return active;
    }

    private void assertConcurrentCommitGuardRejects(
            Fixture fixture, SqlMutation mutation) throws Exception {
        CompletableFuture<Integer> mutationBackendPid = new CompletableFuture<>();
        CountDownLatch allowCommit = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<Integer> mutationFuture = null;
        Future<LineItemChangeSignalStore.CaptureResult> persistenceFuture = null;
        try {
            mutationFuture = executor.submit(() -> runUncommittedMutation(
                    fixture, mutation, mutationBackendPid, allowCommit));
            int blockerPid = mutationBackendPid.get(
                    LOCK_WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            persistenceFuture = executor.submit(() -> store.capture(
                    fixture.tenant().id(),
                    fixture.connection().id(),
                    List.of(basicSignal(
                            fixture, UUID.randomUUID(), key(50), LineItemChangeSignalType.CREATED))));

            assertThat(awaitBlockedByBackend(blockerPid, LOCK_WAIT_TIMEOUT)).isNotEqualTo(blockerPid);
            allowCommit.countDown();
            assertThat(mutationFuture.get(
                    LOCK_WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isEqualTo(blockerPid);
            assertThat(persistenceFuture.get(
                    LOCK_WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                    .isEqualTo(LineItemChangeSignalStore.CaptureResult.ineligible());
        } finally {
            allowCommit.countDown();
            cancelIfRunning(persistenceFuture);
            cancelIfRunning(mutationFuture);
            executor.shutdownNow();
            if (!executor.awaitTermination(
                    LOCK_WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("timed out cleaning up concurrent test executor");
            }
        }
        assertThat(count()).isZero();
    }

    private int runUncommittedMutation(
            Fixture fixture,
            SqlMutation mutation,
            CompletableFuture<Integer> backendPid,
            CountDownLatch allowCommit) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                int pid = backendPid(connection);
                mutation.apply(connection, fixture);
                backendPid.complete(pid);
                await(allowCommit);
                connection.commit();
                return pid;
            } catch (Throwable failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                backendPid.completeExceptionally(failure);
                if (failure instanceof Exception exception) {
                    throw exception;
                }
                throw (Error) failure;
            }
        }
    }

    private int awaitBlockedByBackend(int blockerPid, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        try (Connection observer = dataSource.getConnection();
                PreparedStatement statement = observer.prepareStatement("""
                        SELECT activity.pid
                        FROM pg_stat_activity activity
                        WHERE activity.datname = current_database()
                          AND activity.backend_type = 'client backend'
                          AND activity.pid <> ?
                          AND activity.state = 'active'
                          AND activity.wait_event_type = 'Lock'
                          AND ? = ANY(pg_blocking_pids(activity.pid))
                        """)) {
            statement.setInt(1, blockerPid);
            statement.setInt(2, blockerPid);
            while (System.nanoTime() < deadline) {
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) {
                        return result.getInt(1);
                    }
                }
                TimeUnit.MILLISECONDS.sleep(20);
            }
        }
        throw new AssertionError("PostgreSQL did not report a signal capture blocked by backend "
                + blockerPid + " within " + timeout);
    }

    private static int backendPid(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_backend_pid()");
                ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                throw new SQLException("PostgreSQL did not return a backend PID");
            }
            return result.getInt(1);
        }
    }

    private static LineItemChangeSignal basicSignal(
            Fixture fixture,
            UUID id,
            ProviderDeduplicationKey key,
            LineItemChangeSignalType type) {
        return new LineItemChangeSignal(
                id,
                fixture.tenant().id(),
                fixture.connection().id(),
                "event-" + Byte.toUnsignedInt(key.value()[0]),
                "subscription-1",
                key,
                new ProviderObjectId("line-1"),
                type,
                OCCURRED_AT,
                RECEIVED_AT,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private static LineItemChangeSignal propertySignal(
            Fixture fixture,
            UUID id,
            ProviderDeduplicationKey key,
            String value) {
        return new LineItemChangeSignal(
                id,
                fixture.tenant().id(),
                fixture.connection().id(),
                "event-" + Byte.toUnsignedInt(key.value()[0]),
                "subscription-1",
                key,
                new ProviderObjectId("line-1"),
                LineItemChangeSignalType.PROPERTY_CHANGED,
                OCCURRED_AT,
                RECEIVED_AT,
                MonitoredLineItemProperty.NAME,
                value,
                null,
                null,
                null,
                null);
    }

    private static ProviderDeduplicationKey key(int discriminator) {
        byte[] value = new byte[32];
        value[0] = (byte) discriminator;
        return new ProviderDeduplicationKey(value);
    }

    private long count() {
        return Objects.requireNonNull(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM line_item_watch_change_signal", Long.class));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for concurrent test step");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", exception);
        }
    }

    private static void cancelIfRunning(Future<?> future) {
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }
    }

    @FunctionalInterface
    private interface SqlMutation {

        void apply(Connection connection, Fixture fixture) throws SQLException;
    }

    private record Fixture(Tenant tenant, PlatformConnection connection) {
    }
}
