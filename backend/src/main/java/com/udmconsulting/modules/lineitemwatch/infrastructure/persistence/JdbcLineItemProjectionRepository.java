package com.udmconsulting.modules.lineitemwatch.infrastructure.persistence;

import com.udmconsulting.modules.lineitemwatch.domain.AssociationAction;
import com.udmconsulting.modules.lineitemwatch.domain.BillingStart;
import com.udmconsulting.modules.lineitemwatch.domain.LineItemAuditEvent;
import com.udmconsulting.modules.lineitemwatch.domain.LineItemChangeSignal;
import com.udmconsulting.modules.lineitemwatch.domain.LineItemChangeSignalType;
import com.udmconsulting.modules.lineitemwatch.domain.LineItemObservation;
import com.udmconsulting.modules.lineitemwatch.domain.LineItemProjection;
import com.udmconsulting.modules.lineitemwatch.domain.LineItemProjectionCheckpoint;
import com.udmconsulting.modules.lineitemwatch.domain.LineItemProjectionReconstructor;
import com.udmconsulting.modules.lineitemwatch.domain.LineItemPropertyValues;
import com.udmconsulting.modules.lineitemwatch.domain.MonitoredLineItemProperty;
import com.udmconsulting.modules.lineitemwatch.domain.ObservedValue;
import com.udmconsulting.modules.lineitemwatch.domain.ProviderDeduplicationKey;
import com.udmconsulting.modules.lineitemwatch.domain.ProviderObjectId;
import com.udmconsulting.modules.lineitemwatch.domain.RecurringPeriod;
import com.udmconsulting.modules.lineitemwatch.domain.SnapshotKind;
import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.tenant.domain.TenantId;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

@Component
final class JdbcLineItemProjectionRepository {

    private static final String INSERT_LINE_ITEM = """
            INSERT INTO line_item_watch_line_item
                (id, tenant_id, connection_id, external_line_item_id)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (tenant_id, connection_id, external_line_item_id) DO NOTHING
            """;

    private static final String UPSERT_LATEST = """
            INSERT INTO line_item_watch_snapshot (
                tenant_id, connection_id, line_item_id, snapshot_kind, name,
                quantity, unit_price, unit_discount, discount_percentage,
                billing_frequency, billing_start_date, billing_start_delay_unit,
                billing_start_delay_count, recurring_billing_period,
                provider_created_at, provider_updated_at, observed_at,
                known_properties, deal_set_complete, deleted_at,
                history_coverage_mode, history_observed_from
            ) VALUES (?, ?, ?, 'LATEST', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::text[], ?, ?, ?, ?)
            ON CONFLICT (line_item_id, snapshot_kind) DO UPDATE SET
                name = EXCLUDED.name,
                quantity = EXCLUDED.quantity,
                unit_price = EXCLUDED.unit_price,
                unit_discount = EXCLUDED.unit_discount,
                discount_percentage = EXCLUDED.discount_percentage,
                billing_frequency = EXCLUDED.billing_frequency,
                billing_start_date = EXCLUDED.billing_start_date,
                billing_start_delay_unit = EXCLUDED.billing_start_delay_unit,
                billing_start_delay_count = EXCLUDED.billing_start_delay_count,
                recurring_billing_period = EXCLUDED.recurring_billing_period,
                provider_created_at = EXCLUDED.provider_created_at,
                provider_updated_at = EXCLUDED.provider_updated_at,
                observed_at = EXCLUDED.observed_at,
                persisted_at = CURRENT_TIMESTAMP,
                known_properties = EXCLUDED.known_properties,
                deal_set_complete = EXCLUDED.deal_set_complete,
                deleted_at = EXCLUDED.deleted_at,
                history_coverage_mode = EXCLUDED.history_coverage_mode,
                history_observed_from = EXCLUDED.history_observed_from
            """;

    private static final String UPSERT_AUDIT = """
            INSERT INTO line_item_watch_audit_event (
                id, tenant_id, connection_id, line_item_id, semantic_key,
                event_type, occurred_at, property_name, before_state,
                before_value, after_state, after_value, external_deal_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, connection_id, semantic_key) DO UPDATE SET
                line_item_id = EXCLUDED.line_item_id,
                event_type = EXCLUDED.event_type,
                occurred_at = EXCLUDED.occurred_at,
                property_name = EXCLUDED.property_name,
                before_state = EXCLUDED.before_state,
                before_value = EXCLUDED.before_value,
                after_state = EXCLUDED.after_state,
                after_value = EXCLUDED.after_value,
                external_deal_id = EXCLUDED.external_deal_id,
                projected_at = CURRENT_TIMESTAMP
            RETURNING id
            """;

    private final JdbcTemplate jdbcTemplate;
    private final LineItemProjectionReconstructor reconstructor =
            new LineItemProjectionReconstructor();

    JdbcLineItemProjectionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
    }

    LockedLineItem ensureAndLock(
            TenantId tenantId,
            PlatformConnectionId connectionId,
            ProviderObjectId externalLineItemId) {
        UUID proposed = UUID.randomUUID();
        int created = jdbcTemplate.update(
                INSERT_LINE_ITEM,
                proposed,
                tenantId.value(),
                connectionId.value(),
                externalLineItemId.value());
        return jdbcTemplate.queryForObject("""
                SELECT id, external_line_item_id
                FROM line_item_watch_line_item
                WHERE tenant_id = ? AND connection_id = ? AND external_line_item_id = ?
                FOR UPDATE
                """,
                (row, ignored) -> new LockedLineItem(
                        row.getObject("id", UUID.class),
                        new ProviderObjectId(row.getString("external_line_item_id")),
                        created == 1),
                tenantId.value(),
                connectionId.value(),
                externalLineItemId.value());
    }

    int upsertCompleteCheckpoint(
            TenantId tenantId,
            PlatformConnectionId connectionId,
            LockedLineItem lineItem,
            SnapshotKind kind,
            LineItemObservation observation) {
        if (kind != SnapshotKind.BASELINE && kind != SnapshotKind.OBSERVED) {
            throw new IllegalArgumentException("complete checkpoint kind is invalid");
        }
        String conflict = kind == SnapshotKind.BASELINE
                ? "DO NOTHING"
                : "DO UPDATE SET "
                    + "name=EXCLUDED.name, quantity=EXCLUDED.quantity, unit_price=EXCLUDED.unit_price, "
                    + "unit_discount=EXCLUDED.unit_discount, discount_percentage=EXCLUDED.discount_percentage, "
                    + "billing_frequency=EXCLUDED.billing_frequency, "
                    + "billing_start_date=EXCLUDED.billing_start_date, "
                    + "billing_start_delay_unit=EXCLUDED.billing_start_delay_unit, "
                    + "billing_start_delay_count=EXCLUDED.billing_start_delay_count, "
                    + "recurring_billing_period=EXCLUDED.recurring_billing_period, "
                    + "provider_created_at=EXCLUDED.provider_created_at, "
                    + "provider_updated_at=EXCLUDED.provider_updated_at, "
                    + "observed_at=EXCLUDED.observed_at, persisted_at=CURRENT_TIMESTAMP, "
                    + "known_properties=EXCLUDED.known_properties, deal_set_complete=TRUE, deleted_at=NULL "
                    + "WHERE line_item_watch_snapshot.provider_updated_at < EXCLUDED.provider_updated_at "
                    + "OR (line_item_watch_snapshot.provider_updated_at = EXCLUDED.provider_updated_at "
                    + "AND line_item_watch_snapshot.observed_at < EXCLUDED.observed_at)";
        BillingStart start = observation.billingStart();
        int changed = jdbcTemplate.update("""
                INSERT INTO line_item_watch_snapshot (
                    tenant_id, connection_id, line_item_id, snapshot_kind, name,
                    quantity, unit_price, unit_discount, discount_percentage,
                    billing_frequency, billing_start_date, billing_start_delay_unit,
                    billing_start_delay_count, recurring_billing_period,
                    provider_created_at, provider_updated_at, observed_at,
                    known_properties, deal_set_complete, deleted_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::text[], TRUE, NULL)
                ON CONFLICT (line_item_id, snapshot_kind) %s
                """.formatted(conflict),
                tenantId.value(),
                connectionId.value(),
                lineItem.id(),
                kind.name(),
                observation.name(),
                observation.quantity(),
                observation.unitPrice(),
                observation.unitDiscount(),
                observation.discountPercentage(),
                observation.billingFrequency(),
                sqlDate(start.date()),
                start.delayUnit() == null ? null : start.delayUnit().name(),
                start.delayCount(),
                observation.recurringPeriod() == null
                        ? null : observation.recurringPeriod().canonicalValue(),
                Timestamp.from(observation.providerCreatedAt()),
                Timestamp.from(observation.providerUpdatedAt()),
                Timestamp.from(observation.observedAt()),
                allPropertiesArrayLiteral());
        if (changed == 1) {
            replaceSnapshotDeals(
                    tenantId, connectionId, lineItem.id(), kind, observation.associatedDealIds());
        }
        return changed;
    }

    LineItemProjection rebuildLocked(
            TenantId tenantId,
            PlatformConnectionId connectionId,
            LockedLineItem lineItem,
            UUID currentSignalId) {
        List<LineItemProjectionCheckpoint> checkpoints = loadCheckpoints(lineItem.id());
        List<LineItemChangeSignal> signals = loadProjectionSignals(
                tenantId, connectionId, lineItem.externalId(), currentSignalId);
        LineItemProjection projection = reconstructor.reconstruct(
                lineItem.externalId(), checkpoints, signals);
        writeLatest(tenantId, connectionId, lineItem.id(), projection);
        writeAuditProjection(tenantId, connectionId, lineItem.id(), projection.auditEvents());
        return projection;
    }

    private List<LineItemProjectionCheckpoint> loadCheckpoints(UUID lineItemId) {
        return jdbcTemplate.query("""
                SELECT *
                FROM line_item_watch_snapshot
                WHERE line_item_id = ? AND snapshot_kind IN ('BASELINE', 'OBSERVED')
                ORDER BY observed_at, snapshot_kind
                """, (row, ignored) -> checkpoint(row), lineItemId);
    }

    private LineItemProjectionCheckpoint checkpoint(ResultSet row) throws SQLException {
        SnapshotKind kind = SnapshotKind.valueOf(row.getString("snapshot_kind"));
        UUID lineItemId = row.getObject("line_item_id", UUID.class);
        Set<ProviderObjectId> deals = new LinkedHashSet<>(jdbcTemplate.queryForList("""
                SELECT external_deal_id
                FROM line_item_watch_snapshot_deal
                WHERE line_item_id = ? AND snapshot_kind = ?
                ORDER BY external_deal_id
                """, String.class, lineItemId, kind.name()).stream().map(ProviderObjectId::new).toList());
        return new LineItemProjectionCheckpoint(
                kind,
                row.getTimestamp("provider_created_at").toInstant(),
                row.getTimestamp("provider_updated_at").toInstant(),
                row.getTimestamp("observed_at").toInstant(),
                checkpointProperties(row),
                deals);
    }

    private static Map<MonitoredLineItemProperty, ObservedValue> checkpointProperties(
            ResultSet row) throws SQLException {
        EnumMap<MonitoredLineItemProperty, ObservedValue> values =
                new EnumMap<>(MonitoredLineItemProperty.class);
        values.put(MonitoredLineItemProperty.NAME, value(row.getString("name")));
        values.put(MonitoredLineItemProperty.QUANTITY, decimalValue(row.getBigDecimal("quantity")));
        values.put(MonitoredLineItemProperty.PRICE, decimalValue(row.getBigDecimal("unit_price")));
        values.put(MonitoredLineItemProperty.DISCOUNT, decimalValue(row.getBigDecimal("unit_discount")));
        values.put(
                MonitoredLineItemProperty.DISCOUNT_PERCENTAGE,
                decimalValue(row.getBigDecimal("discount_percentage")));
        values.put(
                MonitoredLineItemProperty.BILLING_FREQUENCY,
                value(row.getString("billing_frequency")));
        Date date = row.getDate("billing_start_date");
        values.put(
                MonitoredLineItemProperty.BILLING_START_DATE,
                date == null ? ObservedValue.absent() : ObservedValue.value(date.toLocalDate().toString()));
        String delayUnit = row.getString("billing_start_delay_unit");
        Integer delayCount = (Integer) row.getObject("billing_start_delay_count");
        values.put(
                MonitoredLineItemProperty.BILLING_START_DELAY_DAYS,
                "DAYS".equals(delayUnit)
                        ? ObservedValue.value(Integer.toString(delayCount)) : ObservedValue.absent());
        values.put(
                MonitoredLineItemProperty.BILLING_START_DELAY_MONTHS,
                "MONTHS".equals(delayUnit)
                        ? ObservedValue.value(Integer.toString(delayCount)) : ObservedValue.absent());
        values.put(
                MonitoredLineItemProperty.RECURRING_BILLING_PERIOD,
                value(row.getString("recurring_billing_period")));
        return values;
    }

    private List<LineItemChangeSignal> loadProjectionSignals(
            TenantId tenantId,
            PlatformConnectionId connectionId,
            ProviderObjectId lineItemId,
            UUID currentSignalId) {
        return jdbcTemplate.query("""
                SELECT s.*
                FROM line_item_watch_change_signal s
                JOIN line_item_watch_signal_processing p ON p.signal_id = s.id
                WHERE s.tenant_id = ?
                  AND s.connection_id = ?
                  AND s.external_line_item_id = ?
                  AND (p.status = 'PROCESSED' OR s.id = ?)
                ORDER BY s.occurred_at, s.provider_deduplication_key
                """, SIGNAL_MAPPER,
                tenantId.value(), connectionId.value(), lineItemId.value(), currentSignalId);
    }

    private void writeLatest(
            TenantId tenantId,
            PlatformConnectionId connectionId,
            UUID lineItemId,
            LineItemProjection projection) {
        Map<MonitoredLineItemProperty, ObservedValue> properties = projection.properties();
        ObservedValue date = properties.get(MonitoredLineItemProperty.BILLING_START_DATE);
        ObservedValue days = properties.get(MonitoredLineItemProperty.BILLING_START_DELAY_DAYS);
        ObservedValue months = properties.get(MonitoredLineItemProperty.BILLING_START_DELAY_MONTHS);
        String delayUnit = days.state() == ObservedValue.State.VALUE ? "DAYS"
                : months.state() == ObservedValue.State.VALUE ? "MONTHS" : null;
        Integer delayCount = days.state() == ObservedValue.State.VALUE
                ? Integer.valueOf(days.value())
                : months.state() == ObservedValue.State.VALUE ? Integer.valueOf(months.value()) : null;
        jdbcTemplate.update(
                UPSERT_LATEST,
                tenantId.value(),
                connectionId.value(),
                lineItemId,
                stringValue(properties.get(MonitoredLineItemProperty.NAME)),
                decimal(properties.get(MonitoredLineItemProperty.QUANTITY)),
                decimal(properties.get(MonitoredLineItemProperty.PRICE)),
                decimal(properties.get(MonitoredLineItemProperty.DISCOUNT)),
                decimal(properties.get(MonitoredLineItemProperty.DISCOUNT_PERCENTAGE)),
                stringValue(properties.get(MonitoredLineItemProperty.BILLING_FREQUENCY)),
                date.state() == ObservedValue.State.VALUE ? Date.valueOf(date.value()) : null,
                delayUnit,
                delayCount,
                stringValue(properties.get(MonitoredLineItemProperty.RECURRING_BILLING_PERIOD)),
                timestamp(projection.providerCreatedAt()),
                timestamp(projection.providerUpdatedAt()),
                Timestamp.from(projection.observedAt()),
                knownPropertiesArrayLiteral(properties),
                projection.dealSetComplete(),
                timestamp(projection.deletedAt()),
                projection.historyCoverage().mode().name(),
                Timestamp.from(projection.historyCoverage().observedFrom()));
        replaceSnapshotDeals(
                tenantId,
                connectionId,
                lineItemId,
                SnapshotKind.LATEST,
                projection.associatedDealIds());
    }

    private void writeAuditProjection(
            TenantId tenantId,
            PlatformConnectionId connectionId,
            UUID lineItemId,
            List<LineItemAuditEvent> events) {
        Set<ByteArrayKey> retainedKeys = new HashSet<>();
        for (LineItemAuditEvent event : events) {
            retainedKeys.add(new ByteArrayKey(event.semanticKey()));
            UUID eventId = jdbcTemplate.queryForObject(
                    UPSERT_AUDIT,
                    UUID.class,
                    UUID.randomUUID(),
                    tenantId.value(),
                    connectionId.value(),
                    lineItemId,
                    event.semanticKey(),
                    event.type().name(),
                    Timestamp.from(event.occurredAt()),
                    event.property() == null ? null : event.property().providerName(),
                    event.before().state().name(),
                    event.before().value(),
                    event.after().state().name(),
                    event.after().value(),
                    event.dealId() == null ? null : event.dealId().value());
            jdbcTemplate.update("""
                    DELETE FROM line_item_watch_audit_event_source source
                    WHERE source.tenant_id = ? AND source.connection_id = ?
                      AND source.audit_event_id = ?
                      AND EXISTS (
                          SELECT 1 FROM line_item_watch_change_signal signal
                          WHERE signal.tenant_id = source.tenant_id
                            AND signal.connection_id = source.connection_id
                            AND signal.id = source.source_signal_id
                      )
                    """, tenantId.value(), connectionId.value(), eventId);
            for (UUID sourceId : event.sourceSignalIds()) {
                jdbcTemplate.update("""
                        INSERT INTO line_item_watch_audit_event_source (
                            tenant_id, connection_id, audit_event_id, source_signal_id
                        ) VALUES (?, ?, ?, ?)
                        ON CONFLICT (audit_event_id, source_signal_id) DO NOTHING
                        """, tenantId.value(), connectionId.value(), eventId, sourceId);
            }
            jdbcTemplate.update(
                    "DELETE FROM line_item_watch_audit_event_deal_context WHERE audit_event_id = ?",
                    eventId);
            for (ProviderObjectId dealId : event.dealContext()) {
                jdbcTemplate.update("""
                        INSERT INTO line_item_watch_audit_event_deal_context (
                            tenant_id, connection_id, audit_event_id, external_deal_id,
                            occurred_at, semantic_key
                        ) VALUES (?, ?, ?, ?, ?, ?)
                        """, tenantId.value(), connectionId.value(), eventId, dealId.value(),
                        Timestamp.from(event.occurredAt()), event.semanticKey());
            }
        }

        List<ExistingAudit> existing = jdbcTemplate.query("""
                SELECT id, semantic_key
                FROM line_item_watch_audit_event
                WHERE tenant_id = ? AND connection_id = ? AND line_item_id = ?
                """, (row, ignored) -> new ExistingAudit(
                        row.getObject("id", UUID.class), row.getBytes("semantic_key")),
                tenantId.value(), connectionId.value(), lineItemId);
        for (ExistingAudit audit : existing) {
            if (retainedKeys.contains(new ByteArrayKey(audit.semanticKey()))) {
                continue;
            }
            Long missingSources = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM line_item_watch_audit_event_source src
                    LEFT JOIN line_item_watch_change_signal signal
                      ON signal.id = src.source_signal_id
                     AND signal.tenant_id = src.tenant_id
                     AND signal.connection_id = src.connection_id
                    WHERE src.audit_event_id = ? AND signal.id IS NULL
                    """, Long.class, audit.id());
            if (missingSources != null && missingSources == 0) {
                jdbcTemplate.update("DELETE FROM line_item_watch_audit_event WHERE id = ?", audit.id());
            }
        }
    }

    private void replaceSnapshotDeals(
            TenantId tenantId,
            PlatformConnectionId connectionId,
            UUID lineItemId,
            SnapshotKind kind,
            Set<ProviderObjectId> deals) {
        jdbcTemplate.update(
                "DELETE FROM line_item_watch_snapshot_deal WHERE line_item_id = ? AND snapshot_kind = ?",
                lineItemId,
                kind.name());
        for (ProviderObjectId dealId : deals) {
            jdbcTemplate.update("""
                    INSERT INTO line_item_watch_snapshot_deal (
                        tenant_id, connection_id, line_item_id, snapshot_kind, external_deal_id
                    ) VALUES (?, ?, ?, ?, ?)
                    """, tenantId.value(), connectionId.value(), lineItemId, kind.name(), dealId.value());
        }
    }

    private static final RowMapper<LineItemChangeSignal> SIGNAL_MAPPER = (row, ignored) -> {
        LineItemChangeSignalType type = LineItemChangeSignalType.valueOf(row.getString("signal_type"));
        String propertyName = row.getString("property_name");
        String dealId = row.getString("external_deal_id");
        String action = row.getString("association_action");
        return new LineItemChangeSignal(
                row.getObject("id", UUID.class),
                new TenantId(row.getObject("tenant_id", UUID.class)),
                new PlatformConnectionId(row.getObject("connection_id", UUID.class)),
                row.getString("provider_event_id"),
                row.getString("provider_subscription_id"),
                new ProviderDeduplicationKey(row.getBytes("provider_deduplication_key")),
                new ProviderObjectId(row.getString("external_line_item_id")),
                type,
                row.getTimestamp("occurred_at").toInstant(),
                row.getTimestamp("received_at").toInstant(),
                propertyName == null
                        ? null
                        : MonitoredLineItemProperty.fromProviderName(propertyName).orElseThrow(),
                row.getString("property_value"),
                dealId == null ? null : new ProviderObjectId(dealId),
                action == null ? null : AssociationAction.valueOf(action),
                row.getString("association_type_id"),
                row.getString("association_category"));
    };

    private static ObservedValue value(String value) {
        return value == null ? ObservedValue.absent() : ObservedValue.value(value);
    }

    private static ObservedValue decimalValue(BigDecimal value) {
        return value == null
                ? ObservedValue.absent()
                : LineItemPropertyValues.normalize(
                        MonitoredLineItemProperty.QUANTITY, value.toPlainString());
    }

    private static String stringValue(ObservedValue value) {
        return value.state() == ObservedValue.State.VALUE ? value.value() : null;
    }

    private static BigDecimal decimal(ObservedValue value) {
        return value.state() == ObservedValue.State.VALUE ? new BigDecimal(value.value()) : null;
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Date sqlDate(LocalDate value) {
        return value == null ? null : Date.valueOf(value);
    }

    private static String allPropertiesArrayLiteral() {
        EnumMap<MonitoredLineItemProperty, ObservedValue> all =
                new EnumMap<>(MonitoredLineItemProperty.class);
        Arrays.stream(MonitoredLineItemProperty.values())
                .forEach(property -> all.put(property, ObservedValue.absent()));
        return knownPropertiesArrayLiteral(all);
    }

    private static String knownPropertiesArrayLiteral(
            Map<MonitoredLineItemProperty, ObservedValue> properties) {
        return properties.entrySet().stream()
                .filter(entry -> entry.getValue().state() != ObservedValue.State.UNKNOWN)
                .map(entry -> entry.getKey().providerName())
                .sorted()
                .collect(java.util.stream.Collectors.joining(",", "{", "}"));
    }

    record LockedLineItem(UUID id, ProviderObjectId externalId, boolean created) {
    }

    private record ExistingAudit(UUID id, byte[] semanticKey) {
    }

    private static final class ByteArrayKey {
        private final byte[] value;

        private ByteArrayKey(byte[] value) {
            this.value = value.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ByteArrayKey that && Arrays.equals(value, that.value);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(value);
        }
    }
}
