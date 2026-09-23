package com.udmconsulting.integrations.hubspot.oauth.application;

import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.tenant.domain.TenantId;
import java.net.URI;
import java.util.UUID;

public interface HubSpotInstallationUseCase {

    InstallationStart beginInstallation();

    CompletedInstallation completeInstallation(String state, String authorizationCode);

    void rejectInstallation(String state);

    record InstallationStart(URI authorizationUri, UUID correlationId) {

        @Override
        public String toString() {
            return "InstallationStart[authorizationUri=<redacted>, correlationId=" + correlationId + "]";
        }
    }

    record CompletedInstallation(TenantId tenantId, PlatformConnectionId connectionId) {
    }
}
