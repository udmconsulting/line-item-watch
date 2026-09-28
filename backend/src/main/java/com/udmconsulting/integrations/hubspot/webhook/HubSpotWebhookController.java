package com.udmconsulting.integrations.hubspot.webhook;

import com.udmconsulting.integrations.hubspot.config.HubSpotWebhookProperties;
import com.udmconsulting.platform.supportability.ApplicationOperation;
import com.udmconsulting.platform.supportability.OperationalErrorCode;
import com.udmconsulting.platform.supportability.RequestSupportability;
import com.udmconsulting.platform.supportability.SafeDiagnosticException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Locale;
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
        RequestSupportability.operation(request, ApplicationOperation.HUBSPOT_WEBHOOK_INGEST);
        if (!"POST".equals(request.getMethod())) {
            RequestSupportability.error(request, OperationalErrorCode.INVALID_REQUEST);
            return empty(HttpStatus.METHOD_NOT_ALLOWED);
        }
        if (!isJson(request.getContentType())) {
            RequestSupportability.error(request, OperationalErrorCode.INVALID_REQUEST);
            return empty(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        }
        if (!isSupportedContentEncoding(request.getHeader(HttpHeaders.CONTENT_ENCODING))) {
            RequestSupportability.error(request, OperationalErrorCode.INVALID_REQUEST);
            return empty(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        }
        if (request.getContentLengthLong() > HubSpotWebhookProperties.MAX_BODY_BYTES) {
            RequestSupportability.error(request, OperationalErrorCode.INVALID_REQUEST);
            return empty(HttpStatus.PAYLOAD_TOO_LARGE);
        }

        byte[] rawBody;
        try {
            rawBody = request.getInputStream()
                    .readNBytes(HubSpotWebhookProperties.MAX_BODY_BYTES + 1);
        } catch (IOException exception) {
            RequestSupportability.error(request, OperationalErrorCode.REQUEST_BODY_READ_FAILED);
            logKnown("FAILED", OperationalErrorCode.REQUEST_BODY_READ_FAILED);
            return empty(HttpStatus.SERVICE_UNAVAILABLE);
        }
        if (rawBody.length > HubSpotWebhookProperties.MAX_BODY_BYTES) {
            RequestSupportability.error(request, OperationalErrorCode.INVALID_REQUEST);
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
            RequestSupportability.error(request, OperationalErrorCode.AUTHENTICATION_FAILED);
            logKnown("REJECTED", OperationalErrorCode.AUTHENTICATION_FAILED);
            return empty(HttpStatus.UNAUTHORIZED);
        } catch (HubSpotWebhookPayloadException exception) {
            RequestSupportability.error(request, OperationalErrorCode.WEBHOOK_PAYLOAD_INVALID);
            logKnown("REJECTED", OperationalErrorCode.WEBHOOK_PAYLOAD_INVALID);
            return empty(HttpStatus.BAD_REQUEST);
        } catch (DataAccessException exception) {
            RequestSupportability.error(request, OperationalErrorCode.DATABASE_UNAVAILABLE);
            logKnown("FAILED", OperationalErrorCode.DATABASE_UNAVAILABLE);
            return empty(HttpStatus.SERVICE_UNAVAILABLE);
        } catch (RuntimeException exception) {
            RequestSupportability.error(request, OperationalErrorCode.INTERNAL_ERROR);
            LOGGER.atError()
                    .addKeyValue("component", "hubspot_webhook")
                    .addKeyValue("operation", "webhook_ingest")
                    .addKeyValue("result", "FAILED")
                    .addKeyValue("errorCode", OperationalErrorCode.INTERNAL_ERROR.name())
                    .setCause(SafeDiagnosticException.from(exception))
                    .log("HubSpot webhook request failed unexpectedly");
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

    private static void logKnown(String result, OperationalErrorCode errorCode) {
        LOGGER.atWarn()
                .addKeyValue("component", "hubspot_webhook")
                .addKeyValue("operation", "webhook_ingest")
                .addKeyValue("result", result)
                .addKeyValue("errorCode", errorCode.name())
                .log("HubSpot webhook request was not accepted");
    }
}
