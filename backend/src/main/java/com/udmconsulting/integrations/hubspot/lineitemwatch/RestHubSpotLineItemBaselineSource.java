package com.udmconsulting.integrations.hubspot.lineitemwatch;

import com.udmconsulting.integrations.hubspot.config.HubSpotOAuthProperties;
import com.udmconsulting.integrations.hubspot.oauth.application.HubSpotAccessTokenProvider;
import com.udmconsulting.modules.lineitemwatch.application.BaselineSyncException;
import com.udmconsulting.modules.lineitemwatch.application.DealLineItemObservations;
import com.udmconsulting.modules.lineitemwatch.application.LineItemBaselineSource;
import com.udmconsulting.modules.lineitemwatch.domain.BillingStart;
import com.udmconsulting.modules.lineitemwatch.domain.LineItemObservation;
import com.udmconsulting.modules.lineitemwatch.domain.ProviderObjectId;
import com.udmconsulting.modules.lineitemwatch.domain.RecurringPeriod;
import com.udmconsulting.platform.connection.domain.PlatformConnection;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public final class RestHubSpotLineItemBaselineSource implements LineItemBaselineSource {

    static final String DEAL_PATH = "/crm/objects/2026-09/deals/%s";
    static final String LINE_ITEM_PATH = "/crm/objects/2026-09/line_items/%s";
    static final String DEAL_LINE_ITEMS_PATH =
            "/crm/associations/2026-09/deals/line_items/batch/read";
    static final String LINE_ITEM_DEALS_PATH =
            "/crm/associations/2026-09/line_items/deals/batch/read";

    static final List<String> REQUESTED_PROPERTIES = List.of(
            "name",
            "quantity",
            "price",
            "discount",
            "hs_discount_percentage",
            "recurringbillingfrequency",
            "hs_recurring_billing_start_date",
            "hs_billing_start_delay_days",
            "hs_billing_start_delay_months",
            "hs_recurring_billing_period");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final Function<PlatformConnection, String> accessTokenResolver;
    private final Clock clock;

    @Autowired
    public RestHubSpotLineItemBaselineSource(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            HubSpotOAuthProperties properties,
            HubSpotAccessTokenProvider accessTokenProvider,
            Clock clock) {
        this.restClient = restClientBuilder.baseUrl(properties.apiBaseUrl().toString()).build();
        this.objectMapper = objectMapper;
        this.accessTokenResolver = connection ->
                accessTokenProvider.accessTokenFor(connection).accessToken();
        this.clock = clock;
    }

    RestHubSpotLineItemBaselineSource(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            HubSpotOAuthProperties properties,
            Function<PlatformConnection, String> accessTokenResolver,
            Clock clock) {
        this.restClient = restClientBuilder.baseUrl(properties.apiBaseUrl().toString()).build();
        this.objectMapper = objectMapper;
        this.accessTokenResolver = accessTokenResolver;
        this.clock = clock;
    }

    @Override
    public DealLineItemObservations readDeal(
            PlatformConnection connection, ProviderObjectId dealId) {
        String accessToken = accessTokenResolver.apply(connection);
        JsonNode deal = get(
                DEAL_PATH.formatted(pathSegment(dealId.value())),
                accessToken,
                "HubSpot Deal was not found",
                false);
        validateObjectIdentity(deal, dealId, "Deal");

        Set<ProviderObjectId> lineItemIds = readAssociations(
                DEAL_LINE_ITEMS_PATH, dealId, accessToken);
        List<LineItemObservation> observations = new ArrayList<>(lineItemIds.size());
        for (ProviderObjectId lineItemId : lineItemIds) {
            JsonNode lineItem = get(
                    lineItemPath(lineItemId),
                    accessToken,
                    "Associated HubSpot Line Item was not found",
                    true);
            validateObjectIdentity(lineItem, lineItemId, "Line Item");
            Set<ProviderObjectId> dealIds = readAssociations(
                    LINE_ITEM_DEALS_PATH, lineItemId, accessToken);
            if (!dealIds.contains(dealId)) {
                throw new BaselineSyncException(
                        "Provider state changed while the Deal baseline was read", true);
            }
            observations.add(toObservation(lineItem, lineItemId, dealIds));
        }
        return new DealLineItemObservations(dealId, observations);
    }

    private JsonNode get(
            String path,
            String accessToken,
            String notFoundMessage,
            boolean changedWhenNotFound) {
        try {
            return restClient.get()
                    .uri(path)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .accept(MediaType.APPLICATION_JSON)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (response.getStatusCode().is2xxSuccessful()) {
                            return readBody(response.getBody());
                        }
                        if (status == 404) {
                            throw new BaselineSyncException(
                                    notFoundMessage, changedWhenNotFound);
                        }
                        throw classify(status);
                    });
        } catch (ResourceAccessException exception) {
            throw new BaselineSyncException("HubSpot baseline read was unavailable", true);
        }
    }

    private Set<ProviderObjectId> readAssociations(
            String path, ProviderObjectId sourceId, String accessToken) {
        LinkedHashSet<ProviderObjectId> ids = new LinkedHashSet<>();
        LinkedHashSet<String> seenCursors = new LinkedHashSet<>();
        String after = null;
        do {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("id", sourceId.value());
            if (after != null) {
                input.put("after", after);
            }
            Map<String, Object> body = Map.of("inputs", List.of(input));
            JsonNode response = post(path, accessToken, body);
            validateAssociationEnvelope(response);
            JsonNode results = response.get("results");
            if (results == null || !results.isArray()) {
                throw contractFailure();
            }
            JsonNode sourceResult = null;
            for (JsonNode result : results) {
                String returnedSource = requiredHubSpotObjectId(result.path("from"), "id");
                if (sourceId.value().equals(returnedSource)) {
                    if (sourceResult != null) {
                        throw contractFailure();
                    }
                    sourceResult = result;
                }
            }
            if (sourceResult == null) {
                if (results.isEmpty()) {
                    return Set.copyOf(ids);
                }
                throw contractFailure();
            }
            JsonNode targets = sourceResult.get("to");
            if (targets == null || !targets.isArray()) {
                throw contractFailure();
            }
            for (JsonNode target : targets) {
                ids.add(new ProviderObjectId(requiredHubSpotObjectId(target, "toObjectId")));
            }
            after = optionalText(sourceResult.path("paging").path("next"), "after");
            if (after != null && !seenCursors.add(after)) {
                throw contractFailure();
            }
        } while (after != null);
        return Set.copyOf(ids);
    }

    private JsonNode post(String path, String accessToken, Object body) {
        try {
            return restClient.post()
                    .uri(path)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 200) {
                            return readBody(response.getBody());
                        }
                        if (response.getStatusCode().is2xxSuccessful()) {
                            throw incompleteAssociationFailure();
                        }
                        if (status == 404) {
                            throw new BaselineSyncException(
                                    "Provider state changed while the Deal baseline was read", true);
                        }
                        throw classify(status);
                    });
        } catch (ResourceAccessException exception) {
            throw new BaselineSyncException("HubSpot baseline read was unavailable", true);
        }
    }

    private static void validateAssociationEnvelope(JsonNode response) {
        String status = optionalText(response, "status");
        if (status == null) {
            throw contractFailure();
        }
        switch (status) {
            case "COMPLETE" -> { }
            case "PENDING", "PROCESSING", "CANCELED" -> throw incompleteAssociationFailure();
            default -> throw contractFailure();
        }

        JsonNode errors = response.get("errors");
        if (errors != null) {
            if (!errors.isArray()) {
                throw contractFailure();
            }
            if (!errors.isEmpty()) {
                throw incompleteAssociationFailure();
            }
        }

        JsonNode numErrors = response.get("numErrors");
        if (numErrors != null) {
            if (!numErrors.isIntegralNumber() || numErrors.longValue() < 0) {
                throw contractFailure();
            }
            if (numErrors.longValue() > 0) {
                throw incompleteAssociationFailure();
            }
        }
    }

    private LineItemObservation toObservation(
            JsonNode lineItem,
            ProviderObjectId lineItemId,
            Set<ProviderObjectId> dealIds) {
        JsonNode properties = lineItem.get("properties");
        if (properties == null || !properties.isObject()) {
            throw contractFailure();
        }
        try {
            LocalDate startDate = optionalDate(properties, "hs_recurring_billing_start_date");
            Integer delayDays = optionalInteger(properties, "hs_billing_start_delay_days");
            Integer delayMonths = optionalInteger(properties, "hs_billing_start_delay_months");
            BillingStart billingStart = billingStart(startDate, delayDays, delayMonths);
            String period = optionalText(properties, "hs_recurring_billing_period");
            return new LineItemObservation(
                    lineItemId,
                    optionalText(properties, "name"),
                    optionalDecimal(properties, "quantity"),
                    optionalDecimal(properties, "price"),
                    optionalDecimal(properties, "discount"),
                    optionalDecimal(properties, "hs_discount_percentage"),
                    optionalText(properties, "recurringbillingfrequency"),
                    billingStart,
                    period == null ? null : RecurringPeriod.parse(period),
                    requiredInstant(lineItem, "createdAt"),
                    requiredInstant(lineItem, "updatedAt"),
                    clock.instant(),
                    dealIds);
        } catch (IllegalArgumentException exception) {
            throw contractFailure();
        }
    }

    private static BillingStart billingStart(
            LocalDate startDate, Integer delayDays, Integer delayMonths) {
        int supplied = (startDate == null ? 0 : 1)
                + (delayDays == null ? 0 : 1)
                + (delayMonths == null ? 0 : 1);
        if (supplied > 1) {
            throw new IllegalArgumentException("conflicting billing start inputs");
        }
        if (startDate != null) {
            return BillingStart.on(startDate);
        }
        if (delayDays != null) {
            return BillingStart.after(BillingStart.DelayUnit.DAYS, delayDays);
        }
        if (delayMonths != null) {
            return BillingStart.after(BillingStart.DelayUnit.MONTHS, delayMonths);
        }
        return BillingStart.unspecified();
    }

    private void validateObjectIdentity(
            JsonNode object, ProviderObjectId expectedId, String objectName) {
        if (!expectedId.value().equals(requiredHubSpotObjectId(object, "id"))) {
            throw new BaselineSyncException(
                    "HubSpot returned an unexpected " + objectName + " identity", false);
        }
        JsonNode archived = object.get("archived");
        if (archived == null || !archived.isBoolean()) {
            throw contractFailure();
        }
        if (archived.booleanValue()) {
            throw new BaselineSyncException(
                    "Provider state changed while the Deal baseline was read", true);
        }
    }

    private String lineItemPath(ProviderObjectId lineItemId) {
        return LINE_ITEM_PATH.formatted(pathSegment(lineItemId.value()))
                + "?properties="
                + String.join(",", REQUESTED_PROPERTIES);
    }

    private JsonNode readBody(java.io.InputStream inputStream) {
        try {
            JsonNode body = objectMapper.readTree(inputStream);
            if (body == null || !body.isObject()) {
                throw contractFailure();
            }
            return body;
        } catch (JacksonException exception) {
            throw contractFailure();
        }
    }

    private static RuntimeException classify(int status) {
        if (status == 401 || status == 403) {
            return new BaselineSyncException("HubSpot baseline read was not authorized", false);
        }
        if (status == 429 || status >= 500) {
            return new BaselineSyncException("HubSpot baseline read was unavailable", true);
        }
        return contractFailure();
    }

    private static BigDecimal optionalDecimal(JsonNode object, String field) {
        String value = optionalText(object, field);
        return value == null ? null : new BigDecimal(value);
    }

    private static Integer optionalInteger(JsonNode object, String field) {
        String value = optionalText(object, field);
        if (value == null) {
            return null;
        }
        int parsed = Integer.parseInt(value);
        if (parsed < 0) {
            throw new IllegalArgumentException("negative integer");
        }
        return parsed;
    }

    private static LocalDate optionalDate(JsonNode object, String field) {
        String value = optionalText(object, field);
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            long epochMilliseconds = Long.parseLong(value);
            return Instant.ofEpochMilli(epochMilliseconds)
                    .atZone(ZoneOffset.UTC)
                    .toLocalDate();
        }
    }

    private static Instant requiredInstant(JsonNode object, String field) {
        String value = requiredText(object, field);
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("invalid timestamp");
        }
    }

    private static String requiredText(JsonNode object, String field) {
        String value = optionalText(object, field);
        if (value == null) {
            throw contractFailure();
        }
        return value;
    }

    private static String requiredHubSpotObjectId(JsonNode object, String field) {
        if (object == null || object.isMissingNode()) {
            throw contractFailure();
        }
        JsonNode value = object.get(field);
        if (value == null || value.isNull()) {
            throw contractFailure();
        }

        String decoded;
        if (value.isString()) {
            decoded = value.stringValue().trim();
        } else if (value.isIntegralNumber()) {
            decoded = value.bigIntegerValue().toString();
        } else {
            throw contractFailure();
        }
        if (decoded.isEmpty()) {
            throw contractFailure();
        }
        return pathSegment(decoded);
    }

    private static String optionalText(JsonNode object, String field) {
        if (object == null || object.isMissingNode()) {
            return null;
        }
        JsonNode value = object.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            throw contractFailure();
        }
        String text = value.stringValue().trim();
        return text.isEmpty() ? null : text;
    }

    private static String pathSegment(String value) {
        if (!value.matches("[1-9][0-9]*")) {
            throw new BaselineSyncException("HubSpot object ID is invalid", false);
        }
        return value;
    }

    private static BaselineSyncException contractFailure() {
        return new BaselineSyncException("HubSpot returned an invalid baseline response", false);
    }

    private static BaselineSyncException incompleteAssociationFailure() {
        return new BaselineSyncException(
                "HubSpot baseline association read was incomplete", true);
    }
}
