package com.udmconsulting.modules.lineitemwatch.application;

import com.udmconsulting.modules.lineitemwatch.domain.ProviderObjectId;
import com.udmconsulting.platform.connection.domain.PlatformConnection;

public interface LineItemBaselineSource {

    DealLineItemObservations readDeal(
            PlatformConnection connection, ProviderObjectId dealId);
}
