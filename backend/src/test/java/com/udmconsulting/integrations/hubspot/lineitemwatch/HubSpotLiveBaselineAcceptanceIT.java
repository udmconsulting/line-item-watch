package com.udmconsulting.integrations.hubspot.lineitemwatch;

import static org.assertj.core.api.Assertions.assertThat;

import com.udmconsulting.modules.lineitemwatch.application.DealLineItemObservations;
import com.udmconsulting.modules.lineitemwatch.application.EstablishLineItemBaseline;
import com.udmconsulting.modules.lineitemwatch.application.LineItemBaselineSource;
import com.udmconsulting.modules.lineitemwatch.domain.LineItemObservation;
import com.udmconsulting.modules.lineitemwatch.domain.ProviderObjectId;
import com.udmconsulting.platform.connection.application.PlatformConnectionService;
import com.udmconsulting.platform.connection.domain.ConnectionStatus;
import com.udmconsulting.platform.connection.domain.ExternalAccountId;
import com.udmconsulting.platform.connection.domain.PlatformConnection;
import com.udmconsulting.platform.connection.domain.Provider;
import com.udmconsulting.platform.entitlement.application.EntitlementService;
import com.udmconsulting.platform.module.domain.ProductModule;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class HubSpotLiveBaselineAcceptanceIT {

    private static final String LIVE_CONFIRM_VARIABLE = "HUBSPOT_LIVE_BASELINE_CONFIRM";
    private static final String HUBSPOT_ACCOUNT_ID = "149377304";
    private static final String DEAL_ID = "521984899298";
    private static final String EXPECTED_LINE_ITEM_ID = "486464823492";
    private static final String EXPECTED_CONFIRMATION = HUBSPOT_ACCOUNT_ID + ":" + DEAL_ID;
    private static final String LOCAL_DATABASE_URL =
            "jdbc:postgresql://localhost:5433/line_item_watch_local";

    static {
        if (!EXPECTED_CONFIRMATION.equals(System.getenv(LIVE_CONFIRM_VARIABLE))) {
            throw new IllegalStateException(
                    "HUBSPOT_LIVE_BASELINE_CONFIRM must exactly match the approved account and Deal");
        }
    }

    @Autowired
    private PlatformConnectionService connectionService;

    @Autowired
    private EntitlementService entitlementService;

    @Autowired
    private LineItemBaselineSource baselineSource;

    @Autowired
    private EstablishLineItemBaseline establishBaseline;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Environment environment;

    @Test
    void readsPersistsAndIdempotentlyRerunsTheApprovedDealBaseline() {
        assertLocalRuntime();
        PlatformConnection connection = resolveConnection();
        assertThat(connection.status()).isEqualTo(ConnectionStatus.ACTIVE);
        assertThat(entitlementService.isEnabled(
                connection.tenantId(), ProductModule.LINE_ITEM_WATCH)).isTrue();

        ProviderObjectId dealId = new ProviderObjectId(DEAL_ID);
        DealLineItemObservations expected = baselineSource.readDeal(connection, dealId);
        LineItemObservation expectedLineItem = expected.lineItems().stream()
                .filter(item -> EXPECTED_LINE_ITEM_ID.equals(item.lineItemId().value()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected feasibility Line Item was not associated"));

        EstablishLineItemBaseline.Result first = establishBaseline.execute(
                connection.tenantId(), connection.id(), dealId);
        EstablishLineItemBaseline.Result second = establishBaseline.execute(
                connection.tenantId(), connection.id(), dealId);

        assertThat(first.observedLineItems()).isEqualTo(expected.lineItems().size());
        assertThat(second.observedLineItems()).isEqualTo(expected.lineItems().size());
        assertThat(second.createdLineItems()).isZero();
        assertThat(second.createdBaselines()).isZero();
        assertThat(second.createdLatestSnapshots()).isZero();
        for (LineItemObservation item : expected.lineItems()) {
            assertThat(queryLong("""
                    SELECT COUNT(*)
                    FROM line_item_watch_line_item
                    WHERE tenant_id = ? AND connection_id = ? AND external_line_item_id = ?
                    """, connection.tenantId().value(), connection.id().value(), item.lineItemId().value()))
                    .isEqualTo(1L);
            assertThat(queryLong("""
                    SELECT COUNT(*)
                    FROM line_item_watch_snapshot snapshot
                    JOIN line_item_watch_line_item identity ON identity.id = snapshot.line_item_id
                    WHERE identity.tenant_id = ? AND identity.connection_id = ?
                      AND identity.external_line_item_id = ?
                    """, connection.tenantId().value(), connection.id().value(), item.lineItemId().value()))
                    .isEqualTo(2L);
        }

        PersistedSnapshot latest = loadSnapshot(connection, expectedLineItem.lineItemId(), "LATEST");
        assertThat(latest)
                .usingRecursiveComparison()
                .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .isEqualTo(PersistedSnapshot.from(expectedLineItem));
        assertThat(loadDealIds(connection, expectedLineItem.lineItemId(), "LATEST"))
                .containsExactlyInAnyOrderElementsOf(expectedLineItem.associatedDealIds().stream()
                        .map(ProviderObjectId::value)
                        .toList());
        assertThat(loadDealIds(connection, expectedLineItem.lineItemId(), "BASELINE"))
                .contains(DEAL_ID);
        assertThat(queryLong("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name LIKE 'line_item_watch_%'
                  AND (
                    lower(column_name) LIKE '%token%'
                    OR lower(column_name) LIKE '%secret%'
                    OR lower(column_name) LIKE '%raw%json%'
                  )
                """)).isZero();

        System.out.println("LIVE_BASELINE_ACCEPTANCE_PASS");
        System.out.println("observed line items: " + first.observedLineItems());
        System.out.println("baseline rows: " + expected.lineItems().size());
        System.out.println("latest rows: " + expected.lineItems().size());
        System.out.println("rerun created records: 0");
        System.out.println("tenant and connection provenance: verified");
        System.out.println("known Deal association: verified");
    }

    private void assertLocalRuntime() {
        assertThat(Arrays.asList(environment.getActiveProfiles())).contains("local");
        assertThat(environment.getProperty("spring.datasource.url")).isEqualTo(LOCAL_DATABASE_URL);
    }

    private PlatformConnection resolveConnection() {
        return connectionService.resolve(Provider.HUBSPOT, new ExternalAccountId(HUBSPOT_ACCOUNT_ID))
                .orElseThrow(() -> new AssertionError("Expected HubSpot connection was not found"));
    }

    private PersistedSnapshot loadSnapshot(
            PlatformConnection connection, ProviderObjectId lineItemId, String kind) {
        return jdbcTemplate.queryForObject("""
                SELECT snapshot.name, snapshot.quantity, snapshot.unit_price,
                       snapshot.unit_discount, snapshot.discount_percentage,
                       snapshot.billing_frequency, snapshot.billing_start_date,
                       snapshot.billing_start_delay_unit, snapshot.billing_start_delay_count,
                       snapshot.recurring_billing_period, snapshot.provider_created_at,
                       snapshot.provider_updated_at
                FROM line_item_watch_snapshot snapshot
                JOIN line_item_watch_line_item item ON item.id = snapshot.line_item_id
                WHERE item.tenant_id = ? AND item.connection_id = ?
                  AND item.external_line_item_id = ? AND snapshot.snapshot_kind = ?
                """, (resultSet, rowNumber) -> mapSnapshot(resultSet),
                connection.tenantId().value(),
                connection.id().value(),
                lineItemId.value(),
                kind);
    }

    private List<String> loadDealIds(
            PlatformConnection connection, ProviderObjectId lineItemId, String kind) {
        return jdbcTemplate.queryForList("""
                SELECT association.external_deal_id
                FROM line_item_watch_snapshot_deal association
                JOIN line_item_watch_line_item item ON item.id = association.line_item_id
                WHERE item.tenant_id = ? AND item.connection_id = ?
                  AND item.external_line_item_id = ? AND association.snapshot_kind = ?
                ORDER BY association.external_deal_id
                """, String.class,
                connection.tenantId().value(),
                connection.id().value(),
                lineItemId.value(),
                kind);
    }

    private static PersistedSnapshot mapSnapshot(ResultSet resultSet) throws SQLException {
        return new PersistedSnapshot(
                resultSet.getString("name"),
                resultSet.getBigDecimal("quantity"),
                resultSet.getBigDecimal("unit_price"),
                resultSet.getBigDecimal("unit_discount"),
                resultSet.getBigDecimal("discount_percentage"),
                resultSet.getString("billing_frequency"),
                resultSet.getDate("billing_start_date") == null
                        ? null : resultSet.getDate("billing_start_date").toLocalDate(),
                resultSet.getString("billing_start_delay_unit"),
                resultSet.getObject("billing_start_delay_count", Integer.class),
                resultSet.getString("recurring_billing_period"),
                resultSet.getTimestamp("provider_created_at").toInstant(),
                resultSet.getTimestamp("provider_updated_at").toInstant());
    }

    private long queryLong(String sql, Object... arguments) {
        return Objects.requireNonNull(jdbcTemplate.queryForObject(sql, Long.class, arguments));
    }

    private record PersistedSnapshot(
            String name,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal unitDiscount,
            BigDecimal discountPercentage,
            String billingFrequency,
            java.time.LocalDate billingStartDate,
            String billingStartDelayUnit,
            Integer billingStartDelayCount,
            String recurringBillingPeriod,
            java.time.Instant providerCreatedAt,
            java.time.Instant providerUpdatedAt) {

        private static PersistedSnapshot from(LineItemObservation observation) {
            return new PersistedSnapshot(
                    observation.name(),
                    observation.quantity(),
                    observation.unitPrice(),
                    observation.unitDiscount(),
                    observation.discountPercentage(),
                    observation.billingFrequency(),
                    observation.billingStart().date(),
                    observation.billingStart().delayUnit() == null
                            ? null : observation.billingStart().delayUnit().name(),
                    observation.billingStart().delayCount(),
                    observation.recurringPeriod() == null
                            ? null : observation.recurringPeriod().canonicalValue(),
                    observation.providerCreatedAt(),
                    observation.providerUpdatedAt());
        }
    }
}
