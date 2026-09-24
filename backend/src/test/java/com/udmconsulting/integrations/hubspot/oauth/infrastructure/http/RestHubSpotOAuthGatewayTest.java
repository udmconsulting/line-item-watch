package com.udmconsulting.integrations.hubspot.oauth.infrastructure.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

import com.udmconsulting.integrations.hubspot.config.HubSpotOAuthProperties;
import com.udmconsulting.integrations.hubspot.oauth.application.HubSpotInactiveAccessTokenException;
import com.udmconsulting.integrations.hubspot.oauth.application.HubSpotOAuthException;
import com.udmconsulting.integrations.hubspot.oauth.application.HubSpotOAuthFailureCategory;
import com.udmconsulting.integrations.hubspot.oauth.application.HubSpotProviderUnavailableException;
import com.udmconsulting.integrations.hubspot.oauth.application.HubSpotTokenMetadataException;
import com.udmconsulting.integrations.hubspot.oauth.application.InvalidRefreshCredentialException;
import java.net.URI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.ResourceAccessException;
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
    void tokenIssuanceDoesNotRequireOrUseHubIdOrScopes() {
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
                        {"access_token":"transient","refresh_token":"refresh","expires_in":1800,
                         "token_type":"bearer","token_use":"access_token"}
                        """, MediaType.APPLICATION_JSON));

        var result = gateway.exchangeAuthorizationCode("authorization-code");

        assertThat(result.accessToken()).isEqualTo("transient");
        assertThat(result.refreshToken()).isEqualTo("refresh");
        server.verify();
    }

    @Test
    void introspectsAtCurrentEndpointWithCompleteFormAndAllowsAdditionalScopes() {
        LinkedMultiValueMap<String, String> expected = new LinkedMultiValueMap<>();
        expected.add("client_id", "client-id");
        expected.add("client_secret", "client-secret");
        expected.add("token", "transient-access");
        expected.add("token_type_hint", "access_token");
        server.expect(requestTo("https://api.hubapi.test/oauth/2026-09/token/introspect"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formData(expected))
                .andRespond(withSuccess(validIntrospection(
                        "client-id", "12345", "crm.objects.contacts.read"), MediaType.APPLICATION_JSON));

        var result = gateway.introspectAccessToken("transient-access");

        assertThat(result.externalAccountId()).isEqualTo("12345");
        assertThat(result.grantedScopes())
                .containsAll(HubSpotOAuthProperties.REQUIRED_SCOPES)
                .contains("crm.objects.contacts.read");
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
    void refreshReturnsLatestCredentialWithoutUsingOptionalMetadata() {
        server.expect(requestTo("https://api.hubapi.test/oauth/2026-09/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"access_token":"transient","refresh_token":"replacement","expires_in":1800,
                         "token_type":"bearer","token_use":"access_token"}
                        """, MediaType.APPLICATION_JSON));

        var result = gateway.refresh("old-refresh");

        assertThat(result.accessToken()).isEqualTo("transient");
        assertThat(result.replacementRefreshToken()).contains("replacement");
        server.verify();
    }

    @Test
    void inactiveIntrospectionIsAuthoritativelyUnusable() {
        expectIntrospection("""
                {"active":false,"token_use":"access_token","token_type":"bearer",
                 "client_id":"client-id","hub_id":12345,"scopes":[]}
                """);

        assertThatThrownBy(() -> gateway.introspectAccessToken("access"))
                .isInstanceOfSatisfying(HubSpotInactiveAccessTokenException.class,
                        exception -> assertThat(exception.category())
                                .isEqualTo(HubSpotOAuthFailureCategory.TOKEN_INTROSPECTION_REJECTED));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"active\":\"true\",\"token_use\":\"access_token\",\"token_type\":\"bearer\",\"client_id\":\"client-id\",\"hub_id\":12345,\"scopes\":[]}",
            "{\"active\":true,\"token_use\":\"refresh_token\",\"token_type\":\"bearer\",\"client_id\":\"client-id\",\"hub_id\":12345,\"scopes\":[]}",
            "{\"active\":true,\"token_use\":\"access_token\",\"token_type\":\"bearer\",\"client_id\":\"wrong-client\",\"hub_id\":12345,\"scopes\":[]}",
            "{\"active\":true,\"token_use\":\"access_token\",\"token_type\":\"bearer\",\"client_id\":\"client-id\",\"scopes\":[]}",
            "{\"active\":true,\"token_use\":\"access_token\",\"token_type\":\"bearer\",\"client_id\":\"client-id\",\"hub_id\":12345,\"scopes\":\"scope\"}"
    })
    void rejectsMalformedOrMismatchedIntrospectionMetadata(String response) {
        expectIntrospection(response);

        assertThatThrownBy(() -> gateway.introspectAccessToken("access"))
                .isInstanceOfSatisfying(HubSpotTokenMetadataException.class,
                        exception -> assertThat(exception.category())
                                .isEqualTo(HubSpotOAuthFailureCategory.TOKEN_METADATA_INVALID));
    }

    @Test
    void distinguishesMalformedTokenResponse() {
        server.expect(requestTo("https://api.hubapi.test/oauth/2026-09/token"))
                .andRespond(withSuccess("{\"refresh_token\":\"refresh\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.exchangeAuthorizationCode("code"))
                .isInstanceOfSatisfying(HubSpotOAuthException.class,
                        exception -> assertThat(exception.category())
                                .isEqualTo(HubSpotOAuthFailureCategory.TOKEN_RESPONSE_INVALID));
    }

    @Test
    void rejectsIssuanceWithInvalidAccessTokenSemantics() {
        server.expect(requestTo("https://api.hubapi.test/oauth/2026-09/token"))
                .andRespond(withSuccess("""
                        {"access_token":"transient","refresh_token":"refresh","expires_in":1800,
                         "token_type":"bearer","token_use":"refresh_token"}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.exchangeAuthorizationCode("code"))
                .isInstanceOfSatisfying(HubSpotOAuthException.class,
                        exception -> assertThat(exception.category())
                                .isEqualTo(HubSpotOAuthFailureCategory.TOKEN_RESPONSE_INVALID));
    }

    @Test
    void distinguishesTokenExchangeRejection() {
        server.expect(requestTo("https://api.hubapi.test/oauth/2026-09/token"))
                .andRespond(withUnauthorizedRequest());

        assertThatThrownBy(() -> gateway.exchangeAuthorizationCode("sensitive-code"))
                .isInstanceOfSatisfying(HubSpotOAuthException.class, exception -> {
                    assertThat(exception.category())
                            .isEqualTo(HubSpotOAuthFailureCategory.TOKEN_EXCHANGE_REJECTED);
                    assertThat(exception).hasMessageNotContaining("sensitive-code");
                });
    }

    @Test
    void distinguishesIntrospectionRejection() {
        server.expect(requestTo("https://api.hubapi.test/oauth/2026-09/token/introspect"))
                .andRespond(withUnauthorizedRequest());

        assertThatThrownBy(() -> gateway.introspectAccessToken("access"))
                .isInstanceOfSatisfying(HubSpotOAuthException.class,
                        exception -> assertThat(exception.category())
                                .isEqualTo(HubSpotOAuthFailureCategory.TOKEN_INTROSPECTION_REJECTED));
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 500, 503})
    void mapsIntrospectionRateLimitsAndServerFailuresAsUnavailable(int status) {
        server.expect(requestTo("https://api.hubapi.test/oauth/2026-09/token/introspect"))
                .andRespond(withStatus(HttpStatus.valueOf(status)));

        assertThatThrownBy(() -> gateway.introspectAccessToken("access"))
                .isInstanceOfSatisfying(HubSpotProviderUnavailableException.class,
                        exception -> assertThat(exception.category()).isEqualTo(
                                HubSpotOAuthFailureCategory.TOKEN_INTROSPECTION_PROVIDER_UNAVAILABLE));
    }

    @Test
    void mapsIntrospectionConnectivityFailureAsUnavailableWithoutSensitiveCause() {
        String accessToken = "sensitive-access-token";
        String clientSecret = "sensitive-client-secret";
        server.expect(requestTo("https://api.hubapi.test/oauth/2026-09/token/introspect"))
                .andRespond(request -> {
                    throw new ResourceAccessException(accessToken + clientSecret);
                });

        assertThatThrownBy(() -> gateway.introspectAccessToken(accessToken))
                .isInstanceOfSatisfying(HubSpotProviderUnavailableException.class, exception -> {
                    assertThat(exception.category()).isEqualTo(
                            HubSpotOAuthFailureCategory.TOKEN_INTROSPECTION_PROVIDER_UNAVAILABLE);
                    assertThat(exception)
                            .hasMessageNotContaining(accessToken)
                            .hasMessageNotContaining(clientSecret);
                    assertThat(exception.getCause()).isNull();
                });
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

    private void expectIntrospection(String response) {
        server.expect(requestTo("https://api.hubapi.test/oauth/2026-09/token/introspect"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }

    private static String validIntrospection(String clientId, String hubId, String additionalScope) {
        return """
                {"active":true,"token_use":"access_token","token_type":"bearer",
                 "client_id":"%s","hub_id":%s,
                 "scopes":["crm.objects.deals.read","crm.objects.line_items.read","%s"]}
                """.formatted(clientId, hubId, additionalScope);
    }
}
