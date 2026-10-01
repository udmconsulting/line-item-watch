package com.udmconsulting.modules.lineitemwatch.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.udmconsulting.modules.lineitemwatch.application.DealAuditQuery;
import com.udmconsulting.modules.lineitemwatch.application.DealAuditView;
import com.udmconsulting.modules.lineitemwatch.application.LineItemChangeSignalStore;
import com.udmconsulting.modules.lineitemwatch.application.LineItemSignalProcessingStore;
import com.udmconsulting.modules.lineitemwatch.application.LineItemSnapshotStore;
import com.udmconsulting.modules.lineitemwatch.application.ReadDealAudit;
import com.udmconsulting.modules.lineitemwatch.domain.AssociationAction;
import com.udmconsulting.modules.lineitemwatch.domain.BillingStart;
import com.udmconsulting.modules.lineitemwatch.domain.LineItemChangeSignal;
import com.udmconsulting.modules.lineitemwatch.domain.LineItemChangeSignalType;
import com.udmconsulting.modules.lineitemwatch.domain.LineItemHistoryCoverage;
import com.udmconsulting.modules.lineitemwatch.domain.LineItemObservation;
import com.udmconsulting.modules.lineitemwatch.domain.LineItemAuditType;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = "line-item-watch.processing.enabled=false")
@Testcontainers
class DealAuditReadPersistenceIntegrationTest {

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

    @Autowired TenantService tenantService;
    @Autowired PlatformConnectionService connectionService;
    @Autowired EntitlementService entitlementService;
    @Autowired LineItemSnapshotStore snapshotStore;
    @Autowired LineItemChangeSignalStore signalStore;
    @Autowired LineItemSignalProcessingStore processingStore;
    @Autowired ReadDealAudit readDealAudit;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ApplicationContext applicationContext;

    @BeforeEach
    void clearData() {
        jdbcTemplate.update("DELETE FROM tenant");
    }

    @Test
    void migrationCreatesCoverageAndDealChronologyWithParentConsistency() {
        assertThat(jdbcTemplate.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_name = 'line_item_watch_snapshot'
                """, String.class)).contains("history_coverage_mode", "history_observed_from");
        assertThat(jdbcTemplate.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_name = 'line_item_watch_audit_event_deal_context'
                """, String.class)).contains(
                        "occurred_at", "semantic_key", "line_item_id", "event_type", "property_name");
        assertThat(jdbcTemplate.queryForList("""
                SELECT indexname FROM pg_indexes
                WHERE schemaname = 'public'
                  AND tablename = 'line_item_watch_audit_event_deal_context'
                """, String.class)).contains(
                        "idx_line_item_watch_audit_event_deal_chronology",
                        "idx_liw_audit_context_line_item_chronology",
                        "idx_liw_audit_context_event_type_chronology",
                        "idx_liw_audit_context_property_chronology");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_extension WHERE extname = 'pg_trgm'", Long.class))
                .isEqualTo(1L);
        assertThat(jdbcTemplate.queryForList("""
                SELECT indexname FROM pg_indexes
                WHERE schemaname = 'public' AND tablename = 'line_item_watch_snapshot'
                """, String.class)).contains("idx_liw_snapshot_latest_name_trgm");

        Fixture fixture = fixture("migration-consistency");
        Instant time = Instant.parse("2026-09-28T10:00:00Z");
        signalStore.capture(fixture.tenant().id(), fixture.connection().id(), List.of(
                signal(fixture, "associate", "line-1", time,
                        LineItemChangeSignalType.ASSOCIATION_CHANGED,
                        null, null, "1001", AssociationAction.ADDED, "20")));
        processAll();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE line_item_watch_audit_event_deal_context
                SET semantic_key = ?
                """, new byte[32]))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void customerEndpointIsDisabledByDefault() {
        assertThat(applicationContext.containsBean("hubSpotDealAuditController")).isFalse();
        assertThat(applicationContext.containsBean("hubSpotUiExtensionRequestAuthenticator")).isFalse();
    }

    @Test
    void migrationBackfillsExisting005RowsWithoutInventingEarlierCoverage() throws Exception {
        jdbcTemplate.execute("CREATE SCHEMA p6_upgrade_test");
        Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Database database = DatabaseFactory.getInstance()
                .findCorrectDatabaseImplementation(new JdbcConnection(connection));
        try (Liquibase liquibase = new Liquibase(
                        "db/changelog/db.changelog-master.yaml",
                        new ClassLoaderResourceAccessor(),
                        database)) {
            database.setDefaultSchemaName("p6_upgrade_test");
            liquibase.update(18, new Contexts(), new LabelExpression());
            connection.setSchema("p6_upgrade_test");
            insert005BackfillFixture(connection);

            liquibase.update();

            try (Statement statement = connection.createStatement();
                    ResultSet coverage = statement.executeQuery("""
                            SELECT item.external_line_item_id,
                                   snapshot.history_coverage_mode,
                                   snapshot.history_observed_from
                            FROM line_item_watch_snapshot snapshot
                            JOIN line_item_watch_line_item item ON item.id = snapshot.line_item_id
                            WHERE snapshot.snapshot_kind = 'LATEST'
                            ORDER BY item.external_line_item_id
                            """)) {
                assertThat(coverage.next()).isTrue();
                assertThat(coverage.getString(1)).isEqualTo("baseline-line");
                assertThat(coverage.getString(2)).isEqualTo("BASELINE_ANCHORED");
                assertThat(coverage.getTimestamp(3).toInstant())
                        .isEqualTo(Instant.parse("2026-09-28T10:00:00Z"));
                assertThat(coverage.next()).isTrue();
                assertThat(coverage.getString(1)).isEqualTo("deleted-before-baseline-line");
                assertThat(coverage.getString(2)).isEqualTo("SIGNAL_FIRST");
                assertThat(coverage.getTimestamp(3).toInstant())
                        .isEqualTo(Instant.parse("2026-09-28T09:59:00Z"));
                assertThat(coverage.next()).isTrue();
                assertThat(coverage.getString(1)).isEqualTo("signal-line");
                assertThat(coverage.getString(2)).isEqualTo("SIGNAL_FIRST");
                assertThat(coverage.getTimestamp(3).toInstant())
                        .isEqualTo(Instant.parse("2026-09-28T11:00:00Z"));
                assertThat(coverage.next()).isFalse();
            }
            try (Statement statement = connection.createStatement();
                    ResultSet context = statement.executeQuery("""
                            SELECT context.occurred_at = event.occurred_at,
                                   context.semantic_key = event.semantic_key,
                                   context.line_item_id = event.line_item_id,
                                   context.event_type = event.event_type,
                                   context.property_name IS NOT DISTINCT FROM event.property_name
                            FROM line_item_watch_audit_event_deal_context context
                            JOIN line_item_watch_audit_event event
                              ON event.id = context.audit_event_id
                            """)) {
                assertThat(context.next()).isTrue();
                assertThat(context.getBoolean(1)).isTrue();
                assertThat(context.getBoolean(2)).isTrue();
                assertThat(context.getBoolean(3)).isTrue();
                assertThat(context.getBoolean(4)).isTrue();
                assertThat(context.getBoolean(5)).isTrue();
            }
        } finally {
            if (!connection.isClosed()) {
                connection.close();
            }
        }
    }

    @Test
    void baselineCoverageAndHistoricalDisassociationRemainDealRelevant() {
        Fixture fixture = fixture("baseline-history");
        Instant baselineAt = Instant.parse("2026-09-28T10:00:00Z");
        snapshotStore.establish(fixture.tenant().id(), fixture.connection().id(), List.of(
                observation("line-1", "Known", baselineAt.minusSeconds(600), baselineAt, "1001")));
        signalStore.capture(fixture.tenant().id(), fixture.connection().id(), List.of(
                signal(fixture, "pre-baseline-association", "line-1", baselineAt.minusSeconds(60),
                        LineItemChangeSignalType.ASSOCIATION_CHANGED,
                        null, null, "1001", AssociationAction.ADDED, "20"),
                signal(fixture, "remove", "line-1", baselineAt.plusSeconds(60),
                        LineItemChangeSignalType.ASSOCIATION_CHANGED,
                        null, null, "1001", AssociationAction.REMOVED, "20")));
        processAll();

        DealAuditView result = read(fixture, "1001", 20, null, 20, null);

        assertThat(result.lineItems().items()).hasSize(1);
        DealAuditView.LineItemSummary item = result.lineItems().items().getFirst();
        assertThat(item.currentMembership()).isEqualTo(DealAuditView.Membership.ABSENT);
        assertThat(item.historicalRelevance()).isTrue();
        assertThat(item.historyCoverage().mode())
                .isEqualTo(LineItemHistoryCoverage.Mode.BASELINE_ANCHORED);
        assertThat(item.historyCoverage().observedFrom()).isEqualTo(baselineAt);
        assertThat(item.historyCoverage().hasUnknownState()).isFalse();
        assertThat(result.events().items())
                .extracting(DealAuditView.AuditEvent::type)
                .containsExactly(com.udmconsulting.modules.lineitemwatch.domain.LineItemAuditType.DEAL_DISASSOCIATED);
    }

    @Test
    void checkpointIgnoredAfterEarlierDeletionDoesNotInventDealRelevance() {
        Fixture fixture = fixture("ignored-post-delete-baseline");
        Instant baselineAt = Instant.parse("2026-09-28T10:00:00Z");
        Instant deletedAt = baselineAt.minusSeconds(60);
        snapshotStore.establish(fixture.tenant().id(), fixture.connection().id(), List.of(
                observation("line-1", "Stale", baselineAt.minusSeconds(600), baselineAt, "1001")));
        signalStore.capture(fixture.tenant().id(), fixture.connection().id(), List.of(
                signal(fixture, "earlier-delete", "line-1", deletedAt,
                        LineItemChangeSignalType.DELETED,
                        null, null, null, null, null)));
        processAll();

        DealAuditView result = read(fixture, "1001", 20, null, 20, null);

        assertThat(result.lineItems().items()).isEmpty();
        assertThat(result.events().items()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT history_coverage_mode = 'SIGNAL_FIRST'
                       AND history_observed_from = ?
                FROM line_item_watch_snapshot
                WHERE tenant_id = ? AND connection_id = ?
                  AND snapshot_kind = 'LATEST'
                """, Boolean.class, Timestamp.from(deletedAt),
                fixture.tenant().id().value(), fixture.connection().id().value())).isTrue();
    }

    @Test
    void signalFirstCoverageStartsAtEvidenceAndFreezesDeletedMembershipBeforeCleanup() {
        Fixture fixture = fixture("signal-first-deleted");
        Instant firstEvidence = Instant.parse("2026-09-28T11:00:00Z");
        signalStore.capture(fixture.tenant().id(), fixture.connection().id(), List.of(
                signal(fixture, "name", "line-1", firstEvidence,
                        LineItemChangeSignalType.PROPERTY_CHANGED,
                        MonitoredLineItemProperty.NAME, "Observed", null, null, null),
                signal(fixture, "add", "line-1", firstEvidence.plusSeconds(1),
                        LineItemChangeSignalType.ASSOCIATION_CHANGED,
                        null, null, "1001", AssociationAction.ADDED, "20"),
                signal(fixture, "delete", "line-1", firstEvidence.plusSeconds(2),
                        LineItemChangeSignalType.DELETED,
                        null, null, null, null, null),
                signal(fixture, "cleanup", "line-1", firstEvidence.plusSeconds(3),
                        LineItemChangeSignalType.ASSOCIATION_CHANGED,
                        null, null, "1001", AssociationAction.REMOVED, "20")));
        processAll();

        DealAuditView result = read(fixture, "1001", 20, null, 20, null);
        DealAuditView.LineItemSummary item = result.lineItems().items().getFirst();

        assertThat(item.deleted()).isTrue();
        assertThat(item.currentMembership()).isEqualTo(DealAuditView.Membership.NOT_APPLICABLE);
        assertThat(item.membershipAtDeletion()).isEqualTo(DealAuditView.Membership.PRESENT);
        assertThat(item.historyCoverage().mode())
                .isEqualTo(LineItemHistoryCoverage.Mode.SIGNAL_FIRST);
        assertThat(item.historyCoverage().observedFrom()).isEqualTo(firstEvidence);
        assertThat(item.historyCoverage().hasUnknownState()).isTrue();
        assertThat(result.events().items())
                .extracting(DealAuditView.AuditEvent::occurredAt)
                .allMatch(occurredAt -> !occurredAt.isBefore(firstEvidence));
        assertThat(result.events().items())
                .extracting(DealAuditView.AuditEvent::type)
                .doesNotContain(com.udmconsulting.modules.lineitemwatch.domain.LineItemAuditType.DEAL_DISASSOCIATED);
    }

    @Test
    void independentKeysetsAreStableForSameTimestampEventsAndLineItems() {
        Fixture fixture = fixture("keysets");
        Instant time = Instant.parse("2026-09-28T12:00:00Z");
        snapshotStore.establish(fixture.tenant().id(), fixture.connection().id(), List.of(
                observation("line-1", "One", time.minusSeconds(10), time.minusSeconds(5), "1001"),
                observation("line-2", "Two", time.minusSeconds(10), time.minusSeconds(5), "1001")));
        signalStore.capture(fixture.tenant().id(), fixture.connection().id(), List.of(
                signal(fixture, "same-name", "line-1", time,
                        LineItemChangeSignalType.PROPERTY_CHANGED,
                        MonitoredLineItemProperty.NAME, "Changed", null, null, null),
                signal(fixture, "same-quantity", "line-1", time,
                        LineItemChangeSignalType.PROPERTY_CHANGED,
                        MonitoredLineItemProperty.QUANTITY, "3", null, null, null),
                signal(fixture, "same-association", "line-1", time,
                        LineItemChangeSignalType.ASSOCIATION_CHANGED,
                        null, null, "1001", AssociationAction.ADDED, "20")));
        processAll();

        DealAuditView firstItems = read(fixture, "1001", 1, null, 0, null);
        assertThat(firstItems.lineItems().hasMore()).isTrue();
        ProviderObjectId firstId = firstItems.lineItems().items().getFirst().lineItemId();
        DealAuditView secondItems = read(
                fixture, "1001", 1, new DealAuditQuery.LineItemCursor(firstId), 0, null);
        assertThat(secondItems.lineItems().items()).extracting(item -> item.lineItemId().value())
                .containsExactly("line-2");
        assertThat(secondItems.lineItems().hasMore()).isFalse();

        List<String> eventIds = new ArrayList<>();
        DealAuditQuery.EventCursor cursor = null;
        do {
            DealAuditView page = read(fixture, "1001", 0, null, 1, cursor);
            if (page.events().items().isEmpty()) {
                break;
            }
            DealAuditView.AuditEvent event = page.events().items().getFirst();
            eventIds.add(Base64.getEncoder().encodeToString(event.semanticKey()));
            cursor = new DealAuditQuery.EventCursor(event.occurredAt(), event.semanticKey());
            if (!page.events().hasMore()) {
                break;
            }
        } while (true);

        assertThat(eventIds).hasSize(2).doesNotHaveDuplicates();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM line_item_watch_audit_event_deal_context
                WHERE tenant_id = ? AND connection_id = ? AND external_deal_id = '1001'
                  AND occurred_at = ?
                """, Long.class,
                fixture.tenant().id().value(), fixture.connection().id().value(), Timestamp.from(time)))
                .isEqualTo(2L);
    }

    @Test
    void identicalDealIdsRemainIsolatedByAuthenticatedOwnerScope() {
        Fixture first = fixture("account-one");
        Fixture second = fixture("account-two");
        Instant time = Instant.parse("2026-09-28T13:00:00Z");
        snapshotStore.establish(first.tenant().id(), first.connection().id(), List.of(
                observation("same-line", "First", time, time, "1001")));
        snapshotStore.establish(second.tenant().id(), second.connection().id(), List.of(
                observation("same-line", "Second", time, time, "1001"),
                observation("second-only-line", "Exclusive", time, time, "2002")));

        DealAuditView.LineItemSummary firstItem =
                read(first, "1001", 20, null, 20, null).lineItems().items().getFirst();
        DealAuditView.LineItemSummary secondItem =
                read(second, "1001", 20, null, 20, null).lineItems().items().getFirst();
        assertThat(firstItem.lineItemId().value()).isEqualTo("same-line");
        assertThat(secondItem.lineItemId().value()).isEqualTo("same-line");
        assertThat(firstItem.latest().get(MonitoredLineItemProperty.NAME).value()).isEqualTo("First");
        assertThat(secondItem.latest().get(MonitoredLineItemProperty.NAME).value()).isEqualTo("Second");
        assertThat(read(first, "2002", 20, null, 20, null).lineItems().items()).isEmpty();
        assertThat(read(first, "9999", 20, null, 20, null).lineItems().items()).isEmpty();
    }

    @Test
    void latestNameSearchIsCaseInsensitiveLiteralAndUsesStableKeysets() {
        Fixture fixture = fixture("name-search");
        Instant time = Instant.parse("2026-09-28T13:00:00Z");
        snapshotStore.establish(fixture.tenant().id(), fixture.connection().id(), List.of(
                observation("1001", "First", time, time, "9001"),
                observation("1002", "Alpha 50%_\\ Support", time, time, "9001"),
                observation("1003", "Árvíztűrő", time, time, "9001"),
                observation("1004", "Last alpha 50%_\\ support", time, time, "9001")));

        DealAuditQuery.LineItemFilter literal =
                new DealAuditQuery.LineItemFilter("ALPHA 50%_\\ SUPPORT");
        DealAuditView first = readFiltered(
                fixture, "9001", 1, literal, null, 0,
                DealAuditQuery.EventFilter.none(), null);
        assertThat(first.lineItems().items())
                .extracting(item -> item.lineItemId().value())
                .containsExactly("1002");
        assertThat(first.lineItems().hasMore()).isTrue();
        DealAuditView second = readFiltered(
                fixture,
                "9001",
                1,
                literal,
                new DealAuditQuery.LineItemCursor(first.lineItems().items().getFirst().lineItemId()),
                0,
                DealAuditQuery.EventFilter.none(),
                null);
        assertThat(second.lineItems().items())
                .extracting(item -> item.lineItemId().value())
                .containsExactly("1004");
        assertThat(second.lineItems().hasMore()).isFalse();

        DealAuditView accentSensitive = readFiltered(
                fixture,
                "9001",
                20,
                new DealAuditQuery.LineItemFilter("arviz"),
                null,
                0,
                DealAuditQuery.EventFilter.none(),
                null);
        assertThat(accentSensitive.lineItems().items()).isEmpty();
    }

    @Test
    void eventFiltersAreConjunctiveDateBoundedAndReturnLatestRetainedIdentity() {
        Fixture fixture = fixture("event-filters");
        Instant start = Instant.parse("2026-09-28T10:00:00Z");
        snapshotStore.establish(fixture.tenant().id(), fixture.connection().id(), List.of(
                observation("2001", "Latest retained name", start.minusSeconds(60), start, "9001"),
                observation("2002", "Other item", start.minusSeconds(60), start, "9001")));
        signalStore.capture(fixture.tenant().id(), fixture.connection().id(), List.of(
                signal(fixture, "quantity-one", "2001", start,
                        LineItemChangeSignalType.PROPERTY_CHANGED,
                        MonitoredLineItemProperty.QUANTITY, "2", null, null, null),
                signal(fixture, "price-one", "2001", start.plusSeconds(1),
                        LineItemChangeSignalType.PROPERTY_CHANGED,
                        MonitoredLineItemProperty.PRICE, "10", null, null, null),
                signal(fixture, "quantity-two", "2002", start,
                        LineItemChangeSignalType.PROPERTY_CHANGED,
                        MonitoredLineItemProperty.QUANTITY, "3", null, null, null)));
        processAll();

        DealAuditQuery.EventFilter filter = new DealAuditQuery.EventFilter(
                LineItemAuditType.PROPERTY_CHANGED,
                MonitoredLineItemProperty.QUANTITY,
                new ProviderObjectId("2001"),
                start,
                start.plusSeconds(1));
        DealAuditView result = readFiltered(
                fixture,
                "9001",
                0,
                DealAuditQuery.LineItemFilter.none(),
                null,
                20,
                filter,
                null);

        assertThat(result.events().items()).hasSize(1);
        DealAuditView.AuditEvent event = result.events().items().getFirst();
        assertThat(event.lineItemId().value()).isEqualTo("2001");
        assertThat(event.property()).isEqualTo(MonitoredLineItemProperty.QUANTITY);
        assertThat(event.occurredAt()).isEqualTo(start);
        assertThat(event.latestRetainedLineItemName().value())
                .isEqualTo("Latest retained name");

        DealAuditQuery.EventFilter noMatch = new DealAuditQuery.EventFilter(
                null, null, new ProviderObjectId("9999"), null, null);
        assertThat(readFiltered(
                fixture,
                "9001",
                0,
                DealAuditQuery.LineItemFilter.none(),
                null,
                20,
                noMatch,
                null).events().items()).isEmpty();
    }

    private DealAuditView read(
            Fixture fixture,
            String dealId,
            int lineItemsLimit,
            DealAuditQuery.LineItemCursor lineItemsCursor,
            int eventsLimit,
            DealAuditQuery.EventCursor eventsCursor) {
        return readDealAudit.read(new DealAuditQuery(
                fixture.tenant().id(),
                fixture.connection().id(),
                new ProviderObjectId(dealId),
                lineItemsLimit,
                lineItemsCursor,
                eventsLimit,
                eventsCursor));
    }

    private DealAuditView readFiltered(
            Fixture fixture,
            String dealId,
            int lineItemsLimit,
            DealAuditQuery.LineItemFilter lineItemFilter,
            DealAuditQuery.LineItemCursor lineItemsCursor,
            int eventsLimit,
            DealAuditQuery.EventFilter eventFilter,
            DealAuditQuery.EventCursor eventsCursor) {
        return readDealAudit.read(new DealAuditQuery(
                fixture.tenant().id(),
                fixture.connection().id(),
                new ProviderObjectId(dealId),
                lineItemsLimit,
                lineItemFilter,
                lineItemsCursor,
                eventsLimit,
                eventFilter,
                eventsCursor));
    }

    private void processAll() {
        Instant now = Instant.now().plus(Duration.ofDays(1));
        int count = 0;
        while (true) {
            var claim = processingStore.claimNext(now, Duration.ofMinutes(2), 8);
            if (claim.isEmpty()) {
                return;
            }
            processingStore.process(claim.orElseThrow(), now);
            if (++count > 100) {
                throw new IllegalStateException("signal processing did not converge");
            }
        }
    }

    private Fixture fixture(String externalAccountId) {
        Tenant tenant = tenantService.create();
        PlatformConnection connection = connectionService.register(
                tenant.id(), Provider.HUBSPOT, new ExternalAccountId(externalAccountId));
        jdbcTemplate.update("""
                UPDATE platform_connection
                SET status = 'ACTIVE', status_changed_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, connection.id().value());
        entitlementService.enable(tenant.id(), ProductModule.LINE_ITEM_WATCH);
        return new Fixture(tenant, connection);
    }

    private static LineItemObservation observation(
            String lineItemId,
            String name,
            Instant providerUpdatedAt,
            Instant observedAt,
            String dealId) {
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
                Set.of(new ProviderObjectId(dealId)));
    }

    private static LineItemChangeSignal signal(
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
        String identity = fixture.connection().id() + ":" + key;
        return new LineItemChangeSignal(
                UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)),
                fixture.tenant().id(),
                fixture.connection().id(),
                "event-" + key,
                "subscription-1",
                new ProviderDeduplicationKey(digest(identity)),
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

    private static void insert005BackfillFixture(Connection connection) throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        UUID baselineItemId = UUID.randomUUID();
        UUID deletedBeforeBaselineItemId = UUID.randomUUID();
        UUID signalItemId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Instant baselineAt = Instant.parse("2026-09-28T10:00:00Z");
        Instant signalAt = Instant.parse("2026-09-28T11:00:00Z");
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO tenant (id) VALUES (?);
                INSERT INTO platform_connection (
                    id, tenant_id, provider, external_account_id, status
                ) VALUES (?, ?, 'HUBSPOT', 'upgrade-account', 'ACTIVE');
                INSERT INTO line_item_watch_line_item (
                    id, tenant_id, connection_id, external_line_item_id
                ) VALUES (?, ?, ?, 'baseline-line'), (?, ?, ?, 'signal-line');
                """)) {
            statement.setObject(1, tenantId);
            statement.setObject(2, connectionId);
            statement.setObject(3, tenantId);
            statement.setObject(4, baselineItemId);
            statement.setObject(5, tenantId);
            statement.setObject(6, connectionId);
            statement.setObject(7, signalItemId);
            statement.setObject(8, tenantId);
            statement.setObject(9, connectionId);
            statement.execute();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO line_item_watch_snapshot (
                    tenant_id, connection_id, line_item_id, snapshot_kind,
                    provider_created_at, provider_updated_at, observed_at
                ) VALUES
                    (?, ?, ?, 'BASELINE', ?, ?, ?),
                    (?, ?, ?, 'LATEST', ?, ?, ?),
                    (?, ?, ?, 'LATEST', NULL, ?, ?)
                """)) {
            int index = 1;
            statement.setObject(index++, tenantId);
            statement.setObject(index++, connectionId);
            statement.setObject(index++, baselineItemId);
            statement.setTimestamp(index++, Timestamp.from(baselineAt.minusSeconds(60)));
            statement.setTimestamp(index++, Timestamp.from(baselineAt.minusSeconds(30)));
            statement.setTimestamp(index++, Timestamp.from(baselineAt));
            statement.setObject(index++, tenantId);
            statement.setObject(index++, connectionId);
            statement.setObject(index++, baselineItemId);
            statement.setTimestamp(index++, Timestamp.from(baselineAt.minusSeconds(60)));
            statement.setTimestamp(index++, Timestamp.from(baselineAt.minusSeconds(30)));
            statement.setTimestamp(index++, Timestamp.from(baselineAt.plusSeconds(30)));
            statement.setObject(index++, tenantId);
            statement.setObject(index++, connectionId);
            statement.setObject(index++, signalItemId);
            statement.setTimestamp(index++, Timestamp.from(signalAt));
            statement.setTimestamp(index, Timestamp.from(signalAt.plusSeconds(300)));
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO line_item_watch_line_item (
                    id, tenant_id, connection_id, external_line_item_id
                ) VALUES (?, ?, ?, 'deleted-before-baseline-line');
                INSERT INTO line_item_watch_snapshot (
                    tenant_id, connection_id, line_item_id, snapshot_kind,
                    provider_created_at, provider_updated_at, observed_at
                ) VALUES
                    (?, ?, ?, 'BASELINE', ?, ?, ?),
                    (?, ?, ?, 'LATEST', ?, ?, ?)
                """)) {
            int index = 1;
            statement.setObject(index++, deletedBeforeBaselineItemId);
            statement.setObject(index++, tenantId);
            statement.setObject(index++, connectionId);
            statement.setObject(index++, tenantId);
            statement.setObject(index++, connectionId);
            statement.setObject(index++, deletedBeforeBaselineItemId);
            statement.setTimestamp(index++, Timestamp.from(baselineAt.minusSeconds(120)));
            statement.setTimestamp(index++, Timestamp.from(baselineAt.minusSeconds(30)));
            statement.setTimestamp(index++, Timestamp.from(baselineAt));
            statement.setObject(index++, tenantId);
            statement.setObject(index++, connectionId);
            statement.setObject(index++, deletedBeforeBaselineItemId);
            statement.setTimestamp(index++, Timestamp.from(baselineAt.minusSeconds(120)));
            statement.setTimestamp(index++, Timestamp.from(baselineAt.minusSeconds(30)));
            statement.setTimestamp(index, Timestamp.from(baselineAt.plusSeconds(30)));
            statement.execute();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO line_item_watch_change_signal (
                    id, tenant_id, connection_id, provider_event_id,
                    provider_subscription_id, provider_deduplication_key,
                    external_line_item_id, signal_type, occurred_at, received_at,
                    property_name, property_value
                ) VALUES (?, ?, ?, 'upgrade-event', 'upgrade-subscription', ?,
                          'signal-line', 'PROPERTY_CHANGED', ?, ?, 'name', 'Observed')
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, tenantId);
            statement.setObject(3, connectionId);
            statement.setBytes(4, digest("upgrade-signal"));
            statement.setTimestamp(5, Timestamp.from(signalAt));
            statement.setTimestamp(6, Timestamp.from(signalAt.plusSeconds(1)));
            statement.executeUpdate();
        }
        UUID unprocessedSignalId = UUID.randomUUID();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO line_item_watch_change_signal (
                    id, tenant_id, connection_id, provider_event_id,
                    provider_subscription_id, provider_deduplication_key,
                    external_line_item_id, signal_type, occurred_at, received_at,
                    property_name, property_value
                ) VALUES (?, ?, ?, 'unprocessed-event', 'upgrade-subscription', ?,
                          'signal-line', 'PROPERTY_CHANGED', ?, ?, 'name', 'Unprocessed');
                INSERT INTO line_item_watch_signal_processing (
                    signal_id, tenant_id, connection_id, status
                ) VALUES (?, ?, ?, 'PENDING')
                """)) {
            statement.setObject(1, unprocessedSignalId);
            statement.setObject(2, tenantId);
            statement.setObject(3, connectionId);
            statement.setBytes(4, digest("unprocessed-upgrade-signal"));
            statement.setTimestamp(5, Timestamp.from(signalAt.minusSeconds(3600)));
            statement.setTimestamp(6, Timestamp.from(signalAt.minusSeconds(3599)));
            statement.setObject(7, unprocessedSignalId);
            statement.setObject(8, tenantId);
            statement.setObject(9, connectionId);
            statement.execute();
        }
        UUID deletedSignalId = UUID.randomUUID();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO line_item_watch_change_signal (
                    id, tenant_id, connection_id, provider_event_id,
                    provider_subscription_id, provider_deduplication_key,
                    external_line_item_id, signal_type, occurred_at, received_at
                ) VALUES (?, ?, ?, 'delete-event', 'upgrade-subscription', ?,
                          'deleted-before-baseline-line', 'DELETED', ?, ?);
                INSERT INTO line_item_watch_signal_processing (
                    signal_id, tenant_id, connection_id, status, processed_at
                ) VALUES (?, ?, ?, 'PROCESSED', ?)
                """)) {
            Instant deletedAt = baselineAt.minusSeconds(60);
            statement.setObject(1, deletedSignalId);
            statement.setObject(2, tenantId);
            statement.setObject(3, connectionId);
            statement.setBytes(4, digest("deleted-before-baseline-signal"));
            statement.setTimestamp(5, Timestamp.from(deletedAt));
            statement.setTimestamp(6, Timestamp.from(deletedAt.plusSeconds(1)));
            statement.setObject(7, deletedSignalId);
            statement.setObject(8, tenantId);
            statement.setObject(9, connectionId);
            statement.setTimestamp(10, Timestamp.from(deletedAt.plusSeconds(1)));
            statement.execute();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO line_item_watch_signal_processing (
                    signal_id, tenant_id, connection_id, status, processed_at
                )
                SELECT id, tenant_id, connection_id, 'PROCESSED', received_at
                FROM line_item_watch_change_signal
                WHERE provider_event_id = 'upgrade-event'
                """)) {
            statement.executeUpdate();
        }
        byte[] eventKey = digest("upgrade-event");
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO line_item_watch_audit_event (
                    id, tenant_id, connection_id, line_item_id, semantic_key,
                    event_type, occurred_at, before_state, after_state
                ) VALUES (?, ?, ?, ?, ?, 'CREATED', ?, 'ABSENT', 'PRESENT');
                INSERT INTO line_item_watch_audit_event_deal_context (
                    tenant_id, connection_id, audit_event_id, external_deal_id
                ) VALUES (?, ?, ?, '1001')
                """)) {
            statement.setObject(1, eventId);
            statement.setObject(2, tenantId);
            statement.setObject(3, connectionId);
            statement.setObject(4, baselineItemId);
            statement.setBytes(5, eventKey);
            statement.setTimestamp(6, Timestamp.from(baselineAt.plusSeconds(1)));
            statement.setObject(7, tenantId);
            statement.setObject(8, connectionId);
            statement.setObject(9, eventId);
            statement.execute();
        }
    }

    private record Fixture(Tenant tenant, PlatformConnection connection) {
    }
}
