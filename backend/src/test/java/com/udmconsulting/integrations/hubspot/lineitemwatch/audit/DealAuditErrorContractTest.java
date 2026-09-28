package com.udmconsulting.integrations.hubspot.lineitemwatch.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import com.udmconsulting.platform.connection.domain.ExternalAccountId;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DealAuditErrorContractTest {

    private static final String PATH = "/api/v1/line-item-watch/deals/1001/audit";

    @Test
    void authenticationErrorContainsOnlyStableCodeAndCorrelationId() throws Exception {
        HubSpotUiExtensionRequestAuthenticator authenticator =
                mock(HubSpotUiExtensionRequestAuthenticator.class);
        when(authenticator.authenticate(any()))
                .thenThrow(new HubSpotUiExtensionAuthenticationException(
                        "signature contained private technical detail"));
        TestContext context = context(authenticator, mock(HubSpotDealAuditReadService.class));

        MvcResult result = context.mvc().perform(get(PATH)
                        .header("X-Correlation-ID", "caller-controlled-correlation")
                        .queryParam("portalId", "456")
                        .queryParam("userId", "789")
                        .queryParam("userEmail", "user@example.test")
                        .queryParam("appId", "12345"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_FAILED"))
                .andExpect(jsonPath("$.error.correlationId").isNotEmpty())
                .andExpect(jsonPath("$.error.message").doesNotExist())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        String correlationId = correlationId(body);
        assertThat(correlationId).isNotEqualTo("caller-controlled-correlation");
        assertThat(body).doesNotContain("signature", "technical detail", "message");
        assertThat(context.logs().list)
                .extracting(event -> event.getFormattedMessage())
                .anySatisfy(message -> assertThat(message).contains(correlationId))
                .allSatisfy(message -> assertThat(message)
                        .doesNotContain("1001", "456", "789", "user@example.test", "12345"));
        context.close();
    }

    @Test
    void requestAccountAndInternalFailuresUseOnlyTheirStableEnvelope() throws Exception {
        HubSpotUiExtensionRequestAuthenticator authenticator =
                mock(HubSpotUiExtensionRequestAuthenticator.class);
        when(authenticator.authenticate(any())).thenReturn(
                new AuthenticatedHubSpotUiCaller(new ExternalAccountId("456")));

        TestContext invalid = context(authenticator, mock(HubSpotDealAuditReadService.class));
        invalid.mvc().perform(get(PATH).queryParam("lineItemsLimit", "21"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.correlationId").isNotEmpty())
                .andExpect(jsonPath("$.error.message").doesNotExist());
        invalid.close();

        HubSpotDealAuditReadService unavailableService = mock(HubSpotDealAuditReadService.class);
        when(unavailableService.read(any(), any(), any())).thenThrow(new AccountUnavailableException());
        TestContext unavailable = context(authenticator, unavailableService);
        unavailable.mvc().perform(get(PATH))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_UNAVAILABLE"))
                .andExpect(jsonPath("$.error.correlationId").isNotEmpty())
                .andExpect(jsonPath("$.error.message").doesNotExist());
        unavailable.close();

        HubSpotDealAuditReadService invariantService = mock(HubSpotDealAuditReadService.class);
        when(invariantService.read(any(), any(), any()))
                .thenThrow(new AccountResolutionInvariantException());
        TestContext invariant = context(authenticator, invariantService);
        invariant.mvc().perform(get(PATH))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.error.correlationId").isNotEmpty())
                .andExpect(jsonPath("$.error.message").doesNotExist());
        invariant.close();
    }

    @Test
    void databaseDetailsNeverAppearInServiceUnavailableError() throws Exception {
        HubSpotUiExtensionRequestAuthenticator authenticator =
                mock(HubSpotUiExtensionRequestAuthenticator.class);
        HubSpotDealAuditReadService readService = mock(HubSpotDealAuditReadService.class);
        when(authenticator.authenticate(any())).thenReturn(
                new AuthenticatedHubSpotUiCaller(new ExternalAccountId("456")));
        when(readService.read(any(), any(), any())).thenThrow(
                new DataAccessResourceFailureException(
                        "jdbc:postgresql://secret-host/customer portal=456 deal=1001"));
        TestContext context = context(authenticator, readService);

        MvcResult result = context.mvc().perform(get(PATH)
                        .queryParam("portalId", "456")
                        .queryParam("userId", "789")
                        .queryParam("userEmail", "user@example.test")
                        .queryParam("appId", "12345"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.error.correlationId").isNotEmpty())
                .andExpect(jsonPath("$.error.message").doesNotExist())
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("postgresql", "secret-host", "portal", "deal", "message");
        assertThat(context.logs().list)
                .extracting(event -> event.getFormattedMessage())
                .allSatisfy(message -> assertThat(message)
                        .doesNotContain("secret-host", "customer", "1001", "456"));
        context.close();
    }

    private static TestContext context(
            HubSpotUiExtensionRequestAuthenticator authenticator,
            HubSpotDealAuditReadService readService) {
        DealAuditCursorCodec codec = new DealAuditCursorCodec();
        HubSpotDealAuditController controller = new HubSpotDealAuditController(
                authenticator, new DealAuditQueryFactory(codec), readService, codec);
        Logger logger = (Logger) LoggerFactory.getLogger(DealAuditErrorHandler.class);
        ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new DealAuditErrorHandler())
                .addFilters(new DealAuditCorrelationFilter())
                .build();
        return new TestContext(mvc, logger, logs);
    }

    private static String correlationId(String body) {
        Matcher matcher = Pattern.compile("\\\"correlationId\\\":\\\"([^\\\"]+)\\\"").matcher(body);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private record TestContext(
            MockMvc mvc,
            Logger logger,
            ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs) {
        void close() {
            logger.detachAppender(logs);
            logs.stop();
        }
    }
}
