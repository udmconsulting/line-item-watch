package com.udmconsulting.modules.lineitemwatch.application;

import com.udmconsulting.modules.lineitemwatch.application.LineItemSnapshotStore.PersistenceResult;
import com.udmconsulting.modules.lineitemwatch.domain.ProviderObjectId;
import com.udmconsulting.platform.connection.application.PlatformConnectionService;
import com.udmconsulting.platform.connection.domain.ConnectionStatus;
import com.udmconsulting.platform.connection.domain.PlatformConnection;
import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.connection.domain.Provider;
import com.udmconsulting.platform.entitlement.application.EntitlementService;
import com.udmconsulting.platform.module.domain.ProductModule;
import com.udmconsulting.platform.tenant.domain.TenantId;
import java.util.HashSet;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public final class EstablishLineItemBaseline {

    private static final Logger LOGGER = LoggerFactory.getLogger(EstablishLineItemBaseline.class);

    private final PlatformConnectionService connectionService;
    private final EntitlementService entitlementService;
    private final LineItemBaselineSource baselineSource;
    private final LineItemSnapshotStore snapshotStore;

    public EstablishLineItemBaseline(
            PlatformConnectionService connectionService,
            EntitlementService entitlementService,
            LineItemBaselineSource baselineSource,
            LineItemSnapshotStore snapshotStore) {
        this.connectionService = Objects.requireNonNull(connectionService);
        this.entitlementService = Objects.requireNonNull(entitlementService);
        this.baselineSource = Objects.requireNonNull(baselineSource);
        this.snapshotStore = Objects.requireNonNull(snapshotStore);
    }

    public Result execute(
            TenantId tenantId,
            PlatformConnectionId connectionId,
            ProviderObjectId dealId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(connectionId, "connectionId must not be null");
        Objects.requireNonNull(dealId, "dealId must not be null");

        PlatformConnection connection = requireUsableConnection(tenantId, connectionId);
        requireEntitlement(tenantId);

        DealLineItemObservations observed = baselineSource.readDeal(connection, dealId);
        if (!dealId.equals(observed.dealId())) {
            throw new BaselineSyncException("Provider returned an unexpected Deal identity", false);
        }
        HashSet<ProviderObjectId> uniqueLineItems = new HashSet<>();
        observed.lineItems().forEach(item -> {
            if (!uniqueLineItems.add(item.lineItemId())) {
                throw new BaselineSyncException("Provider returned a duplicate Line Item identity", false);
            }
            if (!item.associatedDealIds().contains(dealId)) {
                throw new BaselineSyncException(
                        "Provider state changed while the Deal baseline was read", true);
            }
        });

        PersistenceResult persisted = snapshotStore.establish(
                tenantId, connectionId, observed.lineItems());
        LOGGER.info(
                "Line Item baseline synchronization completed tenantId={} connectionId={} "
                        + "observed={} createdItems={} createdBaselines={} createdLatest={} "
                        + "updatedLatest={} unchangedLatest={}",
                tenantId.value(),
                connectionId.value(),
                observed.lineItems().size(),
                persisted.createdLineItems(),
                persisted.createdBaselines(),
                persisted.createdLatestSnapshots(),
                persisted.updatedLatestSnapshots(),
                persisted.unchangedLatestSnapshots());
        return new Result(
                dealId,
                observed.lineItems().size(),
                persisted.createdLineItems(),
                persisted.createdBaselines(),
                persisted.createdLatestSnapshots(),
                persisted.updatedLatestSnapshots(),
                persisted.unchangedLatestSnapshots());
    }

    private PlatformConnection requireUsableConnection(
            TenantId tenantId, PlatformConnectionId connectionId) {
        PlatformConnection connection = connectionService.findForTenant(tenantId, connectionId)
                .orElseThrow(() -> new BaselineSyncException(
                        "Platform Connection was not found for Tenant", false));
        if (connection.provider() != Provider.HUBSPOT) {
            throw new BaselineSyncException("Platform Connection provider is not supported", false);
        }
        if (connection.status() != ConnectionStatus.ACTIVE) {
            throw new BaselineSyncException("Platform Connection is not active", false);
        }
        return connection;
    }

    private void requireEntitlement(TenantId tenantId) {
        if (!entitlementService.isEnabled(tenantId, ProductModule.LINE_ITEM_WATCH)) {
            throw new BaselineSyncException("Tenant is not entitled to LINE_ITEM_WATCH", false);
        }
    }

    public record Result(
            ProviderObjectId dealId,
            int observedLineItems,
            int createdLineItems,
            int createdBaselines,
            int createdLatestSnapshots,
            int updatedLatestSnapshots,
            int unchangedLatestSnapshots) {
    }
}
