package com.udmconsulting.modules.lineitemwatch.infrastructure.persistence;

import com.udmconsulting.modules.lineitemwatch.application.BaselineSyncException;
import com.udmconsulting.modules.lineitemwatch.application.BaselineSyncFailure;
import com.udmconsulting.modules.lineitemwatch.application.LineItemSnapshotStore;
import com.udmconsulting.modules.lineitemwatch.domain.LineItemObservation;
import com.udmconsulting.modules.lineitemwatch.domain.SnapshotKind;
import com.udmconsulting.modules.lineitemwatch.infrastructure.persistence.JdbcLineItemProjectionRepository.LockedLineItem;
import com.udmconsulting.platform.connection.application.PlatformConnectionService;
import com.udmconsulting.platform.connection.domain.ConnectionStatus;
import com.udmconsulting.platform.connection.domain.PlatformConnection;
import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.connection.domain.Provider;
import com.udmconsulting.platform.entitlement.application.EntitlementService;
import com.udmconsulting.platform.module.domain.ProductModule;
import com.udmconsulting.platform.tenant.domain.TenantId;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcLineItemSnapshotStore implements LineItemSnapshotStore {

    private final PlatformConnectionService connectionService;
    private final EntitlementService entitlementService;
    private final JdbcLineItemProjectionRepository projectionRepository;

    public JdbcLineItemSnapshotStore(
            PlatformConnectionService connectionService,
            EntitlementService entitlementService,
            JdbcLineItemProjectionRepository projectionRepository) {
        this.connectionService = Objects.requireNonNull(connectionService);
        this.entitlementService = Objects.requireNonNull(entitlementService);
        this.projectionRepository = Objects.requireNonNull(projectionRepository);
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
                        BaselineSyncFailure.CONNECTION_NOT_FOUND));
        if (connection.provider() != Provider.HUBSPOT) {
            throw new BaselineSyncException(
                    BaselineSyncFailure.CONNECTION_PROVIDER_UNSUPPORTED);
        }
        if (connection.status() != ConnectionStatus.ACTIVE) {
            throw new BaselineSyncException(BaselineSyncFailure.CONNECTION_NOT_ACTIVE);
        }
        if (!entitlementService.lockEnabledForCommit(
                tenantId, ProductModule.LINE_ITEM_WATCH)) {
            throw new BaselineSyncException(BaselineSyncFailure.MODULE_NOT_ENTITLED);
        }

        int createdLineItems = 0;
        int createdBaselines = 0;
        int createdLatest = 0;
        int updatedLatest = 0;
        int unchangedLatest = 0;

        List<LineItemObservation> orderedObservations = observations.stream()
                .sorted(Comparator.comparing(observation -> observation.lineItemId().value()))
                .toList();
        for (LineItemObservation observation : orderedObservations) {
            LockedLineItem lineItem = projectionRepository.ensureAndLock(
                    tenantId, connectionId, observation.lineItemId());
            if (lineItem.created()) {
                createdLineItems++;
            }
            boolean latestExisted = !lineItem.created();
            int baselineChanged = projectionRepository.upsertCompleteCheckpoint(
                    tenantId, connectionId, lineItem, SnapshotKind.BASELINE, observation);
            createdBaselines += baselineChanged;
            int observedChanged = projectionRepository.upsertCompleteCheckpoint(
                    tenantId, connectionId, lineItem, SnapshotKind.OBSERVED, observation);

            // P.3 and P.5 share this single locked writer for derived LATEST.
            projectionRepository.rebuildLocked(tenantId, connectionId, lineItem, null);
            if (!latestExisted) {
                createdLatest++;
            } else if (observedChanged == 1) {
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

}
