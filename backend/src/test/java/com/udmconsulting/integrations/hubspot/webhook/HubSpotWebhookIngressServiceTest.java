package com.udmconsulting.integrations.hubspot.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.udmconsulting.integrations.hubspot.webhook.HubSpotWebhookBatchParser.ParsedBatch;
import com.udmconsulting.modules.lineitemwatch.application.CaptureLineItemChangeSignals;
import com.udmconsulting.modules.lineitemwatch.application.LineItemChangeSignalStore.CaptureResult;
import com.udmconsulting.modules.lineitemwatch.domain.LineItemChangeSignal;
import com.udmconsulting.modules.lineitemwatch.domain.LineItemChangeSignalType;
import com.udmconsulting.modules.lineitemwatch.domain.ProviderDeduplicationKey;
import com.udmconsulting.platform.connection.application.PlatformConnectionService;
import com.udmconsulting.platform.connection.domain.ConnectionStatus;
import com.udmconsulting.platform.connection.domain.ExternalAccountId;
import com.udmconsulting.platform.connection.domain.PlatformConnection;
import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.connection.domain.Provider;
import com.udmconsulting.platform.entitlement.application.EntitlementService;
import com.udmconsulting.platform.module.domain.ProductModule;
import com.udmconsulting.platform.tenant.domain.TenantId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.TransientDataAccessResourceException;

class HubSpotWebhookIngressServiceTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-09-27T12:00:00Z");

    private HubSpotV3SignatureVerifier verifier;
    private HubSpotWebhookBatchParser parser;
    private PlatformConnectionService connections;
    private EntitlementService entitlements;
    private CaptureLineItemChangeSignals capture;
    private HubSpotWebhookIngressService service;

    @BeforeEach
    void setUp() {
        verifier = mock(HubSpotV3SignatureVerifier.class);
        parser = mock(HubSpotWebhookBatchParser.class);
        connections = mock(PlatformConnectionService.class);
        entitlements = mock(EntitlementService.class);
        capture = mock(CaptureLineItemChangeSignals.class);
        service = new HubSpotWebhookIngressService(
                verifier,
                parser,
                connections,
                entitlements,
                capture,
                Clock.fixed(RECEIVED_AT, ZoneOffset.UTC));
    }

    @Test
    void authenticatesBeforeParsingAndRoutesAnEligiblePortalWithCapturedProvenance() {
        byte[] body = "[]".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        PlatformConnection connection = connection("101", ConnectionStatus.ACTIVE);
        NormalizedHubSpotLineItemEvent event = event("101", "event-1");
        when(parser.parse(body)).thenReturn(new ParsedBatch(List.of(event), 0, 1));
        when(connections.resolve(Provider.HUBSPOT, new ExternalAccountId("101")))
                .thenReturn(Optional.of(connection));
        when(entitlements.isEnabled(connection.tenantId(), ProductModule.LINE_ITEM_WATCH))
                .thenReturn(true);
        when(capture.capture(eq(connection.tenantId()), eq(connection.id()), any()))
                .thenReturn(new CaptureResult(1, 0, true));

        HubSpotWebhookIngressService.IngestionResult result =
                service.ingest("POST", "signature", "timestamp", body);

        assertThat(result).isEqualTo(new HubSpotWebhookIngressService.IngestionResult(1, 1, 0, 0));
        verify(verifier).verify("POST", "signature", "timestamp", body);
        ArgumentCaptor<List<LineItemChangeSignal>> signals = ArgumentCaptor.forClass(List.class);
        verify(capture).capture(eq(connection.tenantId()), eq(connection.id()), signals.capture());
        assertThat(signals.getValue()).singleElement().satisfies(signal -> {
            assertThat(signal.tenantId()).isEqualTo(connection.tenantId());
            assertThat(signal.connectionId()).isEqualTo(connection.id());
            assertThat(signal.receivedAt()).isEqualTo(RECEIVED_AT);
            assertThat(signal.providerEventId()).isEqualTo("event-1");
        });
    }

    @Test
    void authenticationFailureStopsBeforePayloadParsingOrRouting() {
        byte[] body = "not-json".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        org.mockito.Mockito.doThrow(new HubSpotWebhookAuthenticationException("invalid"))
                .when(verifier).verify("POST", "signature", "timestamp", body);

        assertThatThrownBy(() -> service.ingest("POST", "signature", "timestamp", body))
                .isInstanceOf(HubSpotWebhookAuthenticationException.class);

        verify(parser, never()).parse(any());
        verify(connections, never()).resolve(any(), any());
        verify(capture, never()).capture(any(), any(), any());
    }

    @Test
    void unknownInactiveReauthenticationAndUnentitledPortalsAreIgnored() {
        PlatformConnection disconnected = connection("disconnected", ConnectionStatus.DISCONNECTED);
        PlatformConnection reauth = connection("reauth", ConnectionStatus.REAUTH_REQUIRED);
        PlatformConnection unentitled = connection("unentitled", ConnectionStatus.ACTIVE);
        List<NormalizedHubSpotLineItemEvent> events = List.of(
                event("unknown", "1"),
                event("disconnected", "2"),
                event("reauth", "3"),
                event("unentitled", "4"));
        when(parser.parse(any())).thenReturn(new ParsedBatch(events, 1, 5));
        when(connections.resolve(Provider.HUBSPOT, new ExternalAccountId("unknown")))
                .thenReturn(Optional.empty());
        when(connections.resolve(Provider.HUBSPOT, new ExternalAccountId("disconnected")))
                .thenReturn(Optional.of(disconnected));
        when(connections.resolve(Provider.HUBSPOT, new ExternalAccountId("reauth")))
                .thenReturn(Optional.of(reauth));
        when(connections.resolve(Provider.HUBSPOT, new ExternalAccountId("unentitled")))
                .thenReturn(Optional.of(unentitled));
        when(entitlements.isEnabled(unentitled.tenantId(), ProductModule.LINE_ITEM_WATCH))
                .thenReturn(false);

        HubSpotWebhookIngressService.IngestionResult result =
                service.ingest("POST", "signature", "timestamp", new byte[] {1});

        assertThat(result).isEqualTo(new HubSpotWebhookIngressService.IngestionResult(5, 0, 0, 5));
        verify(capture, never()).capture(any(), any(), any());
        verify(entitlements, never()).isEnabled(disconnected.tenantId(), ProductModule.LINE_ITEM_WATCH);
        verify(entitlements, never()).isEnabled(reauth.tenantId(), ProductModule.LINE_ITEM_WATCH);
    }

    @Test
    void groupsEachConnectionIntoItsOwnCommitAndTreatsCommitGuardLossAsIgnored() {
        PlatformConnection first = connection("first", ConnectionStatus.ACTIVE);
        PlatformConnection second = connection("second", ConnectionStatus.ACTIVE);
        when(parser.parse(any())).thenReturn(new ParsedBatch(
                List.of(event("first", "1"), event("first", "2"), event("second", "3")),
                0,
                3));
        when(connections.resolve(Provider.HUBSPOT, new ExternalAccountId("first")))
                .thenReturn(Optional.of(first));
        when(connections.resolve(Provider.HUBSPOT, new ExternalAccountId("second")))
                .thenReturn(Optional.of(second));
        when(entitlements.isEnabled(any(), eq(ProductModule.LINE_ITEM_WATCH))).thenReturn(true);
        when(capture.capture(eq(first.tenantId()), eq(first.id()), any()))
                .thenReturn(new CaptureResult(1, 1, true));
        when(capture.capture(eq(second.tenantId()), eq(second.id()), any()))
                .thenReturn(CaptureResult.ineligible());

        HubSpotWebhookIngressService.IngestionResult result =
                service.ingest("POST", "signature", "timestamp", new byte[] {1});

        assertThat(result).isEqualTo(new HubSpotWebhookIngressService.IngestionResult(3, 1, 1, 1));
        verify(capture).capture(eq(first.tenantId()), eq(first.id()), any());
        verify(capture).capture(eq(second.tenantId()), eq(second.id()), any());
    }

    @Test
    void databaseFailureFromAnyConnectionIsSurfacedForRetry() {
        PlatformConnection first = connection("first", ConnectionStatus.ACTIVE);
        PlatformConnection second = connection("second", ConnectionStatus.ACTIVE);
        when(parser.parse(any())).thenReturn(new ParsedBatch(
                List.of(event("first", "1"), event("second", "2")), 0, 2));
        when(connections.resolve(any(), eq(new ExternalAccountId("first"))))
                .thenReturn(Optional.of(first));
        when(connections.resolve(any(), eq(new ExternalAccountId("second"))))
                .thenReturn(Optional.of(second));
        when(entitlements.isEnabled(any(), any())).thenReturn(true);
        when(capture.capture(eq(first.tenantId()), eq(first.id()), any()))
                .thenReturn(new CaptureResult(1, 0, true));
        when(capture.capture(eq(second.tenantId()), eq(second.id()), any()))
                .thenThrow(new TransientDataAccessResourceException("database unavailable"));

        assertThatThrownBy(() -> service.ingest("POST", "signature", "timestamp", new byte[] {1}))
                .isInstanceOf(TransientDataAccessResourceException.class);

        verify(capture).capture(eq(first.tenantId()), eq(first.id()), any());
        verify(capture).capture(eq(second.tenantId()), eq(second.id()), any());
    }

    private static PlatformConnection connection(String account, ConnectionStatus status) {
        return new PlatformConnection(
                PlatformConnectionId.newId(),
                TenantId.newId(),
                Provider.HUBSPOT,
                new ExternalAccountId(account),
                status);
    }

    private static NormalizedHubSpotLineItemEvent event(String portalId, String eventId) {
        byte[] dedup = new byte[32];
        dedup[0] = (byte) eventId.hashCode();
        return new NormalizedHubSpotLineItemEvent(
                portalId,
                eventId,
                "subscription-1",
                "line-1",
                LineItemChangeSignalType.CREATED,
                Instant.parse("2026-09-27T11:59:00Z"),
                null,
                null,
                null,
                null,
                null,
                null,
                new ProviderDeduplicationKey(dedup));
    }
}
