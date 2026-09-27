package com.udmconsulting.integrations.hubspot.webhook;

import com.udmconsulting.integrations.hubspot.webhook.HubSpotWebhookBatchParser.ParsedBatch;
import com.udmconsulting.modules.lineitemwatch.application.CaptureLineItemChangeSignals;
import com.udmconsulting.modules.lineitemwatch.application.LineItemChangeSignalStore.CaptureResult;
import com.udmconsulting.modules.lineitemwatch.domain.LineItemChangeSignal;
import com.udmconsulting.platform.connection.application.PlatformConnectionService;
import com.udmconsulting.platform.connection.domain.ConnectionStatus;
import com.udmconsulting.platform.connection.domain.ExternalAccountId;
import com.udmconsulting.platform.connection.domain.PlatformConnection;
import com.udmconsulting.platform.connection.domain.Provider;
import com.udmconsulting.platform.entitlement.application.EntitlementService;
import com.udmconsulting.platform.module.domain.ProductModule;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "hubspot.webhook", name = "enabled", havingValue = "true")
final class HubSpotWebhookIngressService {

    private static final Logger LOGGER = LoggerFactory.getLogger(HubSpotWebhookIngressService.class);

    private final HubSpotV3SignatureVerifier signatureVerifier;
    private final HubSpotWebhookBatchParser batchParser;
    private final PlatformConnectionService connectionService;
    private final EntitlementService entitlementService;
    private final CaptureLineItemChangeSignals captureSignals;
    private final Clock clock;

    HubSpotWebhookIngressService(
            HubSpotV3SignatureVerifier signatureVerifier,
            HubSpotWebhookBatchParser batchParser,
            PlatformConnectionService connectionService,
            EntitlementService entitlementService,
            CaptureLineItemChangeSignals captureSignals,
            Clock clock) {
        this.signatureVerifier = Objects.requireNonNull(signatureVerifier);
        this.batchParser = Objects.requireNonNull(batchParser);
        this.connectionService = Objects.requireNonNull(connectionService);
        this.entitlementService = Objects.requireNonNull(entitlementService);
        this.captureSignals = Objects.requireNonNull(captureSignals);
        this.clock = Objects.requireNonNull(clock);
    }

    IngestionResult ingest(
            String method,
            String signatureHeader,
            String timestampHeader,
            byte[] rawBody) {
        Instant receivedAt = clock.instant();
        signatureVerifier.verify(method, signatureHeader, timestampHeader, rawBody);
        ParsedBatch parsed = batchParser.parse(rawBody);

        int ignored = parsed.ignoredEvents();
        Map<PlatformConnection, List<NormalizedHubSpotLineItemEvent>> groups = new LinkedHashMap<>();
        Map<String, PlatformConnection> resolvedPortals = new LinkedHashMap<>();
        for (NormalizedHubSpotLineItemEvent event : parsed.supportedEvents()) {
            PlatformConnection connection;
            if (resolvedPortals.containsKey(event.portalId())) {
                connection = resolvedPortals.get(event.portalId());
            } else {
                connection = connectionService.resolve(
                                Provider.HUBSPOT, new ExternalAccountId(event.portalId()))
                        .orElse(null);
                resolvedPortals.put(event.portalId(), connection);
            }
            if (connection == null
                    || connection.status() != ConnectionStatus.ACTIVE
                    || !entitlementService.isEnabled(
                            connection.tenantId(), ProductModule.LINE_ITEM_WATCH)) {
                ignored++;
                continue;
            }
            groups.computeIfAbsent(connection, ignoredKey -> new ArrayList<>()).add(event);
        }

        int captured = 0;
        int duplicates = 0;
        for (Map.Entry<PlatformConnection, List<NormalizedHubSpotLineItemEvent>> entry
                : groups.entrySet()) {
            PlatformConnection connection = entry.getKey();
            List<LineItemChangeSignal> signals = entry.getValue().stream()
                    .map(event -> event.routeTo(
                            connection.tenantId(), connection.id(), receivedAt))
                    .toList();
            CaptureResult result = captureSignals.capture(
                    connection.tenantId(), connection.id(), signals);
            if (!result.eligible()) {
                ignored += signals.size();
                continue;
            }
            captured += result.captured();
            duplicates += result.duplicates();
            LOGGER.info(
                    "HubSpot webhook signal group captured tenantId={} connectionId={} "
                            + "events={} captured={} duplicates={}",
                    connection.tenantId().value(),
                    connection.id().value(),
                    signals.size(),
                    result.captured(),
                    result.duplicates());
        }

        IngestionResult result = new IngestionResult(
                parsed.totalEvents(), captured, duplicates, ignored);
        LOGGER.info(
                "HubSpot webhook batch completed events={} captured={} duplicates={} ignored={}",
                result.events(), result.captured(), result.duplicates(), result.ignored());
        return result;
    }

    record IngestionResult(int events, int captured, int duplicates, int ignored) {
    }
}
