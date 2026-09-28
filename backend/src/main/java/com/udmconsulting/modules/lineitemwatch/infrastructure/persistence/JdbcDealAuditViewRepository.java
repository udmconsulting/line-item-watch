package com.udmconsulting.modules.lineitemwatch.infrastructure.persistence;

import com.udmconsulting.modules.lineitemwatch.application.DealAuditQuery;
import com.udmconsulting.modules.lineitemwatch.application.DealAuditView;
import com.udmconsulting.modules.lineitemwatch.application.DealAuditViewRepository;
import com.udmconsulting.modules.lineitemwatch.domain.LineItemAuditType;
import com.udmconsulting.modules.lineitemwatch.domain.LineItemHistoryCoverage;
import com.udmconsulting.modules.lineitemwatch.domain.LineItemPropertyValues;
import com.udmconsulting.modules.lineitemwatch.domain.MonitoredLineItemProperty;
import com.udmconsulting.modules.lineitemwatch.domain.ObservedValue;
import com.udmconsulting.modules.lineitemwatch.domain.ProviderObjectId;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcDealAuditViewRepository implements DealAuditViewRepository {

    private static final String RELEVANT_LINE_ITEMS = """
            SELECT association.line_item_id
            FROM line_item_watch_snapshot_deal association
            JOIN line_item_watch_snapshot evidence
              ON evidence.tenant_id = association.tenant_id
             AND evidence.connection_id = association.connection_id
             AND evidence.line_item_id = association.line_item_id
             AND evidence.snapshot_kind = association.snapshot_kind
            JOIN line_item_watch_snapshot latest
              ON latest.tenant_id = association.tenant_id
             AND latest.connection_id = association.connection_id
             AND latest.line_item_id = association.line_item_id
             AND latest.snapshot_kind = 'LATEST'
            WHERE association.tenant_id = ?
              AND association.connection_id = ?
              AND association.external_deal_id = ?
              AND (evidence.snapshot_kind = 'LATEST'
                   OR latest.deleted_at IS NULL
                   OR evidence.observed_at <= latest.deleted_at)
            UNION
            SELECT event.line_item_id
            FROM line_item_watch_audit_event_deal_context context
            JOIN line_item_watch_audit_event event
              ON event.tenant_id = context.tenant_id
             AND event.connection_id = context.connection_id
             AND event.id = context.audit_event_id
            JOIN line_item_watch_snapshot coverage
              ON coverage.tenant_id = event.tenant_id
             AND coverage.connection_id = event.connection_id
             AND coverage.line_item_id = event.line_item_id
             AND coverage.snapshot_kind = 'LATEST'
             AND context.occurred_at >= coverage.history_observed_from
            WHERE context.tenant_id = ?
              AND context.connection_id = ?
              AND context.external_deal_id = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    JdbcDealAuditViewRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public StoredPage<StoredLineItem> readLineItems(DealAuditQuery query) {
        String cursorPredicate = query.lineItemsCursor() == null
                ? ""
                : "AND item.external_line_item_id > ?";
        String sql = """
                WITH relevant AS (
                %s
                )
                SELECT item.external_line_item_id,
                       latest.*,
                       EXISTS (
                           SELECT 1
                           FROM line_item_watch_snapshot_deal current_deal
                           WHERE current_deal.tenant_id = latest.tenant_id
                             AND current_deal.connection_id = latest.connection_id
                             AND current_deal.line_item_id = latest.line_item_id
                             AND current_deal.snapshot_kind = 'LATEST'
                             AND current_deal.external_deal_id = ?
                       ) AS requested_deal_present,
                       last_association.event_type AS latest_association_type
                FROM relevant
                JOIN line_item_watch_line_item item ON item.id = relevant.line_item_id
                LEFT JOIN line_item_watch_snapshot latest
                  ON latest.tenant_id = item.tenant_id
                 AND latest.connection_id = item.connection_id
                 AND latest.line_item_id = item.id
                 AND latest.snapshot_kind = 'LATEST'
                LEFT JOIN LATERAL (
                    SELECT event.event_type
                    FROM line_item_watch_audit_event event
                    WHERE event.tenant_id = item.tenant_id
                      AND event.connection_id = item.connection_id
                      AND event.line_item_id = item.id
                      AND event.external_deal_id = ?
                      AND event.event_type IN ('DEAL_ASSOCIATED', 'DEAL_DISASSOCIATED')
                    ORDER BY event.occurred_at DESC, event.semantic_key DESC
                    LIMIT 1
                ) last_association ON TRUE
                WHERE item.tenant_id = ?
                  AND item.connection_id = ?
                  %s
                ORDER BY item.external_line_item_id
                LIMIT ?
                """.formatted(RELEVANT_LINE_ITEMS.indent(4), cursorPredicate);

        var arguments = new java.util.ArrayList<>();
        addOwnerDeal(arguments, query);
        addOwnerDeal(arguments, query);
        arguments.add(query.dealId().value());
        arguments.add(query.dealId().value());
        arguments.add(query.tenantId().value());
        arguments.add(query.connectionId().value());
        if (query.lineItemsCursor() != null) {
            arguments.add(query.lineItemsCursor().lineItemId().value());
        }
        arguments.add(query.lineItemsLimit() + 1);

        List<StoredLineItem> rows = jdbcTemplate.query(
                sql, this::lineItem, arguments.toArray());
        boolean hasMore = rows.size() > query.lineItemsLimit();
        return new StoredPage<>(
                hasMore ? rows.subList(0, query.lineItemsLimit()) : rows,
                hasMore);
    }

    @Override
    public StoredPage<DealAuditView.AuditEvent> readEvents(DealAuditQuery query) {
        String cursorPredicate = query.eventsCursor() == null
                ? ""
                : """
                  AND (context.occurred_at < ?
                       OR (context.occurred_at = ? AND context.semantic_key < ?))
                  """;
        String sql = """
                SELECT context.semantic_key, context.occurred_at,
                       item.external_line_item_id, event.event_type,
                       event.property_name, event.before_state, event.before_value,
                       event.after_state, event.after_value
                FROM line_item_watch_audit_event_deal_context context
                JOIN line_item_watch_audit_event event
                  ON event.tenant_id = context.tenant_id
                 AND event.connection_id = context.connection_id
                 AND event.id = context.audit_event_id
                 AND event.occurred_at = context.occurred_at
                 AND event.semantic_key = context.semantic_key
                JOIN line_item_watch_line_item item
                  ON item.tenant_id = event.tenant_id
                 AND item.connection_id = event.connection_id
                 AND item.id = event.line_item_id
                JOIN line_item_watch_snapshot coverage
                  ON coverage.tenant_id = event.tenant_id
                 AND coverage.connection_id = event.connection_id
                 AND coverage.line_item_id = event.line_item_id
                 AND coverage.snapshot_kind = 'LATEST'
                WHERE context.tenant_id = ?
                  AND context.connection_id = ?
                  AND context.external_deal_id = ?
                  AND context.occurred_at >= coverage.history_observed_from
                  %s
                ORDER BY context.occurred_at DESC, context.semantic_key DESC
                LIMIT ?
                """.formatted(cursorPredicate);

        var arguments = new java.util.ArrayList<>();
        addOwnerDeal(arguments, query);
        if (query.eventsCursor() != null) {
            Timestamp occurredAt = Timestamp.from(query.eventsCursor().occurredAt());
            arguments.add(occurredAt);
            arguments.add(occurredAt);
            arguments.add(query.eventsCursor().semanticKey());
        }
        arguments.add(query.eventsLimit() + 1);

        List<DealAuditView.AuditEvent> rows = jdbcTemplate.query(
                sql, JdbcDealAuditViewRepository::event, arguments.toArray());
        boolean hasMore = rows.size() > query.eventsLimit();
        return new StoredPage<>(
                hasMore ? rows.subList(0, query.eventsLimit()) : rows,
                hasMore);
    }

    private static void addOwnerDeal(java.util.List<Object> arguments, DealAuditQuery query) {
        arguments.add(query.tenantId().value());
        arguments.add(query.connectionId().value());
        arguments.add(query.dealId().value());
    }

    private StoredLineItem lineItem(ResultSet row, int ignored) throws SQLException {
        if (row.getString("snapshot_kind") == null) {
            throw new IllegalStateException("relevant Line Item has no LATEST projection");
        }
        String association = row.getString("latest_association_type");
        return new StoredLineItem(
                new ProviderObjectId(row.getString("external_line_item_id")),
                instant(row, "deleted_at"),
                row.getBoolean("requested_deal_present"),
                row.getBoolean("deal_set_complete"),
                association == null ? null : LineItemAuditType.valueOf(association),
                latestProperties(row),
                new LineItemHistoryCoverage(
                        LineItemHistoryCoverage.Mode.valueOf(
                                row.getString("history_coverage_mode")),
                        row.getTimestamp("history_observed_from").toInstant()));
    }

    private static DealAuditView.AuditEvent event(ResultSet row, int ignored) throws SQLException {
        String property = row.getString("property_name");
        return new DealAuditView.AuditEvent(
                row.getBytes("semantic_key"),
                new ProviderObjectId(row.getString("external_line_item_id")),
                LineItemAuditType.valueOf(row.getString("event_type")),
                row.getTimestamp("occurred_at").toInstant(),
                property == null
                        ? null
                        : MonitoredLineItemProperty.fromProviderName(property).orElseThrow(),
                observedValue(row, "before_state", "before_value"),
                observedValue(row, "after_state", "after_value"));
    }

    private static Map<MonitoredLineItemProperty, ObservedValue> latestProperties(ResultSet row)
            throws SQLException {
        Set<String> known = knownProperties(row.getArray("known_properties"));
        EnumMap<MonitoredLineItemProperty, ObservedValue> values =
                new EnumMap<>(MonitoredLineItemProperty.class);
        values.put(MonitoredLineItemProperty.NAME,
                property(known, MonitoredLineItemProperty.NAME, row.getString("name")));
        values.put(MonitoredLineItemProperty.QUANTITY,
                decimalProperty(known, MonitoredLineItemProperty.QUANTITY, row.getBigDecimal("quantity")));
        values.put(MonitoredLineItemProperty.PRICE,
                decimalProperty(known, MonitoredLineItemProperty.PRICE, row.getBigDecimal("unit_price")));
        values.put(MonitoredLineItemProperty.DISCOUNT,
                decimalProperty(known, MonitoredLineItemProperty.DISCOUNT, row.getBigDecimal("unit_discount")));
        values.put(MonitoredLineItemProperty.DISCOUNT_PERCENTAGE,
                decimalProperty(known, MonitoredLineItemProperty.DISCOUNT_PERCENTAGE,
                        row.getBigDecimal("discount_percentage")));
        values.put(MonitoredLineItemProperty.BILLING_FREQUENCY,
                property(known, MonitoredLineItemProperty.BILLING_FREQUENCY,
                        row.getString("billing_frequency")));
        Date date = row.getDate("billing_start_date");
        values.put(MonitoredLineItemProperty.BILLING_START_DATE,
                property(known, MonitoredLineItemProperty.BILLING_START_DATE,
                        date == null ? null : date.toLocalDate().toString()));
        String delayUnit = row.getString("billing_start_delay_unit");
        Integer delayCount = (Integer) row.getObject("billing_start_delay_count");
        values.put(MonitoredLineItemProperty.BILLING_START_DELAY_DAYS,
                property(known, MonitoredLineItemProperty.BILLING_START_DELAY_DAYS,
                        "DAYS".equals(delayUnit) ? Integer.toString(delayCount) : null));
        values.put(MonitoredLineItemProperty.BILLING_START_DELAY_MONTHS,
                property(known, MonitoredLineItemProperty.BILLING_START_DELAY_MONTHS,
                        "MONTHS".equals(delayUnit) ? Integer.toString(delayCount) : null));
        values.put(MonitoredLineItemProperty.RECURRING_BILLING_PERIOD,
                property(known, MonitoredLineItemProperty.RECURRING_BILLING_PERIOD,
                        row.getString("recurring_billing_period")));
        return Map.copyOf(values);
    }

    private static Set<String> knownProperties(Array array) throws SQLException {
        Set<String> values = new HashSet<>();
        Object raw = array.getArray();
        for (Object value : (Object[]) raw) {
            values.add(value.toString());
        }
        return values;
    }

    private static ObservedValue decimalProperty(
            Set<String> known,
            MonitoredLineItemProperty property,
            BigDecimal value) {
        return property(known, property, value == null ? null : value.toPlainString());
    }

    private static ObservedValue property(
            Set<String> known,
            MonitoredLineItemProperty property,
            String value) {
        if (!known.contains(property.providerName())) {
            return ObservedValue.unknown();
        }
        return value == null ? ObservedValue.absent() : LineItemPropertyValues.normalize(property, value);
    }

    private static ObservedValue observedValue(
            ResultSet row, String stateColumn, String valueColumn) throws SQLException {
        ObservedValue.State state = ObservedValue.State.valueOf(row.getString(stateColumn));
        return new ObservedValue(state, row.getString(valueColumn));
    }

    private static java.time.Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
