package com.udmconsulting.integrations.hubspot.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.udmconsulting.integrations.hubspot.config.HubSpotWebhookProperties;
import com.udmconsulting.platform.supportability.CorrelationFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.DelegatingServletInputStream;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class HubSpotWebhookControllerTest {

    private HubSpotWebhookIngressService service;
    private HubSpotWebhookController controller;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(HubSpotWebhookIngressService.class);
        controller = new HubSpotWebhookController(
                service, new HubSpotWebhookMetrics(new SimpleMeterRegistry()));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new CorrelationFilter())
                .build();
    }

    @Test
    void disabledReceiverDoesNotCreateController() {
        new ApplicationContextRunner()
                .withUserConfiguration(HubSpotWebhookController.class)
                .withPropertyValues("hubspot.webhook.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(HubSpotWebhookController.class));
    }

    @Test
    void validJsonWithCharsetReturnsEmpty204AndIgnoresForwardedHeaders() throws Exception {
        byte[] body = "[{\"eventId\":1}]".getBytes(StandardCharsets.UTF_8);
        when(service.ingest(anyString(), anyString(), anyString(), any(byte[].class)))
                .thenReturn(new HubSpotWebhookIngressService.IngestionResult(1, 1, 0, 0));

        mvc.perform(post(HubSpotWebhookProperties.ENDPOINT_PATH)
                        .contentType("application/json;charset=UTF-8")
                        .header(HubSpotWebhookController.SIGNATURE_HEADER, "signature")
                        .header(HubSpotWebhookController.TIMESTAMP_HEADER, "timestamp")
                        .header("Forwarded", "host=attacker.example;proto=http")
                        .header("X-Forwarded-Host", "attacker.example")
                        .content(body))
                .andExpect(status().isNoContent())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().exists(CorrelationFilter.HEADER))
                .andExpect(content().string(""));

        verify(service).ingest("POST", "signature", "timestamp", body);
    }

    @Test
    void mapsAuthenticationPayloadDatabaseAndInternalFailuresToEmptyResponses() throws Exception {
        when(service.ingest(anyString(), any(), any(), any(byte[].class)))
                .thenThrow(new HubSpotWebhookAuthenticationException("sensitive"))
                .thenThrow(new HubSpotWebhookPayloadException("sensitive"))
                .thenThrow(new TransientDataAccessResourceException("sensitive"))
                .thenThrow(new IllegalStateException("sensitive"));

        for (int expected : new int[] {401, 400, 503, 500}) {
            mvc.perform(post(HubSpotWebhookProperties.ENDPOINT_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("[]"))
                    .andExpect(status().is(expected))
                    .andExpect(content().string(""));
        }
    }

    @Test
    void rejectsWrongMediaTypeAndUnsupportedContentEncoding() throws Exception {
        mvc.perform(post(HubSpotWebhookProperties.ENDPOINT_PATH)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("[]"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().string(""));
        mvc.perform(post(HubSpotWebhookProperties.ENDPOINT_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.CONTENT_ENCODING, "gzip")
                        .content("[]"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().string(""));
    }

    @Test
    void rejectsDeclaredAndChunkedOversizedBodies() throws Exception {
        byte[] oversized = new byte[HubSpotWebhookProperties.MAX_BODY_BYTES + 1];

        mvc.perform(post(HubSpotWebhookProperties.ENDPOINT_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversized))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(content().string(""));
        mvc.perform(post(HubSpotWebhookProperties.ENDPOINT_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.TRANSFER_ENCODING, "chunked")
                        .content(oversized))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(content().string(""));
    }

    @Test
    void nonPostMethodReturns405() throws Exception {
        mvc.perform(get(HubSpotWebhookProperties.ENDPOINT_PATH))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().string(""));
    }

    @Test
    void readsRequestBodyExactlyOnce() throws Exception {
        byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getContentType()).thenReturn(MediaType.APPLICATION_JSON_VALUE);
        when(request.getContentLengthLong()).thenReturn(-1L);
        when(request.getMethod()).thenReturn("POST");
        when(request.getHeader(HttpHeaders.CONTENT_ENCODING)).thenReturn(null);
        when(request.getHeader(HubSpotWebhookController.SIGNATURE_HEADER)).thenReturn("signature");
        when(request.getHeader(HubSpotWebhookController.TIMESTAMP_HEADER)).thenReturn("timestamp");
        when(request.getInputStream())
                .thenReturn(new DelegatingServletInputStream(new ByteArrayInputStream(body)));
        when(service.ingest("POST", "signature", "timestamp", body))
                .thenReturn(new HubSpotWebhookIngressService.IngestionResult(0, 0, 0, 0));

        assertThat(controller.receive(request).getStatusCode().value()).isEqualTo(204);
        verify(request, times(1)).getInputStream();
    }
}
