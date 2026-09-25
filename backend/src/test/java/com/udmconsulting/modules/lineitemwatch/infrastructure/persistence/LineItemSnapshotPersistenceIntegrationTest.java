package com.udmconsulting.modules.lineitemwatch.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.udmconsulting.modules.lineitemwatch.application.BaselineSyncException;
import com.udmconsulting.modules.lineitemwatch.application.LineItemSnapshotStore;
import com.udmconsulting.modules.lineitemwatch.domain.BillingStart;
import com.udmconsulting.modules.lineitemwatch.domain.LineItemObservation;
import com.udmconsulting.modules.lineitemwatch.domain.ProviderObjectId;
import com.udmconsulting.modules.lineitemwatch.domain.RecurringPeriod;
import com.udmconsulting.platform.connection.application.PlatformConnectionService;
import com.udmconsulting.platform.connection.domain.ConnectionStatus;
import com.udmconsulting.platform.connection.domain.ExternalAccountId;
import com.udmconsulting.platform.connection.domain.PlatformConnection;
import com.udmconsulting.platform.connection.domain.Provider;
import com.udmconsulting.platform.entitlement.application.EntitlementService;
import com.udmconsulting.platform.module.domain.ProductModule;
import com.udmconsulting.platform.tenant.application.TenantService;
import com.udmconsulting.platform.tenant.domain.Tenant;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
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
class LineItemSnapshotPersistenceIntegrationTest {

    private static final Duration LOCK_WAIT_TIMEOUT = Duration.ofSeconds(10);

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
    }

    @Autowired
    private TenantService tenantService;

    @Autowired
    private PlatformConnectionService connectionService;

    @Autowired
    private EntitlementService entitlementService;

    @Autowired
    private LineItemSnapshotStore store;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void clearData() {
        jdbcTemplate.update("DELETE FROM tenant");
    }

    @Test
    void migrationCreatesModuleTablesAndCompositeConnectionConstraint() {
        assertThat(jdbcTemplate.queryForList("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                """, String.class)).contains(
                        "line_item_watch_line_item",
                        "line_item_watch_snapshot",
                        "line_item_watch_snapshot_deal");

        assertThat(jdbcTemplate.queryForList("""
                SELECT constraint_name
                FROM information_schema.table_constraints
                WHERE table_name = 'platform_connection'
                """, String.class)).contains("uq_platform_connection_tenant_id_id");

        assertThat(jdbcTemplate.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_name = 'line_item_watch_snapshot'
                """, String.class))
                .contains("billing_start_date", "billing_start_delay_unit", "billing_start_delay_count")
                .doesNotContain("hs_billing_start_delay_type", "hs_line_item_currency_code", "raw_json");
    }

    @Test
    void establishesImmutableBaselineAndReplaceableLatestIdempotently() {
        Fixture fixture = fixture("account-1");
        LineItemObservation initial = observation(
                "Initial name",
                "2026-09-20T10:00:00Z",
                "2026-09-20T10:01:00Z",
                Set.of("deal-1", "deal-2"));

        LineItemSnapshotStore.PersistenceResult first = store.establish(
                fixture.tenant().id(), fixture.connection().id(), List.of(initial));
        LineItemSnapshotStore.PersistenceResult repeated = store.establish(
                fixture.tenant().id(), fixture.connection().id(), List.of(initial));

        assertThat(first.createdLineItems()).isEqualTo(1);
        assertThat(first.createdBaselines()).isEqualTo(1);
        assertThat(first.createdLatestSnapshots()).isEqualTo(1);
        assertThat(repeated.unchangedLatestSnapshots()).isEqualTo(1);
        assertThat(count("line_item_watch_line_item")).isEqualTo(1);
        assertThat(count("line_item_watch_snapshot")).isEqualTo(2);
        assertThat(count("line_item_watch_snapshot_deal")).isEqualTo(4);

        LineItemObservation newer = observation(
                "Updated name",
                "2026-09-21T10:00:00Z",
                "2026-09-21T10:01:00Z",
                Set.of("deal-2", "deal-3"));
        LineItemSnapshotStore.PersistenceResult update = store.establish(
                fixture.tenant().id(), fixture.connection().id(), List.of(newer));

        assertThat(update.updatedLatestSnapshots()).isEqualTo(1);
        assertThat(snapshotName("BASELINE")).isEqualTo("Initial name");
        assertThat(snapshotName("LATEST")).isEqualTo("Updated name");
        assertThat(dealIds("BASELINE")).containsExactlyInAnyOrder("deal-1", "deal-2");
        assertThat(dealIds("LATEST")).containsExactlyInAnyOrder("deal-2", "deal-3");
    }

    @Test
    void olderObservationCannotOverwriteLatest() {
        Fixture fixture = fixture("account-2");
        LineItemObservation newer = observation(
                "Newer",
                "2026-09-22T10:00:00Z",
                "2026-09-22T10:01:00Z",
                Set.of("deal-1"));
        LineItemObservation older = observation(
                "Older",
                "2026-09-21T10:00:00Z",
                "2026-09-23T10:01:00Z",
                Set.of("deal-2"));

        store.establish(fixture.tenant().id(), fixture.connection().id(), List.of(newer));
        LineItemSnapshotStore.PersistenceResult result = store.establish(
                fixture.tenant().id(), fixture.connection().id(), List.of(older));

        assertThat(result.unchangedLatestSnapshots()).isEqualTo(1);
        assertThat(snapshotName("LATEST")).isEqualTo("Newer");
        assertThat(dealIds("LATEST")).containsExactly("deal-1");
    }

    @Test
    void equalProviderTimestampUsesLaterObservationAsLatest() {
        Fixture fixture = fixture("account-equal-provider-time");
        LineItemObservation first = observation(
                "First observation",
                "2026-09-22T10:00:00Z",
                "2026-09-22T10:01:00Z",
                Set.of("deal-1"));
        LineItemObservation later = observation(
                "Later observation",
                "2026-09-22T10:00:00Z",
                "2026-09-22T10:02:00Z",
                Set.of("deal-2"));
        LineItemObservation stale = observation(
                "Stale observation",
                "2026-09-22T10:00:00Z",
                "2026-09-22T10:01:30Z",
                Set.of("deal-3"));

        store.establish(fixture.tenant().id(), fixture.connection().id(), List.of(first));
        LineItemSnapshotStore.PersistenceResult result = store.establish(
                fixture.tenant().id(), fixture.connection().id(), List.of(later));
        LineItemSnapshotStore.PersistenceResult staleResult = store.establish(
                fixture.tenant().id(), fixture.connection().id(), List.of(stale));

        assertThat(result.updatedLatestSnapshots()).isEqualTo(1);
        assertThat(staleResult.unchangedLatestSnapshots()).isEqualTo(1);
        assertThat(snapshotName("BASELINE")).isEqualTo("First observation");
        assertThat(snapshotName("LATEST")).isEqualTo("Later observation");
        assertThat(dealIds("LATEST")).containsExactly("deal-2");
    }

    @Test
    void newerExplicitNullsClearLatestWithoutChangingBaseline() {
        Fixture fixture = fixture("account-clears");
        LineItemObservation populated = observation(
                "Populated",
                "2026-09-20T10:00:00Z",
                "2026-09-20T10:01:00Z",
                Set.of("deal-1"));
        LineItemObservation cleared = new LineItemObservation(
                populated.lineItemId(),
                null,
                null,
                null,
                null,
                null,
                null,
                BillingStart.unspecified(),
                null,
                populated.providerCreatedAt(),
                Instant.parse("2026-09-21T10:00:00Z"),
                Instant.parse("2026-09-21T10:01:00Z"),
                populated.associatedDealIds());

        store.establish(fixture.tenant().id(), fixture.connection().id(), List.of(populated));
        store.establish(fixture.tenant().id(), fixture.connection().id(), List.of(cleared));

        assertThat(snapshotName("BASELINE")).isEqualTo("Populated");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM line_item_watch_snapshot
                WHERE snapshot_kind = 'LATEST'
                  AND name IS NULL
                  AND quantity IS NULL
                  AND unit_price IS NULL
                  AND unit_discount IS NULL
                  AND discount_percentage IS NULL
                  AND billing_frequency IS NULL
                  AND billing_start_date IS NULL
                  AND billing_start_delay_unit IS NULL
                  AND billing_start_delay_count IS NULL
                  AND recurring_billing_period IS NULL
                """, Long.class)).isEqualTo(1L);
    }

    @Test
    void oneDealObservationSetPersistsAtomically() {
        Fixture fixture = fixture("account-atomic");
        LineItemObservation valid = observation(
                "Valid",
                "2026-09-20T10:00:00Z",
                "2026-09-20T10:01:00Z",
                Set.of("deal-1"));
        LineItemObservation invalidForDatabasePrecision = new LineItemObservation(
                new ProviderObjectId("line-2"),
                "Too large",
                BigDecimal.TEN.pow(100),
                null,
                null,
                null,
                null,
                BillingStart.unspecified(),
                null,
                Instant.parse("2026-09-01T09:00:00Z"),
                Instant.parse("2026-09-20T10:00:00Z"),
                Instant.parse("2026-09-20T10:01:00Z"),
                Set.of(new ProviderObjectId("deal-1")));

        assertThatThrownBy(() -> store.establish(
                fixture.tenant().id(),
                fixture.connection().id(),
                List.of(valid, invalidForDatabasePrecision)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(count("line_item_watch_line_item")).isZero();
        assertThat(count("line_item_watch_snapshot")).isZero();
        assertThat(count("line_item_watch_snapshot_deal")).isZero();
    }

    @Test
    void databaseRejectsCrossTenantConnectionProvenance() {
        Fixture owner = fixture("account-owner");
        Tenant otherTenant = tenantService.create();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO line_item_watch_line_item
                    (id, tenant_id, connection_id, external_line_item_id)
                VALUES (?, ?, ?, ?)
                """,
                java.util.UUID.randomUUID(),
                otherTenant.id().value(),
                owner.connection().id().value(),
                "cross-tenant-line"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sameExternalIdDoesNotCollideAcrossConnections() {
        Tenant tenant = tenantService.create();
        PlatformConnection first = connectionService.register(
                tenant.id(), Provider.HUBSPOT, new ExternalAccountId("account-a"));
        PlatformConnection second = connectionService.register(
                tenant.id(), Provider.HUBSPOT, new ExternalAccountId("account-b"));
        jdbcTemplate.update("""
                UPDATE platform_connection
                SET status = 'ACTIVE', status_changed_at = CURRENT_TIMESTAMP
                WHERE id IN (?, ?)
                """, first.id().value(), second.id().value());
        entitlementService.enable(tenant.id(), ProductModule.LINE_ITEM_WATCH);
        LineItemObservation observation = observation(
                "Same provider ID",
                "2026-09-20T10:00:00Z",
                "2026-09-20T10:01:00Z",
                Set.of("deal-1"));

        store.establish(tenant.id(), first.id(), List.of(observation));
        store.establish(tenant.id(), second.id(), List.of(observation));

        assertThat(count("line_item_watch_line_item")).isEqualTo(2);
    }

    @Test
    void concurrentFirstRunsProduceOneIdentityAndNewestLatest() throws Exception {
        Fixture fixture = fixture("account-concurrent");
        LineItemObservation older = observation(
                "Older",
                "2026-09-20T10:00:00Z",
                "2026-09-20T10:01:00Z",
                Set.of("deal-1"));
        LineItemObservation newer = observation(
                "Newer",
                "2026-09-21T10:00:00Z",
                "2026-09-21T10:01:00Z",
                Set.of("deal-1", "deal-2"));
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> {
                await(start);
                store.establish(fixture.tenant().id(), fixture.connection().id(), List.of(older));
            });
            Future<?> second = executor.submit(() -> {
                await(start);
                store.establish(fixture.tenant().id(), fixture.connection().id(), List.of(newer));
            });
            start.countDown();
            first.get();
            second.get();
        }

        assertThat(count("line_item_watch_line_item")).isEqualTo(1);
        assertThat(count("line_item_watch_snapshot")).isEqualTo(2);
        assertThat(snapshotName("LATEST")).isEqualTo("Newer");
        assertThat(dealIds("LATEST")).containsExactlyInAnyOrder("deal-1", "deal-2");
    }

    @Test
    void disconnectWinningAtCommitGuardPreventsAllSnapshotPersistence() throws Exception {
        Fixture fixture = fixture("account-disconnect-race");

        assertConcurrentCommitGuardRejects(fixture, (connection, ignored) -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE platform_connection
                    SET status = 'DISCONNECTED', status_changed_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """)) {
                statement.setObject(1, fixture.connection().id().value());
                assertThat(statement.executeUpdate()).isEqualTo(1);
            }
        });

        assertThat(connectionService.findForTenant(
                fixture.tenant().id(), fixture.connection().id()).orElseThrow().status())
                .isEqualTo(ConnectionStatus.DISCONNECTED);
    }

    @Test
    void reauthenticationWinningAtCommitGuardPreventsAllSnapshotPersistence() throws Exception {
        Fixture fixture = fixture("account-reauth-race");

        assertConcurrentCommitGuardRejects(fixture, (connection, ignored) -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE platform_connection
                    SET status = 'REAUTH_REQUIRED', status_changed_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """)) {
                statement.setObject(1, fixture.connection().id().value());
                assertThat(statement.executeUpdate()).isEqualTo(1);
            }
        });

        assertThat(connectionService.findForTenant(
                fixture.tenant().id(), fixture.connection().id()).orElseThrow().status())
                .isEqualTo(ConnectionStatus.REAUTH_REQUIRED);
    }

    @Test
    void entitlementRemovalWinningAtCommitGuardPreventsAllSnapshotPersistence() throws Exception {
        Fixture fixture = fixture("account-entitlement-race");

        assertConcurrentCommitGuardRejects(fixture, (connection, ignored) -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    DELETE FROM tenant_entitlement
                    WHERE tenant_id = ? AND product_module = 'LINE_ITEM_WATCH'
                    """)) {
                statement.setObject(1, fixture.tenant().id().value());
                assertThat(statement.executeUpdate()).isEqualTo(1);
            }
        });

        assertThat(entitlementService.isEnabled(
                fixture.tenant().id(), ProductModule.LINE_ITEM_WATCH)).isFalse();
    }

    private Fixture fixture(String accountId) {
        Tenant tenant = tenantService.create();
        PlatformConnection registered = connectionService.register(
                tenant.id(), Provider.HUBSPOT, new ExternalAccountId(accountId));
        jdbcTemplate.update("""
                UPDATE platform_connection
                SET status = 'ACTIVE', status_changed_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, registered.id().value());
        entitlementService.enable(tenant.id(), ProductModule.LINE_ITEM_WATCH);
        PlatformConnection connection = connectionService.findForTenant(tenant.id(), registered.id())
                .orElseThrow();
        assertThat(connection.status()).isEqualTo(ConnectionStatus.ACTIVE);
        return new Fixture(tenant, connection);
    }

    private void assertConcurrentCommitGuardRejects(
            Fixture fixture, SqlMutation concurrentMutation) throws Exception {
        CompletableFuture<Integer> mutationBackendPid = new CompletableFuture<>();
        CountDownLatch allowMutationCommit = new CountDownLatch(1);
        LineItemObservation observation = observation(
                "Must not persist",
                "2026-09-22T10:00:00Z",
                "2026-09-22T10:01:00Z",
                Set.of("deal-1"));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<Integer> mutation = null;
        Future<?> persistence = null;
        try {
            mutation = executor.submit(() -> runUncommittedMutation(
                    fixture, concurrentMutation, mutationBackendPid, allowMutationCommit));
            int blockerPid = mutationBackendPid.get(
                    LOCK_WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

            persistence = executor.submit(() ->
                    store.establish(
                        fixture.tenant().id(), fixture.connection().id(), List.of(observation)));
            int waitingPid = awaitBlockedByBackend(blockerPid, LOCK_WAIT_TIMEOUT);
            assertThat(waitingPid).isNotEqualTo(blockerPid);

            allowMutationCommit.countDown();
            assertThat(mutation.get(
                    LOCK_WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isEqualTo(blockerPid);

            Future<?> persistenceResult = persistence;
            assertThatThrownBy(() -> persistenceResult.get(
                    LOCK_WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(BaselineSyncException.class);
        } finally {
            allowMutationCommit.countDown();
            cancelIfRunning(persistence);
            cancelIfRunning(mutation);
            executor.shutdownNow();
            if (!executor.awaitTermination(
                    LOCK_WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("timed out cleaning up concurrent test executor");
            }
        }

        assertThat(count("line_item_watch_line_item")).isZero();
        assertThat(count("line_item_watch_snapshot")).isZero();
        assertThat(count("line_item_watch_snapshot_deal")).isZero();
    }

    private int runUncommittedMutation(
            Fixture fixture,
            SqlMutation mutation,
            CompletableFuture<Integer> mutationBackendPid,
            CountDownLatch allowCommit) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                int backendPid = backendPid(connection);
                mutation.apply(connection, fixture);
                mutationBackendPid.complete(backendPid);
                await(allowCommit);
                connection.commit();
                return backendPid;
            } catch (Throwable failure) {
                rollback(connection, failure);
                mutationBackendPid.completeExceptionally(failure);
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
        throw new AssertionError(
                "PostgreSQL did not report a persistence backend lock-blocked by mutation backend "
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

    private static void rollback(Connection connection, Throwable failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private static void cancelIfRunning(Future<?> future) {
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }
    }

    private static LineItemObservation observation(
            String name, String providerUpdatedAt, String observedAt, Set<String> dealIds) {
        return new LineItemObservation(
                new ProviderObjectId("line-1"),
                name,
                new BigDecimal("2.5000"),
                new BigDecimal("100.00"),
                new BigDecimal("5.00"),
                new BigDecimal("10.0000"),
                "monthly",
                BillingStart.on(LocalDate.parse("2026-10-01")),
                RecurringPeriod.parse("P12M"),
                Instant.parse("2026-09-01T09:00:00Z"),
                Instant.parse(providerUpdatedAt),
                Instant.parse(observedAt),
                dealIds.stream().map(ProviderObjectId::new).collect(java.util.stream.Collectors.toSet()));
    }

    private long count(String table) {
        return Objects.requireNonNull(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table, Long.class));
    }

    private String snapshotName(String kind) {
        return jdbcTemplate.queryForObject(
                "SELECT name FROM line_item_watch_snapshot WHERE snapshot_kind = ?",
                String.class,
                kind);
    }

    private List<String> dealIds(String kind) {
        return jdbcTemplate.queryForList("""
                SELECT external_deal_id
                FROM line_item_watch_snapshot_deal
                WHERE snapshot_kind = ?
                ORDER BY external_deal_id
                """, String.class, kind);
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

    @FunctionalInterface
    private interface SqlMutation {

        void apply(Connection connection, Fixture fixture) throws SQLException;
    }

    private record Fixture(Tenant tenant, PlatformConnection connection) {
    }
}
