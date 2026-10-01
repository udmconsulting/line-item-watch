package com.udmconsulting.modules.lineitemwatch.infrastructure.persistence;

import com.udmconsulting.modules.lineitemwatch.application.LineItemChangeSignalStore;
import com.udmconsulting.modules.lineitemwatch.application.LineItemReliabilityStore;
import com.udmconsulting.modules.lineitemwatch.domain.LineItemChangeSignal;
import com.udmconsulting.platform.connection.application.PlatformConnectionService;
import com.udmconsulting.platform.connection.domain.ConnectionStatus;
import com.udmconsulting.platform.connection.domain.PlatformConnection;
import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.connection.domain.Provider;
import com.udmconsulting.platform.entitlement.application.EntitlementService;
import com.udmconsulting.platform.module.domain.ProductModule;
import com.udmconsulting.platform.tenant.domain.TenantId;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcLineItemChangeSignalStore implements LineItemChangeSignalStore {

    private static final String INSERT_SIGNAL = """
            INSERT INTO line_item_watch_change_signal (
                id, tenant_id, connection_id, provider_event_id,
                provider_subscription_id, provider_deduplication_key,
                external_line_item_id, signal_type, occurred_at, received_at,
                property_name, property_value, external_deal_id,
                association_action, association_type_id, association_category
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, connection_id, provider_deduplication_key) DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;
    private final PlatformConnectionService connectionService;
    private final EntitlementService entitlementService;
    private final LineItemReliabilityStore reliabilityStore;

    public JdbcLineItemChangeSignalStore(
            JdbcTemplate jdbcTemplate,
            PlatformConnectionService connectionService,
            EntitlementService entitlementService,
            LineItemReliabilityStore reliabilityStore) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
        this.connectionService = Objects.requireNonNull(connectionService);
        this.entitlementService = Objects.requireNonNull(entitlementService);
        this.reliabilityStore = Objects.requireNonNull(reliabilityStore);
    }

    @Override
    @Transactional
    public CaptureResult capture(
            TenantId tenantId,
            PlatformConnectionId connectionId,
            List<LineItemChangeSignal> signals) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(connectionId, "connectionId must not be null");
        Objects.requireNonNull(signals, "signals must not be null");

        PlatformConnection connection = connectionService.lockForCommit(tenantId, connectionId)
                .orElse(null);
        if (connection == null
                || connection.provider() != Provider.HUBSPOT
                || connection.status() != ConnectionStatus.ACTIVE
                || !entitlementService.lockEnabledForCommit(
                        tenantId, ProductModule.LINE_ITEM_WATCH)) {
            return CaptureResult.ineligible();
        }

        int captured = 0;
        for (LineItemChangeSignal signal : signals) {
            if (!tenantId.equals(signal.tenantId())
                    || !connectionId.equals(signal.connectionId())) {
                throw new IllegalArgumentException(
                        "signal Tenant/connection provenance does not match the capture group");
            }
            int inserted = jdbcTemplate.update(
                    INSERT_SIGNAL,
                    signal.id(),
                    tenantId.value(),
                    connectionId.value(),
                    signal.providerEventId(),
                    signal.providerSubscriptionId(),
                    signal.providerDeduplicationKey().value(),
                    signal.lineItemId().value(),
                    signal.type().name(),
                    Timestamp.from(signal.occurredAt()),
                    Timestamp.from(signal.receivedAt()),
                    signal.property() == null ? null : signal.property().providerName(),
                    signal.propertyValue(),
                    signal.dealId() == null ? null : signal.dealId().value(),
                    signal.associationAction() == null ? null : signal.associationAction().name(),
                    signal.associationTypeId(),
                    signal.associationCategory());
            captured += inserted;
            if (inserted == 1) {
                jdbcTemplate.update("""
                        INSERT INTO line_item_watch_signal_processing (
                            signal_id, tenant_id, connection_id, status, next_attempt_at
                        ) VALUES (?, ?, ?, 'PENDING', CURRENT_TIMESTAMP)
                        ON CONFLICT (signal_id) DO NOTHING
                        """,
                        signal.id(), tenantId.value(), connectionId.value());
            }
        }
        signals.stream()
                .map(LineItemChangeSignal::receivedAt)
                .max(java.time.Instant::compareTo)
                .ifPresent(observedAt -> reliabilityStore.signalObserved(
                        tenantId, connectionId, observedAt));
        return new CaptureResult(captured, signals.size() - captured, true);
    }
}
