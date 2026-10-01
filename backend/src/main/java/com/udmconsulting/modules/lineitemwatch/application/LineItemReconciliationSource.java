package com.udmconsulting.modules.lineitemwatch.application;

import com.udmconsulting.modules.lineitemwatch.domain.ProviderObjectId;
import com.udmconsulting.platform.connection.domain.PlatformConnection;

/** Provider-facing reconciliation read. Implementations must not hold a database transaction. */
public interface LineItemReconciliationSource {

    ReconciliationObservation readDealCurrent(
            PlatformConnection connection, ProviderObjectId dealId);

    record ReconciliationObservation(
            DealLineItemObservations observations, long expectedCredentialGeneration) {
    }
}
