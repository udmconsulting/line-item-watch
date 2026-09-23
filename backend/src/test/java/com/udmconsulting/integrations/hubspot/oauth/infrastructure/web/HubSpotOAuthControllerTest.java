package com.udmconsulting.integrations.hubspot.oauth.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.udmconsulting.integrations.hubspot.oauth.application.HubSpotInstallationUseCase;
import com.udmconsulting.integrations.hubspot.oauth.application.OAuthStateException;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class HubSpotOAuthControllerTest {

    private static final String VALID_STATE = "a".repeat(43);

    private StubInstallationUseCase service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = new StubInstallationUseCase();
        mvc = MockMvcBuilders.standaloneSetup(new HubSpotOAuthController(service))
                .setControllerAdvice(new OAuthErrorHandler())
                .addFilters(new OAuthResponseSecurityHeaderFilter())
                .build();
    }

    @Test
    void installRedirectsWithSecurityHeaders() throws Exception {
        URI authorizationUri = URI.create("https://app.hubspot.test/oauth/authorize?state=safe-state");
        service.start = new HubSpotInstallationUseCase.InstallationStart(
                authorizationUri, UUID.randomUUID());

        ResultActions result = mvc.perform(get("/integrations/hubspot/oauth/install"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", authorizationUri.toString()));

        assertSecure(result);
    }

    @Test
    void callbackReturnsFixedSafeSuccessPage() throws Exception {
        ResultActions result = mvc.perform(get("/integrations/hubspot/oauth/callback")
                        .queryParam("code", "authorization-code")
                        .queryParam("state", VALID_STATE))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(OAuthHttpResponses.SUCCESS_HTML));

        assertSecure(result);
        assertThat(service.completedState).isEqualTo(VALID_STATE);
        assertThat(service.completedCode).isEqualTo("authorization-code");
    }

    @Test
    void missingBlankAndOversizedStateAreRejectedBeforeOrchestration() throws Exception {
        for (String state : new String[] {null, " ", "a".repeat(44)}) {
            var request = get("/integrations/hubspot/oauth/callback").queryParam("code", "code");
            if (state != null) {
                request.queryParam("state", state);
            }
            ResultActions result = mvc.perform(request)
                    .andExpect(status().isBadRequest())
                    .andExpect(content().string(OAuthHttpResponses.ERROR_HTML));
            assertSecure(result);
        }
        assertThat(service.completedState).isNull();
        assertThat(service.rejectedState).isNull();
    }

    @Test
    void invalidCodeAndErrorCombinationsConsumeStateWithoutExchange() throws Exception {
        var requests = java.util.List.of(
                get("/integrations/hubspot/oauth/callback").queryParam("state", VALID_STATE),
                get("/integrations/hubspot/oauth/callback")
                        .queryParam("state", VALID_STATE).queryParam("code", " "),
                get("/integrations/hubspot/oauth/callback")
                        .queryParam("state", VALID_STATE).queryParam("error", " "),
                get("/integrations/hubspot/oauth/callback")
                        .queryParam("state", VALID_STATE)
                        .queryParam("code", "code").queryParam("error", "denied"),
                get("/integrations/hubspot/oauth/callback")
                        .queryParam("state", VALID_STATE).queryParam("code", "c".repeat(2049)),
                get("/integrations/hubspot/oauth/callback")
                        .queryParam("state", VALID_STATE).queryParam("error", "e".repeat(257)));

        for (var request : requests) {
            service.rejectedState = null;
            ResultActions result = mvc.perform(request)
                    .andExpect(status().isBadRequest())
                    .andExpect(content().string(OAuthHttpResponses.ERROR_HTML));
            assertSecure(result);
            assertThat(service.rejectedState).isEqualTo(VALID_STATE);
        }
        assertThat(service.completedCode).isNull();
    }

    @Test
    void providerDenialConsumesStateAndDoesNotReflectProviderValues() throws Exception {
        ResultActions result = mvc.perform(get("/integrations/hubspot/oauth/callback")
                        .queryParam("state", VALID_STATE)
                        .queryParam("error", "provider-secret-error")
                        .queryParam("error_description", "attacker-controlled-description"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(OAuthHttpResponses.ERROR_HTML))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("provider-secret-error"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("attacker-controlled-description"))));

        assertSecure(result);
        assertThat(service.rejectedState).isEqualTo(VALID_STATE);
        assertThat(service.completedCode).isNull();
    }

    @Test
    void knownAndUnexpectedFailuresReturnFixedSafePages() throws Exception {
        service.failure = new OAuthStateException(OAuthStateException.Reason.REPLAYED);
        ResultActions known = mvc.perform(get("/integrations/hubspot/oauth/callback")
                        .queryParam("code", "authorization-code")
                        .queryParam("state", VALID_STATE))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(OAuthHttpResponses.ERROR_HTML));
        assertSecure(known);

        service.failure = new IllegalStateException("internal-sensitive-message");
        ResultActions unexpected = mvc.perform(get("/integrations/hubspot/oauth/callback")
                        .queryParam("code", "authorization-code")
                        .queryParam("state", VALID_STATE))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(OAuthHttpResponses.ERROR_HTML))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("internal-sensitive-message"))));
        assertSecure(unexpected);
    }

    private static void assertSecure(ResultActions result) throws Exception {
        result.andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string(
                        "Content-Security-Policy", OAuthHttpResponses.CONTENT_SECURITY_POLICY));
    }

    private static final class StubInstallationUseCase implements HubSpotInstallationUseCase {
        private InstallationStart start;
        private RuntimeException failure;
        private String completedState;
        private String completedCode;
        private String rejectedState;

        @Override
        public InstallationStart beginInstallation() {
            if (failure != null) {
                throw failure;
            }
            return start;
        }

        @Override
        public CompletedInstallation completeInstallation(String state, String authorizationCode) {
            if (failure != null) {
                throw failure;
            }
            completedState = state;
            completedCode = authorizationCode;
            return null;
        }

        @Override
        public void rejectInstallation(String state) {
            rejectedState = state;
            if (failure != null) {
                throw failure;
            }
        }
    }
}
