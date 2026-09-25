package com.udmconsulting.modules.lineitemwatch.infrastructure.persistence;

import com.udmconsulting.modules.lineitemwatch.application.BaselineSyncException;
import com.udmconsulting.modules.lineitemwatch.application.LineItemSnapshotStore;
import com.udmconsulting.modules.lineitemwatch.domain.BillingStart;
import com.udmconsulting.modules.lineitemwatch.domain.LineItemObservation;
import com.udmconsulting.modules.lineitemwatch.domain.ProviderObjectId;
import com.udmconsulting.modules.lineitemwatch.domain.SnapshotKind;
import com.udmconsulting.platform.connection.application.PlatformConnectionService;
import com.udmconsulting.platform.connection.domain.ConnectionStatus;
import com.udmconsulting.platform.connection.domain.PlatformConnection;
import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.connection.domain.Provider;
import com.udmconsulting.platform.entitlement.application.EntitlementService;
import com.udmconsulting.platform.module.domain.ProductModule;
import com.udmconsulting.platform.tenant.domain.TenantId;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcLineItemSnapshotStore implements LineItemSnapshotStore {

    private static final String INSERT_LINE_ITEM = """
            INSERT INTO line_item_watch_line_item
                (id, tenant_id, connection_id, external_line_item_id)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (tenant_id, connection_id, external_line_item_id) DO NOTHING
            """;

    private static final String SELECT_LINE_ITEM_ID = """
            SELECT id
            FROM line_item_watch_line_item
            WHERE tenant_id = ? AND connection_id = ? AND external_line_item_id = ?
            """;

    private static final String INSERT_SNAPSHOT = """
            INSERT INTO line_item_watch_snapshot (
                tenant_id, connection_id, line_item_id, snapshot_kind, name,
                quantity, unit_price, unit_discount, discount_percentage,
                billing_frequency, billing_start_date, billing_start_delay_unit,
                billing_start_delay_count, recurring_billing_period,
                provider_created_at, provider_updated_at, observed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (line_item_id, snapshot_kind) DO NOTHING
            """;

    private static final String UPDATE_LATEST = """
            UPDATE line_item_watch_snapshot
            SET name = ?, quantity = ?, unit_price = ?, unit_discount = ?,
                discount_percentage = ?, billing_frequency = ?, billing_start_date = ?,
                billing_start_delay_unit = ?, billing_start_delay_count = ?,
                recurring_billing_period = ?, provider_created_at = ?,
                provider_updated_at = ?, observed_at = ?, persisted_at = CURRENT_TIMESTAMP
            WHERE line_item_id = ? AND snapshot_kind = 'LATEST'
              AND (
                  provider_updated_at < ?
                  OR (provider_updated_at = ? AND observed_at < ?)
              )
            """;

    private final JdbcTemplate jdbcTemplate;
    private final PlatformConnectionService connectionService;
    private final EntitlementService entitlementService;

    public JdbcLineItemSnapshotStore(
            JdbcTemplate jdbcTemplate,
            PlatformConnectionService connectionService,
            EntitlementService entitlementService) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
        this.connectionService = Objects.requireNonNull(connectionService);
        this.entitlementService = Objects.requireNonNull(entitlementService);
    }

    @Override
    @Transactional
    public PersistenceResult establish(
            TenantId tenantId,
            PlatformConnectionId connectionId,
            List<LineItemObservation> observations) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(connectionId, "connectionId must not be null");
        Objects.requireNonNull(observations, "observations must not be null");

        PlatformConnection connection = connectionService.lockForCommit(tenantId, connectionId)
                .orElseThrow(() -> new BaselineSyncException(
                        "Platform Connection was not found for Tenant", false));
        if (connection.provider() != Provider.HUBSPOT) {
            throw new BaselineSyncException("Platform Connection provider is not supported", false);
        }
        if (connection.status() != ConnectionStatus.ACTIVE) {
            throw new BaselineSyncException("Platform Connection is not active", false);
        }
        if (!entitlementService.lockEnabledForCommit(
                tenantId, ProductModule.LINE_ITEM_WATCH)) {
            throw new BaselineSyncException(
                    "Tenant is not entitled to LINE_ITEM_WATCH", false);
        }

        int createdLineItems = 0;
        int createdBaselines = 0;
        int createdLatest = 0;
        int updatedLatest = 0;
        int unchangedLatest = 0;

        for (LineItemObservation observation : observations) {
            UUID proposedId = UUID.randomUUID();
            int lineItemInserted = jdbcTemplate.update(
                    INSERT_LINE_ITEM,
                    proposedId,
                    tenantId.value(),
                    connectionId.value(),
                    observation.lineItemId().value());
            createdLineItems += lineItemInserted;
            UUID lineItemId = lineItemInserted == 1
                    ? proposedId
                    : jdbcTemplate.queryForObject(
                            SELECT_LINE_ITEM_ID,
                            UUID.class,
                            tenantId.value(),
                            connectionId.value(),
                            observation.lineItemId().value());

            int baselineInserted = insertSnapshot(
                    tenantId, connectionId, lineItemId, SnapshotKind.BASELINE, observation);
            if (baselineInserted == 1) {
                replaceAssociations(
                        tenantId, connectionId, lineItemId, SnapshotKind.BASELINE, observation);
                createdBaselines++;
            }

            int latestInserted = insertSnapshot(
                    tenantId, connectionId, lineItemId, SnapshotKind.LATEST, observation);
            if (latestInserted == 1) {
                replaceAssociations(
                        tenantId, connectionId, lineItemId, SnapshotKind.LATEST, observation);
                createdLatest++;
                continue;
            }

            int latestUpdated = updateLatest(lineItemId, observation);
            if (latestUpdated == 1) {
                replaceAssociations(
                        tenantId, connectionId, lineItemId, SnapshotKind.LATEST, observation);
                updatedLatest++;
            } else {
                unchangedLatest++;
            }
        }

        return new PersistenceResult(
                createdLineItems,
                createdBaselines,
                createdLatest,
                updatedLatest,
                unchangedLatest);
    }

    private int insertSnapshot(
            TenantId tenantId,
            PlatformConnectionId connectionId,
            UUID lineItemId,
            SnapshotKind kind,
            LineItemObservation observation) {
        BillingStart start = observation.billingStart();
        return jdbcTemplate.update(
                INSERT_SNAPSHOT,
                tenantId.value(),
                connectionId.value(),
                lineItemId,
                kind.name(),
                observation.name(),
                observation.quantity(),
                observation.unitPrice(),
                observation.unitDiscount(),
                observation.discountPercentage(),
                observation.billingFrequency(),
                sqlDate(start),
                start.delayUnit() == null ? null : start.delayUnit().name(),
                start.delayCount(),
                observation.recurringPeriod() == null
                        ? null : observation.recurringPeriod().canonicalValue(),
                Timestamp.from(observation.providerCreatedAt()),
                Timestamp.from(observation.providerUpdatedAt()),
                Timestamp.from(observation.observedAt()));
    }

    private int updateLatest(UUID lineItemId, LineItemObservation observation) {
        BillingStart start = observation.billingStart();
        Timestamp providerUpdatedAt = Timestamp.from(observation.providerUpdatedAt());
        return jdbcTemplate.update(
                UPDATE_LATEST,
                observation.name(),
                observation.quantity(),
                observation.unitPrice(),
                observation.unitDiscount(),
                observation.discountPercentage(),
                observation.billingFrequency(),
                sqlDate(start),
                start.delayUnit() == null ? null : start.delayUnit().name(),
                start.delayCount(),
                observation.recurringPeriod() == null
                        ? null : observation.recurringPeriod().canonicalValue(),
                Timestamp.from(observation.providerCreatedAt()),
                providerUpdatedAt,
                Timestamp.from(observation.observedAt()),
                lineItemId,
                providerUpdatedAt,
                providerUpdatedAt,
                Timestamp.from(observation.observedAt()));
    }

    private void replaceAssociations(
            TenantId tenantId,
            PlatformConnectionId connectionId,
            UUID lineItemId,
            SnapshotKind kind,
            LineItemObservation observation) {
        jdbcTemplate.update(
                "DELETE FROM line_item_watch_snapshot_deal WHERE line_item_id = ? AND snapshot_kind = ?",
                lineItemId,
                kind.name());
        for (ProviderObjectId dealId : observation.associatedDealIds()) {
            jdbcTemplate.update("""
                    INSERT INTO line_item_watch_snapshot_deal (
                        tenant_id, connection_id, line_item_id, snapshot_kind, external_deal_id
                    ) VALUES (?, ?, ?, ?, ?)
                    """,
                    tenantId.value(),
                    connectionId.value(),
                    lineItemId,
                    kind.name(),
                    dealId.value());
        }
    }

    private static Date sqlDate(BillingStart start) {
        return start.date() == null ? null : Date.valueOf(start.date());
    }
}
