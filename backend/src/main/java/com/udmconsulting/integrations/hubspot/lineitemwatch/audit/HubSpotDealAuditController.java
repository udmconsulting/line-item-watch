package com.udmconsulting.integrations.hubspot.lineitemwatch.audit;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(prefix = "hubspot.ui-extension", name = "enabled", havingValue = "true")
final class HubSpotDealAuditController {

    static final String ENDPOINT = "/api/v1/line-item-watch/deals/{dealId}/audit";

    private final HubSpotUiExtensionRequestAuthenticator authenticator;
    private final DealAuditQueryFactory queryFactory;
    private final HubSpotDealAuditReadService readService;
    private final DealAuditCursorCodec cursorCodec;

    HubSpotDealAuditController(
            HubSpotUiExtensionRequestAuthenticator authenticator,
            DealAuditQueryFactory queryFactory,
            HubSpotDealAuditReadService readService,
            DealAuditCursorCodec cursorCodec) {
        this.authenticator = authenticator;
        this.queryFactory = queryFactory;
        this.readService = readService;
        this.cursorCodec = cursorCodec;
    }

    @GetMapping(ENDPOINT)
    ResponseEntity<DealAuditViewDto> read(
            @PathVariable String dealId,
            HttpServletRequest request) {
        UUID correlationId = DealAuditCorrelationFilter.correlationId(request);
        AuthenticatedHubSpotUiCaller caller = authenticator.authenticate(request);
        DealAuditQueryFactory.QueryInput input = queryFactory.create(dealId, request);
        DealAuditViewDto response = DealAuditViewDto.from(
                input, readService.read(caller, input, correlationId), cursorCodec);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(response);
    }
}
