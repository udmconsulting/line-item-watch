package com.udmconsulting.integrations.hubspot.oauth.infrastructure.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

import com.udmconsulting.integrations.hubspot.config.HubSpotOAuthProperties;
import com.udmconsulting.integrations.hubspot.oauth.application.InvalidRefreshCredentialException;
import java.net.URI;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class RestHubSpotOAuthGatewayTest {

    private MockRestServiceServer server;
    private RestHubSpotOAuthGateway gateway;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        gateway = new RestHubSpotOAuthGateway(builder, new JsonMapper(), new HubSpotOAuthProperties(
                "client-id",
                "client-secret",
                URI.create("http://localhost:8080/integrations/hubspot/oauth/callback"),
                URI.create("https://api.hubapi.test"),
                URI.create("https://app.hubspot.test/oauth/authorize"),
                null,
                null));
    }

    @Test
    void exchangesAuthorizationCodeAtCurrentEndpointWithFormEncoding() {
        LinkedMultiValueMap<String, String> expected = new LinkedMultiValueMap<>();
        expected.add("grant_type", "authorization_code");
        expected.add("client_id", "client-id");
        expected.add("client_secret", "client-secret");
        expected.add("code", "authorization-code");
        expected.add("redirect_uri", "http://localhost:8080/integrations/hubspot/oauth/callback");
        server.expect(requestTo("https://api.hubapi.test/oauth/2026-09/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formData(expected))
                .andRespond(withSuccess("""
                        {"access_token":"transient","refresh_token":"refresh","hub_id":12345,
                         "scopes":["crm.objects.deals.read","crm.objects.line_items.read"]}
                        """, MediaType.APPLICATION_JSON));

        var result = gateway.exchangeAuthorizationCode("authorization-code");

        assertThat(result.externalAccountId()).isEqualTo("12345");
        assertThat(result.refreshToken()).isEqualTo("refresh");
        assertThat(result.grantedScopes()).containsExactlyInAnyOrderElementsOf(
                HubSpotOAuthProperties.REQUIRED_SCOPES);
        server.verify();
    }

    @Test
    void refreshUsesCurrentEndpointAndMapsOnlyConfirmedInvalidGrant() {
        server.expect(requestTo("https://api.hubapi.test/oauth/2026-09/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withUnauthorizedRequest().body("{\"error\":\"invalid_grant\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.refresh("revoked-refresh"))
                .isInstanceOf(InvalidRefreshCredentialException.class);
        server.verify();
    }

    @Test
    void refreshReturnsOptionalReplacementWithoutPersistingAccessToken() {
        server.expect(requestTo("https://api.hubapi.test/oauth/2026-09/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"access_token":"transient","refresh_token":"replacement","hub_id":12345,
                         "scopes":"crm.objects.deals.read crm.objects.line_items.read"}
                        """, MediaType.APPLICATION_JSON));

        var result = gateway.refresh("old-refresh");

        assertThat(result.accessToken()).isEqualTo("transient");
        assertThat(result.replacementRefreshToken()).contains("replacement");
        assertThat(result.grantedScopes()).isEqualTo(Set.copyOf(HubSpotOAuthProperties.REQUIRED_SCOPES));
        server.verify();
    }

    @Test
    void revocationAndUninstallUseCurrentEndpoints() {
        LinkedMultiValueMap<String, String> revokeForm = new LinkedMultiValueMap<>();
        revokeForm.add("client_id", "client-id");
        revokeForm.add("client_secret", "client-secret");
        revokeForm.add("token", "refresh");
        revokeForm.add("token_type_hint", "refresh_token");
        server.expect(requestTo("https://api.hubapi.test/oauth/2026-09/token/revoke"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formData(revokeForm))
                .andRespond(withNoContent());
        server.expect(requestTo("https://api.hubapi.test/appinstalls/2026-09/external-install"))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(header("Authorization", "Bearer transient-access"))
                .andRespond(withNoContent());

        gateway.revoke("refresh");
        gateway.uninstall("transient-access");

        server.verify();
    }
}
