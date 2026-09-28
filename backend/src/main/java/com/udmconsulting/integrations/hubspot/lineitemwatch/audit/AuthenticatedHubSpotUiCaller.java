package com.udmconsulting.integrations.hubspot.lineitemwatch.audit;

import com.udmconsulting.platform.connection.domain.ExternalAccountId;
import java.util.Objects;

record AuthenticatedHubSpotUiCaller(ExternalAccountId externalAccountId) {
    AuthenticatedHubSpotUiCaller {
        Objects.requireNonNull(externalAccountId, "externalAccountId must not be null");
    }
}
