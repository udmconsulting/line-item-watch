package com.udmconsulting.integrations.hubspot.webhook;

import com.udmconsulting.integrations.hubspot.config.HubSpotWebhookProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(prefix = "hubspot.webhook", name = "enabled", havingValue = "true")
final class HubSpotWebhookController {

    static final String SIGNATURE_HEADER = "X-HubSpot-Signature-v3";
    static final String TIMESTAMP_HEADER = "X-HubSpot-Request-Timestamp";

    private static final Logger LOGGER = LoggerFactory.getLogger(HubSpotWebhookController.class);

    private final HubSpotWebhookIngressService ingressService;

    HubSpotWebhookController(HubSpotWebhookIngressService ingressService) {
        this.ingressService = ingressService;
    }

    @RequestMapping(HubSpotWebhookProperties.ENDPOINT_PATH)
    ResponseEntity<Void> receive(HttpServletRequest request) {
        UUID correlationId = UUID.randomUUID();
        if (!"POST".equals(request.getMethod())) {
            return empty(HttpStatus.METHOD_NOT_ALLOWED);
        }
        if (!isJson(request.getContentType())) {
            return empty(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        }
        if (!isSupportedContentEncoding(request.getHeader(HttpHeaders.CONTENT_ENCODING))) {
            return empty(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        }
        if (request.getContentLengthLong() > HubSpotWebhookProperties.MAX_BODY_BYTES) {
            return empty(HttpStatus.PAYLOAD_TOO_LARGE);
        }

        byte[] rawBody;
        try {
            rawBody = request.getInputStream()
                    .readNBytes(HubSpotWebhookProperties.MAX_BODY_BYTES + 1);
        } catch (IOException exception) {
            LOGGER.warn(
                    "HubSpot webhook request failed correlationId={} category=BODY_READ_FAILED",
                    correlationId);
            return empty(HttpStatus.SERVICE_UNAVAILABLE);
        }
        if (rawBody.length > HubSpotWebhookProperties.MAX_BODY_BYTES) {
            return empty(HttpStatus.PAYLOAD_TOO_LARGE);
        }

        try {
            ingressService.ingest(
                    request.getMethod(),
                    request.getHeader(SIGNATURE_HEADER),
                    request.getHeader(TIMESTAMP_HEADER),
                    rawBody);
            return empty(HttpStatus.NO_CONTENT);
        } catch (HubSpotWebhookAuthenticationException exception) {
            LOGGER.warn(
                    "HubSpot webhook request rejected correlationId={} category=AUTHENTICATION_FAILED",
                    correlationId);
            return empty(HttpStatus.UNAUTHORIZED);
        } catch (HubSpotWebhookPayloadException exception) {
            LOGGER.warn(
                    "HubSpot webhook request rejected correlationId={} category=PAYLOAD_INVALID",
                    correlationId);
            return empty(HttpStatus.BAD_REQUEST);
        } catch (DataAccessException exception) {
            LOGGER.error(
                    "HubSpot webhook request failed correlationId={} category=DATABASE_UNAVAILABLE exceptionType={}",
                    correlationId,
                    exception.getClass().getName());
            return empty(HttpStatus.SERVICE_UNAVAILABLE);
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "HubSpot webhook request failed correlationId={} category=INTERNAL_FAILURE exceptionType={}",
                    correlationId,
                    exception.getClass().getName());
            return empty(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private static boolean isJson(String contentType) {
        if (contentType == null) {
            return false;
        }
        try {
            MediaType mediaType = MediaType.parseMediaType(contentType);
            return "application".equalsIgnoreCase(mediaType.getType())
                    && "json".equalsIgnoreCase(mediaType.getSubtype());
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean isSupportedContentEncoding(String contentEncoding) {
        return contentEncoding == null
                || contentEncoding.isBlank()
                || "identity".equals(contentEncoding.trim().toLowerCase(Locale.ROOT));
    }

    private static ResponseEntity<Void> empty(HttpStatus status) {
        return ResponseEntity.status(status).build();
    }
}
