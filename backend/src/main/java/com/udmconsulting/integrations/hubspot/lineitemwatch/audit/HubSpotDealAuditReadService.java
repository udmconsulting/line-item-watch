package com.udmconsulting.integrations.hubspot.lineitemwatch.audit;

import com.udmconsulting.modules.lineitemwatch.application.DealAuditQuery;
import com.udmconsulting.modules.lineitemwatch.application.DealAuditView;
import com.udmconsulting.modules.lineitemwatch.application.ReadDealAudit;
import com.udmconsulting.platform.connection.application.PlatformConnectionService;
import com.udmconsulting.platform.connection.domain.ConnectionStatus;
import com.udmconsulting.platform.connection.domain.PlatformConnection;
import com.udmconsulting.platform.connection.domain.Provider;
import com.udmconsulting.platform.entitlement.application.EntitlementService;
import com.udmconsulting.platform.module.domain.ProductModule;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "hubspot.ui-extension", name = "enabled", havingValue = "true")
class HubSpotDealAuditReadService {

    private static final Logger LOGGER = LoggerFactory.getLogger(HubSpotDealAuditReadService.class);

    private final PlatformConnectionService connectionService;
    private final EntitlementService entitlementService;
    private final ReadDealAudit readDealAudit;

    HubSpotDealAuditReadService(
            PlatformConnectionService connectionService,
            EntitlementService entitlementService,
            ReadDealAudit readDealAudit) {
        this.connectionService = connectionService;
        this.entitlementService = entitlementService;
        this.readDealAudit = readDealAudit;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    DealAuditView read(
            AuthenticatedHubSpotUiCaller caller,
            DealAuditQueryFactory.QueryInput input,
            UUID correlationId) {
        PlatformConnection resolved;
        try {
            resolved = connectionService.resolve(Provider.HUBSPOT, caller.externalAccountId())
                    .orElseThrow(AccountUnavailableException::new);
        } catch (IncorrectResultSizeDataAccessException exception) {
            throw new AccountResolutionInvariantException();
        }
        PlatformConnection connection = connectionService
                .lockForCommit(resolved.tenantId(), resolved.id())
                .orElseThrow(AccountUnavailableException::new);
        if (connection.provider() != Provider.HUBSPOT
                || connection.status() != ConnectionStatus.ACTIVE
                || !entitlementService.lockEnabledForCommit(
                        connection.tenantId(), ProductModule.LINE_ITEM_WATCH)) {
            throw new AccountUnavailableException();
        }

        DealAuditView result = readDealAudit.read(new DealAuditQuery(
                connection.tenantId(),
                connection.id(),
                input.dealId(),
                input.lineItemsLimit(),
                input.lineItemsCursor(),
                input.eventsLimit(),
                input.eventsCursor()));
        LOGGER.atInfo()
                .addKeyValue("component", "line_item_watch")
                .addKeyValue("operation", "deal_audit_read")
                .addKeyValue("result", "SUCCESS")
                .addKeyValue("tenantRef", connection.tenantId().value())
                .addKeyValue("connectionRef", connection.id().value())
                .addKeyValue("lineItemCount", result.lineItems().items().size())
                .addKeyValue("eventCount", result.events().items().size())
                .log("Deal audit read completed");
        return result;
    }
}
