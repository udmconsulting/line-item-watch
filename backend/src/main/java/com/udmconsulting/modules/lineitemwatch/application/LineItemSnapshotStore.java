package com.udmconsulting.modules.lineitemwatch.application;

import com.udmconsulting.modules.lineitemwatch.domain.LineItemObservation;
import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.tenant.domain.TenantId;
import java.util.List;

public interface LineItemSnapshotStore {

    /**
     * Atomically revalidates and guards the active HubSpot connection and LINE_ITEM_WATCH
     * entitlement before establishing the already-normalized observations.
     */
    PersistenceResult establish(
            TenantId tenantId,
            PlatformConnectionId connectionId,
            List<LineItemObservation> observations);

    record PersistenceResult(
            int createdLineItems,
            int createdBaselines,
            int createdLatestSnapshots,
            int updatedLatestSnapshots,
            int unchangedLatestSnapshots) {
    }
}
