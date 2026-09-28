package com.udmconsulting.integrations.hubspot.lineitemwatch.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.udmconsulting.integrations.hubspot.authentication.HubSpotV3RequestSignatureVerifier;
import com.udmconsulting.integrations.hubspot.config.HubSpotOAuthProperties;
import com.udmconsulting.integrations.hubspot.config.HubSpotUiExtensionProperties;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class HubSpotUiExtensionRequestAuthenticatorTest {

    private static final String SECRET = "synthetic-ui-extension-secret";
    private static final String ORIGIN = "https://api.example.test";
    private static final String PATH = "/api/v1/line-item-watch/deals/123/audit";
    private static final String QUERY =
            "eventsLimit=20&portalId=456&userId=789&userEmail=user%40example.test&appId=12345";
    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    @Test
    void authenticatesSignedAccountMetadataAndIgnoresForwardedHeaders() throws Exception {
        MockHttpServletRequest request = request(QUERY);
        request.addHeader("Forwarded", "host=attacker.example;proto=http");
        request.addHeader("X-Forwarded-Host", "attacker.example");
        sign(request, QUERY);

        AuthenticatedHubSpotUiCaller caller = authenticator().authenticate(request);

        assertThat(caller.externalAccountId().value()).isEqualTo("456");
    }

    @Test
    void rejectsChangedQueryWrongAppDuplicateMetadataAndBody() throws Exception {
        MockHttpServletRequest changed = request(QUERY);
        sign(changed, QUERY.replace("eventsLimit=20", "eventsLimit=10"));
        assertThatThrownBy(() -> authenticator().authenticate(changed))
                .isInstanceOf(HubSpotUiExtensionAuthenticationException.class);

        String wrongApp = QUERY.replace("appId=12345", "appId=99999");
        MockHttpServletRequest app = request(wrongApp);
        sign(app, wrongApp);
        assertThatThrownBy(() -> authenticator().authenticate(app))
                .isInstanceOf(HubSpotUiExtensionAuthenticationException.class);

        MockHttpServletRequest duplicate = request(QUERY);
        duplicate.addParameter("portalId", "999");
        sign(duplicate, QUERY);
        assertThatThrownBy(() -> authenticator().authenticate(duplicate))
                .isInstanceOf(HubSpotUiExtensionAuthenticationException.class);

        MockHttpServletRequest body = request(QUERY);
        body.setContent("not-empty".getBytes(StandardCharsets.UTF_8));
        sign(body, QUERY);
        assertThatThrownBy(() -> authenticator().authenticate(body))
                .isInstanceOf(HubSpotUiExtensionAuthenticationException.class);
    }

    @Test
    void rejectsUnknownOversizedAndDuplicateSignedInputs() throws Exception {
        String unknownQuery = QUERY + "&unexpected=value";
        MockHttpServletRequest unknown = request(unknownQuery);
        sign(unknown, unknownQuery);
        assertThatThrownBy(() -> authenticator().authenticate(unknown))
                .isInstanceOf(HubSpotUiExtensionAuthenticationException.class);

        MockHttpServletRequest oversized = request(QUERY);
        oversized.setQueryString("x".repeat(4097));
        sign(oversized, QUERY);
        assertThatThrownBy(() -> authenticator().authenticate(oversized))
                .isInstanceOf(HubSpotUiExtensionAuthenticationException.class);

        MockHttpServletRequest headers = request(QUERY);
        sign(headers, QUERY);
        headers.addHeader(HubSpotUiExtensionRequestAuthenticator.SIGNATURE_HEADER,
                Base64.getEncoder().encodeToString(new byte[32]));
        assertThatThrownBy(() -> authenticator().authenticate(headers))
                .isInstanceOf(HubSpotUiExtensionAuthenticationException.class);

        MockHttpServletRequest oversizedHeader = request(QUERY);
        oversizedHeader.addHeader(HubSpotUiExtensionRequestAuthenticator.SIGNATURE_HEADER,
                "A".repeat(129));
        oversizedHeader.addHeader(HubSpotUiExtensionRequestAuthenticator.TIMESTAMP_HEADER,
                Long.toString(NOW.toEpochMilli()));
        assertThatThrownBy(() -> authenticator().authenticate(oversizedHeader))
                .isInstanceOf(HubSpotUiExtensionAuthenticationException.class);
    }

    @Test
    void canonicalizationDecodesOnlyDocumentedQueryCharacters() {
        assertThat(HubSpotUiExtensionRequestAuthenticator.decodeForV3Signature(
                "email=user%40example.test&value=a%2Fb%26c%3Dd"))
                .isEqualTo("email=user@example.test&value=a/b%26c%3Dd");
    }

    @Test
    void acceptsSingleEncodedPlusRawPlusAndPercentWithoutDoubleDecoding() throws Exception {
        for (String email : new String[] {
                "user%2Btag%40example.test",
                "user+tag%40example.test",
                "user%252Ftag%40example.test"
        }) {
            String query = QUERY.replace("user%40example.test", email);
            MockHttpServletRequest request = request(query);
            sign(request, query);

            assertThat(authenticator().authenticate(request).externalAccountId().value())
                    .isEqualTo("456");
        }
    }

    @Test
    void rejectsMalformedUtf8EncodedNamesAndWrongMethod() throws Exception {
        String malformedQuery = QUERY.replace("user%40example.test", "user%C3%28example.test");
        MockHttpServletRequest malformed = request(malformedQuery);
        sign(malformed, malformedQuery);
        assertThatThrownBy(() -> authenticator().authenticate(malformed))
                .isInstanceOf(HubSpotUiExtensionAuthenticationException.class);

        String encodedNameQuery = QUERY.replace("portalId", "%70ortalId");
        MockHttpServletRequest encodedName = request(encodedNameQuery);
        sign(encodedName, encodedNameQuery);
        assertThatThrownBy(() -> authenticator().authenticate(encodedName))
                .isInstanceOf(HubSpotUiExtensionAuthenticationException.class);

        MockHttpServletRequest post = request(QUERY);
        post.setMethod("POST");
        sign(post, QUERY);
        assertThatThrownBy(() -> authenticator().authenticate(post))
                .isInstanceOf(HubSpotUiExtensionAuthenticationException.class);
    }

    private static MockHttpServletRequest request(String query) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", PATH);
        request.setQueryString(query);
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            String value = java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            request.addParameter(parts[0], value);
        }
        return request;
    }

    private static void sign(MockHttpServletRequest request, String signedQuery) throws Exception {
        String timestamp = Long.toString(NOW.toEpochMilli());
        String uri = ORIGIN + PATH + "?"
                + HubSpotUiExtensionRequestAuthenticator.decodeForV3Signature(signedQuery);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update("GET".getBytes(StandardCharsets.UTF_8));
        mac.update(uri.getBytes(StandardCharsets.UTF_8));
        mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
        request.addHeader(HubSpotUiExtensionRequestAuthenticator.SIGNATURE_HEADER,
                Base64.getEncoder().encodeToString(mac.doFinal()));
        request.addHeader(HubSpotUiExtensionRequestAuthenticator.TIMESTAMP_HEADER, timestamp);
    }

    private static HubSpotUiExtensionRequestAuthenticator authenticator() {
        HubSpotOAuthProperties oauth = new HubSpotOAuthProperties(
                "client-id",
                SECRET,
                URI.create("http://localhost:8080/callback"),
                URI.create("http://localhost:9999"),
                URI.create("https://app.hubspot.com/oauth/authorize"),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1));
        return new HubSpotUiExtensionRequestAuthenticator(
                new HubSpotUiExtensionProperties(true, URI.create(ORIGIN), "12345"),
                new HubSpotV3RequestSignatureVerifier(
                        oauth, Clock.fixed(NOW, ZoneOffset.UTC)));
    }
}
