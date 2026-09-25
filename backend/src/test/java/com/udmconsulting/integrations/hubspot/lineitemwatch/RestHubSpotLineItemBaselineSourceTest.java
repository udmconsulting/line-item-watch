package com.udmconsulting.integrations.hubspot.lineitemwatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.udmconsulting.integrations.hubspot.config.HubSpotOAuthProperties;
import com.udmconsulting.modules.lineitemwatch.application.BaselineSyncException;
import com.udmconsulting.modules.lineitemwatch.domain.BillingStart;
import com.udmconsulting.modules.lineitemwatch.domain.ProviderObjectId;
import com.udmconsulting.platform.connection.domain.ConnectionStatus;
import com.udmconsulting.platform.connection.domain.ExternalAccountId;
import com.udmconsulting.platform.connection.domain.PlatformConnection;
import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.connection.domain.Provider;
import com.udmconsulting.platform.tenant.domain.TenantId;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class RestHubSpotLineItemBaselineSourceTest {

    private static final String BASE_URL = "https://api.hubapi.test";
    private static final String ACCESS_TOKEN = "sensitive-access-token";
    private final PlatformConnection connection = new PlatformConnection(
            PlatformConnectionId.newId(),
            TenantId.newId(),
            Provider.HUBSPOT,
            new ExternalAccountId("149377304"),
            ConnectionStatus.ACTIVE);
    private MockRestServiceServer server;
    private RestHubSpotLineItemBaselineSource source;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        source = new RestHubSpotLineItemBaselineSource(
                builder,
                new JsonMapper(),
                new HubSpotOAuthProperties(
                        "client-id",
                        "client-secret",
                        URI.create("http://localhost:8080/callback"),
                        URI.create(BASE_URL),
                        URI.create("https://app.hubspot.test/oauth/authorize"),
                        null,
                        null),
                ignored -> ACCESS_TOKEN,
                Clock.fixed(Instant.parse("2026-09-25T10:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void readsCurrentPropertiesAndCompleteAssociationsWithoutCalculatedFields() {
        expectGet("/crm/objects/2026-09/deals/521984899298", """
                {"id":"521984899298","archived":false}
                """);
        expectAssociationPage(
                "/crm/associations/2026-09/deals/line_items/batch/read",
                "521984899298",
                "486464823492");
        String properties = String.join(",", RestHubSpotLineItemBaselineSource.REQUESTED_PROPERTIES);
        expectGet(
                "/crm/objects/2026-09/line_items/486464823492?properties=" + properties,
                """
                {
                  "id":"486464823492",
                  "createdAt":"2026-09-01T09:00:00Z",
                  "updatedAt":"2026-09-20T11:30:00Z",
                  "archived":false,
                  "properties":{
                    "name":"Implementation",
                    "quantity":"10.2500",
                    "price":"200.00",
                    "discount":"5.00",
                    "hs_discount_percentage":"10",
                    "recurringbillingfrequency":"monthly",
                    "hs_recurring_billing_start_date":null,
                    "hs_billing_start_delay_days":"14",
                    "hs_billing_start_delay_months":null,
                    "hs_recurring_billing_period":"P12M"
                  }
                }
                """);
        expectAssociationPage(
                "/crm/associations/2026-09/line_items/deals/batch/read",
                "486464823492",
                "521984899298");

        var result = source.readDeal(connection, new ProviderObjectId("521984899298"));

        assertThat(result.lineItems()).hasSize(1);
        var item = result.lineItems().getFirst();
        assertThat(item.lineItemId()).isEqualTo(new ProviderObjectId("486464823492"));
        assertThat(item.associatedDealIds())
                .containsExactly(new ProviderObjectId("521984899298"));
        assertThat(item.unitPrice()).isEqualByComparingTo("200.00");
        assertThat(item.billingStart().delayUnit()).isEqualTo(BillingStart.DelayUnit.DAYS);
        assertThat(item.billingStart().delayCount()).isEqualTo(14);
        assertThat(item.recurringPeriod().canonicalValue()).isEqualTo("P12M");
        assertThat(item.observedAt()).isEqualTo("2026-09-25T10:00:00Z");
        assertThat(RestHubSpotLineItemBaselineSource.REQUESTED_PROPERTIES)
                .doesNotContain("hs_billing_start_delay_type", "hs_line_item_currency_code");
        server.verify();
    }

    @Test
    void acceptsIntegralNumericObjectIdsAcrossCrmObjectsAndBothAssociationDirections() {
        expectGet("/crm/objects/2026-09/deals/1", """
                {"id":1,"archived":false}
                """);
        expectAssociationPageWithWireIds(
                "/crm/associations/2026-09/deals/line_items/batch/read", "1", "1", "2");
        expectLineItemWithWireId("2", "2", "{}", "2026-09-20T11:30:00Z");
        expectAssociationPageWithWireIds(
                "/crm/associations/2026-09/line_items/deals/batch/read", "2", "2", "1");

        var result = source.readDeal(connection, new ProviderObjectId("1"));

        assertThat(result.lineItems()).singleElement().satisfies(item -> {
            assertThat(item.lineItemId()).isEqualTo(new ProviderObjectId("2"));
            assertThat(item.associatedDealIds()).containsExactly(new ProviderObjectId("1"));
        });
        server.verify();
    }

    @Test
    void preservesLargeIntegralAssociationIdWithoutFloatingPointConversion() {
        String largeLineItemId = "9007199254740993";
        expectGet("/crm/objects/2026-09/deals/1", """
                {"id":"1","archived":false}
                """);
        expectAssociationPageWithWireIds(
                "/crm/associations/2026-09/deals/line_items/batch/read",
                "1",
                "\"1\"",
                largeLineItemId);
        expectLineItem(largeLineItemId, "{}", "2026-09-20T11:30:00Z");
        expectAssociationPage(
                "/crm/associations/2026-09/line_items/deals/batch/read",
                largeLineItemId,
                "1");

        var result = source.readDeal(connection, new ProviderObjectId("1"));

        assertThat(result.lineItems())
                .extracting(item -> item.lineItemId().value())
                .containsExactly(largeLineItemId);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2.0", "2.5", "null", "true", "{}", "[]", "\" \""})
    void rejectsNonIntegralOrArbitraryDealAssociationTargetIds(String invalidWireId) {
        expectGet("/crm/objects/2026-09/deals/1", """
                {"id":"1","archived":false}
                """);
        expectAssociationPageWithWireIds(
                "/crm/associations/2026-09/deals/line_items/batch/read",
                "1",
                "\"1\"",
                invalidWireId);

        assertSanitizedAssociationFailure(false, invalidWireId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.0", "1.5", "null", "false", "{}", "[]", "\" \""})
    void rejectsNonIntegralOrArbitraryLineItemAssociationTargetIds(String invalidWireId) {
        expectGet("/crm/objects/2026-09/deals/1", """
                {"id":"1","archived":false}
                """);
        expectAssociationPage(
                "/crm/associations/2026-09/deals/line_items/batch/read", "1", "2");
        expectLineItem("2", "{}", "2026-09-20T11:30:00Z");
        expectAssociationPageWithWireIds(
                "/crm/associations/2026-09/line_items/deals/batch/read",
                "2",
                "\"2\"",
                invalidWireId);

        assertSanitizedAssociationFailure(false, invalidWireId);
    }

    @Test
    void rejectsPartialMultiStatusFromLineItemToDealReadWithoutLeakingDetails() {
        expectGet("/crm/objects/2026-09/deals/1", """
                {"id":"1","archived":false}
                """);
        expectAssociationPage(
                "/crm/associations/2026-09/deals/line_items/batch/read", "1", "2");
        expectLineItem("2", "{}", "2026-09-20T11:30:00Z");
        server.expect(requestTo(BASE_URL
                        + "/crm/associations/2026-09/line_items/deals/batch/read"))
                .andRespond(withStatus(HttpStatus.MULTI_STATUS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"status":"COMPLETE","results":[{"from":{"id":"2"},
                                  "to":[{"toObjectId":"1"}]}],"numErrors":1,
                                  "errors":[{"message":"sensitive-provider-detail"}]}
                                """));

        assertSanitizedAssociationFailure(true, "sensitive-provider-detail");
    }

    @Test
    void rejectsNonEmptyAssociationErrors() {
        expectGet("/crm/objects/2026-09/deals/1", """
                {"id":"1","archived":false}
                """);
        expectAssociationResponse("""
                {"status":"COMPLETE","results":[],"numErrors":0,
                 "errors":[{"message":"sensitive-provider-detail"}]}
                """);

        assertSanitizedAssociationFailure(true, "sensitive-provider-detail");
    }

    @Test
    void rejectsPositiveAssociationErrorCount() {
        expectGet("/crm/objects/2026-09/deals/1", """
                {"id":"1","archived":false}
                """);
        expectAssociationResponse("""
                {"status":"COMPLETE","results":[],"numErrors":1,"errors":[]}
                """);

        assertSanitizedAssociationFailure(true, "numErrors");
    }

    @Test
    void rejectsIncompleteAssociationStatus() {
        expectGet("/crm/objects/2026-09/deals/1", """
                {"id":"1","archived":false}
                """);
        expectAssociationResponse("""
                {"status":"PROCESSING","results":[],"numErrors":0,"errors":[]}
                """);

        assertSanitizedAssociationFailure(true, "PROCESSING");
    }

    @Test
    void rejectsMalformedAssociationEnvelopeFromDealToLineItemRead() {
        expectGet("/crm/objects/2026-09/deals/1", """
                {"id":"1","archived":false}
                """);
        expectAssociationResponse("""
                {"status":"COMPLETE","results":[],"numErrors":"zero","errors":{}}
                """);

        assertSanitizedAssociationFailure(false, "zero");
    }

    @Test
    void rejectsMalformedAssociationStatus() {
        expectGet("/crm/objects/2026-09/deals/1", """
                {"id":"1","archived":false}
                """);
        expectAssociationResponse("""
                {"status":7,"results":[],"numErrors":0,"errors":[]}
                """);

        assertSanitizedAssociationFailure(false, "status");
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 500, 503})
    void mapsRetryableProviderFailuresWithoutLeakingToken(int status) {
        server.expect(requestTo(BASE_URL + "/crm/objects/2026-09/deals/1"))
                .andRespond(withStatus(HttpStatus.valueOf(status)));

        assertThatThrownBy(() -> source.readDeal(connection, new ProviderObjectId("1")))
                .isInstanceOfSatisfying(BaselineSyncException.class, exception -> {
                    assertThat(exception.retryable()).isTrue();
                    assertThat(exception).hasMessageNotContaining(ACCESS_TOKEN);
                });
    }

    @Test
    void rejectsConflictingDirectBillingStartInputs() {
        expectGet("/crm/objects/2026-09/deals/1", """
                {"id":"1","archived":false}
                """);
        expectAssociationPage(
                "/crm/associations/2026-09/deals/line_items/batch/read", "1", "2");
        String properties = String.join(",", RestHubSpotLineItemBaselineSource.REQUESTED_PROPERTIES);
        expectGet("/crm/objects/2026-09/line_items/2?properties=" + properties, """
                {"id":"2","createdAt":"2026-09-01T09:00:00Z",
                 "updatedAt":"2026-09-20T11:30:00Z","archived":false,
                 "properties":{"hs_recurring_billing_start_date":"2026-10-01",
                               "hs_billing_start_delay_days":"2"}}
                """);
        expectAssociationPage(
                "/crm/associations/2026-09/line_items/deals/batch/read", "2", "1");

        assertThatThrownBy(() -> source.readDeal(connection, new ProviderObjectId("1")))
                .isInstanceOfSatisfying(BaselineSyncException.class,
                        exception -> assertThat(exception.retryable()).isFalse());
    }

    @Test
    void rejectsMalformedDecimal() {
        expectMalformedLineItem("{\"quantity\":\"not-a-decimal\"}",
                "2026-09-20T11:30:00Z", "not-a-decimal");
    }

    @Test
    void rejectsMalformedDate() {
        expectMalformedLineItem(
                "{\"hs_recurring_billing_start_date\":\"not-a-date\"}",
                "2026-09-20T11:30:00Z", "not-a-date");
    }

    @Test
    void rejectsMalformedRecurringPeriod() {
        expectMalformedLineItem(
                "{\"hs_recurring_billing_period\":\"P1Q\"}",
                "2026-09-20T11:30:00Z", "P1Q");
    }

    @Test
    void rejectsMalformedProviderTimestamp() {
        expectMalformedLineItem("{}", "not-a-timestamp", "not-a-timestamp");
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void mapsAuthorizationFailuresAsTerminalWithoutLeakingToken(int status) {
        server.expect(requestTo(BASE_URL + "/crm/objects/2026-09/deals/1"))
                .andRespond(withStatus(HttpStatus.valueOf(status)));

        assertThatThrownBy(() -> source.readDeal(connection, new ProviderObjectId("1")))
                .isInstanceOfSatisfying(BaselineSyncException.class, exception -> {
                    assertThat(exception.retryable()).isFalse();
                    assertThat(exception).hasMessageNotContaining(ACCESS_TOKEN);
                });
    }

    @Test
    void mapsMissingRequestedDealAsTerminal() {
        server.expect(requestTo(BASE_URL + "/crm/objects/2026-09/deals/1"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> source.readDeal(connection, new ProviderObjectId("1")))
                .isInstanceOfSatisfying(BaselineSyncException.class,
                        exception -> assertThat(exception.retryable()).isFalse());
    }

    @Test
    void mapsMissingAssociatedLineItemAsRetryableConcurrentChange() {
        expectGet("/crm/objects/2026-09/deals/1", """
                {"id":"1","archived":false}
                """);
        expectAssociationPage(
                "/crm/associations/2026-09/deals/line_items/batch/read", "1", "2");
        String properties = String.join(",", RestHubSpotLineItemBaselineSource.REQUESTED_PROPERTIES);
        server.expect(requestTo(BASE_URL
                        + "/crm/objects/2026-09/line_items/2?properties=" + properties))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> source.readDeal(connection, new ProviderObjectId("1")))
                .isInstanceOfSatisfying(BaselineSyncException.class,
                        exception -> assertThat(exception.retryable()).isTrue());
    }

    @Test
    void followsAssociationPaginationUsingTheReturnedAfterToken() {
        expectGet("/crm/objects/2026-09/deals/1", """
                {"id":"1","archived":false}
                """);
        server.expect(requestTo(BASE_URL
                        + "/crm/associations/2026-09/deals/line_items/batch/read"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"inputs":[{"id":"1"}]}
                        """, JsonCompareMode.STRICT))
                .andRespond(withSuccess("""
                        {"status":"COMPLETE","numErrors":0,"errors":[],
                         "results":[{"from":{"id":"1"},"to":[],
                          "paging":{"next":{"after":"next-page","link":"ignored"}}}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL
                        + "/crm/associations/2026-09/deals/line_items/batch/read"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"inputs":[{"id":"1","after":"next-page"}]}
                        """, JsonCompareMode.STRICT))
                .andRespond(withSuccess("""
                        {"status":"COMPLETE","numErrors":0,"errors":[],
                         "results":[{"from":{"id":"1"},"to":[]}]}
                        """, MediaType.APPLICATION_JSON));

        var result = source.readDeal(connection, new ProviderObjectId("1"));

        assertThat(result.lineItems()).isEmpty();
        server.verify();
    }

    @Test
    void rejectsMalformedProviderObjectsAsTerminal() {
        expectGet("/crm/objects/2026-09/deals/1", """
                {"id":"1"}
                """);

        assertThatThrownBy(() -> source.readDeal(connection, new ProviderObjectId("1")))
                .isInstanceOfSatisfying(BaselineSyncException.class,
                        exception -> assertThat(exception.retryable()).isFalse());
    }

    @Test
    void mapsNetworkFailureAsRetryableWithoutLeakingToken() {
        server.expect(requestTo(BASE_URL + "/crm/objects/2026-09/deals/1"))
                .andRespond(request -> {
                    throw new ResourceAccessException("simulated timeout");
                });

        assertThatThrownBy(() -> source.readDeal(connection, new ProviderObjectId("1")))
                .isInstanceOfSatisfying(BaselineSyncException.class, exception -> {
                    assertThat(exception.retryable()).isTrue();
                    assertThat(exception).hasMessageNotContaining(ACCESS_TOKEN);
                });
    }

    private void expectGet(String path, String response) {
        server.expect(requestTo(BASE_URL + path))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }

    private void expectAssociationResponse(String response) {
        server.expect(requestTo(BASE_URL
                        + "/crm/associations/2026-09/deals/line_items/batch/read"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }

    private void expectLineItem(String lineItemId, String properties, String updatedAt) {
        expectLineItemWithWireId(
                lineItemId, "\"" + lineItemId + "\"", properties, updatedAt);
    }

    private void expectLineItemWithWireId(
            String lineItemId, String wireId, String properties, String updatedAt) {
        String requested = String.join(",", RestHubSpotLineItemBaselineSource.REQUESTED_PROPERTIES);
        expectGet("/crm/objects/2026-09/line_items/" + lineItemId + "?properties=" + requested,
                """
                {"id":%s,"createdAt":"2026-09-01T09:00:00Z",
                 "updatedAt":"%s","archived":false,"properties":%s}
                """.formatted(wireId, updatedAt, properties));
    }

    private void expectMalformedLineItem(
            String properties, String updatedAt, String sensitiveValue) {
        expectGet("/crm/objects/2026-09/deals/1", """
                {"id":"1","archived":false}
                """);
        expectAssociationPage(
                "/crm/associations/2026-09/deals/line_items/batch/read", "1", "2");
        expectLineItem("2", properties, updatedAt);
        expectAssociationPage(
                "/crm/associations/2026-09/line_items/deals/batch/read", "2", "1");

        assertThatThrownBy(() -> source.readDeal(connection, new ProviderObjectId("1")))
                .isInstanceOfSatisfying(BaselineSyncException.class, exception -> {
                    assertThat(exception.retryable()).isFalse();
                    assertThat(exception)
                            .hasMessageNotContaining(ACCESS_TOKEN)
                            .hasMessageNotContaining(sensitiveValue);
                });
        server.verify();
    }

    private void assertSanitizedAssociationFailure(
            boolean retryable, String sensitiveValue) {
        assertThatThrownBy(() -> source.readDeal(connection, new ProviderObjectId("1")))
                .isInstanceOfSatisfying(BaselineSyncException.class, exception -> {
                    assertThat(exception.retryable()).isEqualTo(retryable);
                    assertThat(exception)
                            .hasMessageNotContaining(ACCESS_TOKEN)
                            .hasMessageNotContaining(sensitiveValue);
                });
        server.verify();
    }

    private void expectAssociationPage(String path, String sourceId, String targetId) {
        expectAssociationPageWithWireIds(
                path, sourceId, "\"" + sourceId + "\"", "\"" + targetId + "\"");
    }

    private void expectAssociationPageWithWireIds(
            String path, String sourceId, String wireSourceId, String wireTargetId) {
        server.expect(requestTo(BASE_URL + path))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
                .andRespond(withSuccess("""
                        {"status":"COMPLETE","numErrors":0,"errors":[],
                         "results":[{"from":{"id":%s},
                          "to":[{"toObjectId":%s,"associationTypes":[]}]}]}
                        """.formatted(wireSourceId, wireTargetId), MediaType.APPLICATION_JSON));
    }
}
