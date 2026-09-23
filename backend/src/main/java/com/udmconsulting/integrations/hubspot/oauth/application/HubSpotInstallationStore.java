package com.udmconsulting.integrations.hubspot.oauth.application;

import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.credential.domain.ConnectionCredential;
import com.udmconsulting.platform.tenant.domain.TenantId;
import java.util.Optional;
import java.util.Set;

public interface HubSpotInstallationStore {

    FinalizedInstallation finalizeInstallation(
            String externalAccountId, String refreshToken, Set<String> grantedScopes);

    record FinalizedInstallation(
            TenantId tenantId,
            PlatformConnectionId connectionId,
            Optional<ConnectionCredential> supersededCredential) {
    }
}
