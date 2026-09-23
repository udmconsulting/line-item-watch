package com.udmconsulting.integrations.hubspot.oauth.application;

import com.udmconsulting.platform.connection.application.PlatformConnectionService;
import com.udmconsulting.platform.connection.domain.PlatformConnection;
import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.connection.domain.Provider;
import com.udmconsulting.platform.credential.application.ConnectionCredentialService;
import com.udmconsulting.platform.tenant.domain.TenantId;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public final class HubSpotUninstallService {

    private final PlatformConnectionService connectionService;
    private final HubSpotAccessTokenProvider accessTokenProvider;
    private final HubSpotOAuthGateway oauthGateway;
    private final ConnectionCredentialService credentialService;

    public HubSpotUninstallService(
            PlatformConnectionService connectionService,
            HubSpotAccessTokenProvider accessTokenProvider,
            HubSpotOAuthGateway oauthGateway,
            ConnectionCredentialService credentialService) {
        this.connectionService = connectionService;
        this.accessTokenProvider = accessTokenProvider;
        this.oauthGateway = oauthGateway;
        this.credentialService = credentialService;
    }

    public void uninstall(TenantId tenantId, PlatformConnectionId connectionId) {
        PlatformConnection connection = connectionService.findForTenant(
                        Objects.requireNonNull(tenantId), Objects.requireNonNull(connectionId))
                .filter(candidate -> candidate.provider() == Provider.HUBSPOT)
                .orElseThrow(() -> new IllegalArgumentException("HubSpot connection was not found for tenant"));
        HubSpotAccessTokenProvider.TransientAccessGrant access =
                accessTokenProvider.accessTokenFor(connection);
        oauthGateway.uninstall(access.accessToken());
        credentialService.disconnect(connection.id(), access.expectedCredentialGeneration());
    }
}
