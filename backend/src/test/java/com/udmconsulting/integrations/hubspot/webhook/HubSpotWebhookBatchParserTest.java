package com.udmconsulting.integrations.hubspot.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.udmconsulting.modules.lineitemwatch.domain.AssociationAction;
import com.udmconsulting.modules.lineitemwatch.domain.LineItemChangeSignalType;
import com.udmconsulting.modules.lineitemwatch.domain.MonitoredLineItemProperty;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class HubSpotWebhookBatchParserTest {

    private HubSpotWebhookBatchParser parser;

    @BeforeEach
    void setUp() {
        parser = new HubSpotWebhookBatchParser(new JsonMapper());
    }

    @Test
    void parsesCreationAndDeletionWithTextualAndLargeIntegralIds() {
        var batch = parser.parse(array(
                objectEvent("object.creation", "\"line-text\"", "2000"),
                objectEvent(
                        "object.deletion",
                        "486464823492123456789012345678901234567890",
                        "1000")));

        assertThat(batch.ignoredEvents()).isZero();
        assertThat(batch.supportedEvents()).extracting(NormalizedHubSpotLineItemEvent::type)
                .containsExactly(LineItemChangeSignalType.CREATED, LineItemChangeSignalType.DELETED);
        assertThat(batch.supportedEvents()).extracting(NormalizedHubSpotLineItemEvent::externalLineItemId)
                .containsExactly("line-text", "486464823492123456789012345678901234567890");
        assertThat(batch.supportedEvents()).extracting(NormalizedHubSpotLineItemEvent::occurredAt)
                .containsExactly(Instant.ofEpochMilli(2000), Instant.ofEpochMilli(1000));
    }

    @Test
    void parsesEveryMonitoredPropertyAndPreservesEmptyValue() {
        for (MonitoredLineItemProperty property : MonitoredLineItemProperty.values()) {
            String value = property == MonitoredLineItemProperty.NAME ? "" : " value ";
            var batch = parser.parse(array(propertyEvent(property.providerName(), value, 0)));

            assertThat(batch.supportedEvents()).hasSize(1);
            NormalizedHubSpotLineItemEvent event = batch.supportedEvents().getFirst();
            assertThat(event.type()).isEqualTo(LineItemChangeSignalType.PROPERTY_CHANGED);
            assertThat(event.property()).isEqualTo(property);
            assertThat(event.propertyValue()).isEqualTo(value);
        }
    }

    @Test
    void normalizesAssociationAddAndRemoveInBothOrientations() {
        var batch = parser.parse(array(
                associationEvent("0-8", "101", "0-3", "202", false),
                associationEvent("0-3", "303", "0-8", "404", true)));

        assertThat(batch.supportedEvents()).hasSize(2);
        NormalizedHubSpotLineItemEvent added = batch.supportedEvents().get(0);
        assertThat(added.externalLineItemId()).isEqualTo("101");
        assertThat(added.externalDealId()).isEqualTo("202");
        assertThat(added.associationAction()).isEqualTo(AssociationAction.ADDED);
        assertThat(added.associationTypeId()).isEqualTo("20");
        assertThat(added.associationCategory()).isEqualTo("HUBSPOT_DEFINED");

        NormalizedHubSpotLineItemEvent removed = batch.supportedEvents().get(1);
        assertThat(removed.externalLineItemId()).isEqualTo("404");
        assertThat(removed.externalDealId()).isEqualTo("303");
        assertThat(removed.associationAction()).isEqualTo(AssociationAction.REMOVED);
    }

    @Test
    void ignoresUnsupportedPropertiesObjectsAndAssociations() {
        String unsupportedProperty = common("object.propertyChange", "1", "1000", 0)
                + ",\"objectTypeId\":\"0-8\",\"objectId\":1,\"propertyName\":\"calculated_total\"}";
        String otherObject = common("object.creation", "2", "1001", 0)
                + ",\"objectTypeId\":\"0-1\"}";
        String irrelevantAssociation = common("object.associationChange", "3", "1002", 0)
                + ",\"fromObjectTypeId\":\"0-8\",\"toObjectTypeId\":\"0-7\"}";

        var batch = parser.parse(array(unsupportedProperty, otherObject, irrelevantAssociation));

        assertThat(batch.supportedEvents()).isEmpty();
        assertThat(batch.ignoredEvents()).isEqualTo(3);
    }

    @Test
    void enforcesBatchShapeAndMaximum() {
        assertThatThrownBy(() -> parser.parse(bytes("{}")))
                .isInstanceOf(HubSpotWebhookPayloadException.class);
        assertThatThrownBy(() -> parser.parse(bytes("[]")))
                .isInstanceOf(HubSpotWebhookPayloadException.class);

        String hundred = IntStream.range(0, 100)
                .mapToObj(index -> objectEvent("object.creation", Integer.toString(index + 1), "1000"))
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        assertThat(parser.parse(bytes(hundred)).supportedEvents()).hasSize(100);

        String hundredOne = IntStream.range(0, 101)
                .mapToObj(index -> objectEvent("object.creation", Integer.toString(index + 1), "1000"))
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        assertThatThrownBy(() -> parser.parse(bytes(hundredOne)))
                .isInstanceOf(HubSpotWebhookPayloadException.class);
    }

    @Test
    void rejectsMalformedSupportedEventsAndNonIntegralIds() {
        String missingObjectId = common("object.creation", "1", "1000", 0)
                + ",\"objectTypeId\":\"0-8\"}";
        String fractionalObjectId = common("object.creation", "1", "1000", 0)
                + ",\"objectTypeId\":\"0-8\",\"objectId\":1.5}";
        String booleanObjectId = common("object.creation", "1", "1000", 0)
                + ",\"objectTypeId\":\"0-8\",\"objectId\":true}";
        String objectObjectId = common("object.creation", "1", "1000", 0)
                + ",\"objectTypeId\":\"0-8\",\"objectId\":{}}";
        String blankTextObjectId = common("object.creation", "1", "1000", 0)
                + ",\"objectTypeId\":\"0-8\",\"objectId\":\" \"}";

        for (String invalid : Arrays.asList(
                missingObjectId,
                fractionalObjectId,
                booleanObjectId,
                objectObjectId,
                blankTextObjectId)) {
            assertThatThrownBy(() -> parser.parse(array(invalid)))
                    .isInstanceOf(HubSpotWebhookPayloadException.class);
        }
    }

    @Test
    void rejectsOversizedPropertyValueAndMalformedAssociation() {
        assertThatThrownBy(() -> parser.parse(array(propertyEvent(
                "name", "x".repeat(65_536), 0))))
                .isInstanceOf(HubSpotWebhookPayloadException.class);

        String missingRemovalFlag = common("object.associationChange", "1", "1000", 0)
                + ",\"fromObjectTypeId\":\"0-8\",\"fromObjectId\":1"
                + ",\"toObjectTypeId\":\"0-3\",\"toObjectId\":2"
                + ",\"associationTypeId\":20,\"associationCategory\":\"HUBSPOT_DEFINED\"}";
        assertThatThrownBy(() -> parser.parse(array(missingRemovalFlag)))
                .isInstanceOf(HubSpotWebhookPayloadException.class);
    }

    @Test
    void deduplicationIgnoresAttemptAndChangesWithSemanticValue() {
        NormalizedHubSpotLineItemEvent first = parser.parse(array(
                propertyEvent("quantity", "10", 0))).supportedEvents().getFirst();
        NormalizedHubSpotLineItemEvent retry = parser.parse(array(
                propertyEvent("quantity", "10", 4))).supportedEvents().getFirst();
        NormalizedHubSpotLineItemEvent changed = parser.parse(array(
                propertyEvent("quantity", "11", 0))).supportedEvents().getFirst();

        assertThat(first.deduplicationKey()).isEqualTo(retry.deduplicationKey());
        assertThat(first.deduplicationKey()).isNotEqualTo(changed.deduplicationKey());
    }

    private static byte[] array(String... events) {
        return bytes("[" + String.join(",", events) + "]");
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String objectEvent(String type, String objectIdJson, String occurredAt) {
        return common(type, objectIdJson.replace("\"", ""), occurredAt, 0)
                + ",\"objectTypeId\":\"0-8\",\"objectId\":" + objectIdJson + "}";
    }

    private static String propertyEvent(String property, String value, int attempt) {
        String escapedValue = value.replace("\\", "\\\\").replace("\"", "\\\"");
        return common("object.propertyChange", "1", "1000", attempt)
                + ",\"objectTypeId\":\"0-8\",\"objectId\":202"
                + ",\"propertyName\":\"" + property + "\""
                + ",\"propertyValue\":\"" + escapedValue + "\"}";
    }

    private static String associationEvent(
            String fromType,
            String fromId,
            String toType,
            String toId,
            boolean removed) {
        return common("object.associationChange", fromId + "-" + toId, "1000", 0)
                + ",\"fromObjectTypeId\":\"" + fromType + "\""
                + ",\"fromObjectId\":" + fromId
                + ",\"toObjectTypeId\":\"" + toType + "\""
                + ",\"toObjectId\":" + toId
                + ",\"associationRemoved\":" + removed
                + ",\"associationTypeId\":20"
                + ",\"associationCategory\":\"HUBSPOT_DEFINED\"}";
    }

    private static String common(
            String subscriptionType,
            String eventId,
            String occurredAt,
            int attempt) {
        return "{\"eventId\":\"" + eventId + "\""
                + ",\"subscriptionId\":987"
                + ",\"portalId\":101"
                + ",\"appId\":456"
                + ",\"occurredAt\":" + occurredAt
                + ",\"subscriptionType\":\"" + subscriptionType + "\""
                + ",\"attemptNumber\":" + attempt;
    }
}
