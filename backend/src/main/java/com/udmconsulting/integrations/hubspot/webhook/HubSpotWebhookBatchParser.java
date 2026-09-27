package com.udmconsulting.integrations.hubspot.webhook;

import com.udmconsulting.integrations.hubspot.config.HubSpotWebhookProperties;
import com.udmconsulting.modules.lineitemwatch.domain.AssociationAction;
import com.udmconsulting.modules.lineitemwatch.domain.LineItemChangeSignal;
import com.udmconsulting.modules.lineitemwatch.domain.LineItemChangeSignalType;
import com.udmconsulting.modules.lineitemwatch.domain.MonitoredLineItemProperty;
import com.udmconsulting.modules.lineitemwatch.domain.ProviderDeduplicationKey;
import java.math.BigInteger;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
final class HubSpotWebhookBatchParser {

    static final String LINE_ITEM_OBJECT_TYPE_ID = "0-8";
    static final String DEAL_OBJECT_TYPE_ID = "0-3";

    private static final int MAX_OBJECT_TYPE_LENGTH = 64;
    private static final int MAX_SUBSCRIPTION_TYPE_LENGTH = 128;

    private final ObjectMapper objectMapper;

    HubSpotWebhookBatchParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    ParsedBatch parse(byte[] rawBody) {
        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody);
        } catch (JacksonException exception) {
            throw new HubSpotWebhookPayloadException("malformed webhook JSON", exception);
        }
        if (root == null || !root.isArray()) {
            throw new HubSpotWebhookPayloadException("webhook payload must be an array");
        }
        if (root.isEmpty() || root.size() > HubSpotWebhookProperties.MAX_BATCH_EVENTS) {
            throw new HubSpotWebhookPayloadException("webhook batch size is outside the allowed range");
        }

        List<NormalizedHubSpotLineItemEvent> supported = new ArrayList<>();
        int ignored = 0;
        for (JsonNode event : root) {
            if (!event.isObject()) {
                throw new HubSpotWebhookPayloadException("webhook event must be an object");
            }
            CommonFields common = commonFields(event);
            NormalizedHubSpotLineItemEvent normalized = switch (common.subscriptionType()) {
                case "object.creation" -> objectEvent(
                        event, common, LineItemChangeSignalType.CREATED);
                case "object.deletion" -> objectEvent(
                        event, common, LineItemChangeSignalType.DELETED);
                case "object.propertyChange" -> propertyEvent(event, common);
                case "object.associationChange" -> associationEvent(event, common);
                default -> null;
            };
            if (normalized == null) {
                ignored++;
            } else {
                supported.add(normalized);
            }
        }
        return new ParsedBatch(List.copyOf(supported), ignored, root.size());
    }

    private CommonFields commonFields(JsonNode event) {
        String eventId = providerId(event, "eventId", LineItemChangeSignal.MAX_PROVIDER_ID_LENGTH);
        String subscriptionId = providerId(
                event, "subscriptionId", LineItemChangeSignal.MAX_PROVIDER_ID_LENGTH);
        String portalId = providerId(event, "portalId", LineItemChangeSignal.MAX_PROVIDER_ID_LENGTH);
        providerId(event, "appId", LineItemChangeSignal.MAX_PROVIDER_ID_LENGTH);
        Instant occurredAt = epochMilliseconds(event, "occurredAt");
        String subscriptionType = boundedText(
                event, "subscriptionType", MAX_SUBSCRIPTION_TYPE_LENGTH, false);
        nonNegativeIntegral(event, "attemptNumber");
        return new CommonFields(eventId, subscriptionId, portalId, occurredAt, subscriptionType);
    }

    private NormalizedHubSpotLineItemEvent objectEvent(
            JsonNode event,
            CommonFields common,
            LineItemChangeSignalType type) {
        String objectType = boundedText(event, "objectTypeId", MAX_OBJECT_TYPE_LENGTH, false);
        if (!LINE_ITEM_OBJECT_TYPE_ID.equals(objectType)) {
            return null;
        }
        String lineItemId = providerId(
                event, "objectId", LineItemChangeSignal.MAX_PROVIDER_ID_LENGTH);
        return event(common, lineItemId, type, null, null, null, null, null, null);
    }

    private NormalizedHubSpotLineItemEvent propertyEvent(JsonNode event, CommonFields common) {
        String objectType = boundedText(event, "objectTypeId", MAX_OBJECT_TYPE_LENGTH, false);
        if (!LINE_ITEM_OBJECT_TYPE_ID.equals(objectType)) {
            return null;
        }
        String lineItemId = providerId(
                event, "objectId", LineItemChangeSignal.MAX_PROVIDER_ID_LENGTH);
        String propertyName = boundedText(event, "propertyName", 128, false);
        MonitoredLineItemProperty property = MonitoredLineItemProperty
                .fromProviderName(propertyName)
                .orElse(null);
        if (property == null) {
            return null;
        }
        String propertyValue = boundedText(
                event, "propertyValue", LineItemChangeSignal.MAX_PROPERTY_VALUE_LENGTH, true);
        return event(
                common,
                lineItemId,
                LineItemChangeSignalType.PROPERTY_CHANGED,
                property,
                propertyValue,
                null,
                null,
                null,
                null);
    }

    private NormalizedHubSpotLineItemEvent associationEvent(JsonNode event, CommonFields common) {
        String fromType = boundedText(event, "fromObjectTypeId", MAX_OBJECT_TYPE_LENGTH, false);
        String toType = boundedText(event, "toObjectTypeId", MAX_OBJECT_TYPE_LENGTH, false);
        boolean forward = LINE_ITEM_OBJECT_TYPE_ID.equals(fromType) && DEAL_OBJECT_TYPE_ID.equals(toType);
        boolean reverse = DEAL_OBJECT_TYPE_ID.equals(fromType) && LINE_ITEM_OBJECT_TYPE_ID.equals(toType);
        if (!forward && !reverse) {
            return null;
        }

        String fromId = providerId(
                event, "fromObjectId", LineItemChangeSignal.MAX_PROVIDER_ID_LENGTH);
        String toId = providerId(event, "toObjectId", LineItemChangeSignal.MAX_PROVIDER_ID_LENGTH);
        JsonNode removedNode = event.get("associationRemoved");
        if (removedNode == null || !removedNode.isBoolean()) {
            throw new HubSpotWebhookPayloadException("associationRemoved must be a boolean");
        }
        AssociationAction action = removedNode.booleanValue()
                ? AssociationAction.REMOVED
                : AssociationAction.ADDED;
        String associationTypeId = providerId(
                event, "associationTypeId", LineItemChangeSignal.MAX_ASSOCIATION_METADATA_LENGTH);
        String associationCategory = boundedText(
                event,
                "associationCategory",
                LineItemChangeSignal.MAX_ASSOCIATION_METADATA_LENGTH,
                false);
        return event(
                common,
                forward ? fromId : toId,
                LineItemChangeSignalType.ASSOCIATION_CHANGED,
                null,
                null,
                forward ? toId : fromId,
                action,
                associationTypeId,
                associationCategory);
    }

    private NormalizedHubSpotLineItemEvent event(
            CommonFields common,
            String lineItemId,
            LineItemChangeSignalType type,
            MonitoredLineItemProperty property,
            String propertyValue,
            String dealId,
            AssociationAction action,
            String associationTypeId,
            String associationCategory) {
        ProviderDeduplicationKey key = HubSpotWebhookDeduplication.key(
                common.eventId(),
                common.subscriptionId(),
                common.occurredAt(),
                type,
                lineItemId,
                property,
                propertyValue,
                dealId,
                action,
                associationTypeId,
                associationCategory);
        return new NormalizedHubSpotLineItemEvent(
                common.portalId(),
                common.eventId(),
                common.subscriptionId(),
                lineItemId,
                type,
                common.occurredAt(),
                property,
                propertyValue,
                dealId,
                action,
                associationTypeId,
                associationCategory,
                key);
    }

    private static String providerId(JsonNode object, String field, int maximumLength) {
        JsonNode value = object.get(field);
        if (value == null || value.isNull()) {
            throw new HubSpotWebhookPayloadException(field + " is required");
        }
        String normalized;
        if (value.isTextual()) {
            normalized = value.textValue();
        } else if (value.isIntegralNumber()) {
            BigInteger integer = value.bigIntegerValue();
            if (integer.signum() < 0) {
                throw new HubSpotWebhookPayloadException(field + " must not be negative");
            }
            normalized = integer.toString();
        } else {
            throw new HubSpotWebhookPayloadException(field + " must be text or an integral number");
        }
        if (normalized.isBlank()
                || !normalized.equals(normalized.trim())
                || normalized.length() > maximumLength) {
            throw new HubSpotWebhookPayloadException(field + " is outside the allowed bounds");
        }
        return normalized;
    }

    private static String boundedText(
            JsonNode object,
            String field,
            int maximumLength,
            boolean allowEmpty) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual()) {
            throw new HubSpotWebhookPayloadException(field + " must be text");
        }
        String text = value.textValue();
        if ((!allowEmpty && text.isBlank())
                || (!allowEmpty && !text.equals(text.trim()))
                || text.codePointCount(0, text.length()) > maximumLength) {
            throw new HubSpotWebhookPayloadException(field + " is outside the allowed bounds");
        }
        return text;
    }

    private static Instant epochMilliseconds(JsonNode object, String field) {
        BigInteger value = nonNegativeIntegral(object, field);
        if (value.bitLength() > 63) {
            throw new HubSpotWebhookPayloadException(field + " is outside the supported timestamp range");
        }
        try {
            return Instant.ofEpochMilli(value.longValueExact());
        } catch (ArithmeticException | DateTimeException exception) {
            throw new HubSpotWebhookPayloadException(field + " is outside the supported timestamp range");
        }
    }

    private static BigInteger nonNegativeIntegral(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isIntegralNumber()) {
            throw new HubSpotWebhookPayloadException(field + " must be an integral number");
        }
        BigInteger integer = value.bigIntegerValue();
        if (integer.signum() < 0) {
            throw new HubSpotWebhookPayloadException(field + " must not be negative");
        }
        return integer;
    }

    record ParsedBatch(
            List<NormalizedHubSpotLineItemEvent> supportedEvents,
            int ignoredEvents,
            int totalEvents) {
    }

    private record CommonFields(
            String eventId,
            String subscriptionId,
            String portalId,
            Instant occurredAt,
            String subscriptionType) {
    }
}
