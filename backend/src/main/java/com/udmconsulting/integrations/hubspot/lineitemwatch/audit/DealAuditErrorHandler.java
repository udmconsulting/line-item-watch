package com.udmconsulting.integrations.hubspot.lineitemwatch.audit;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = HubSpotDealAuditController.class)
@ConditionalOnProperty(prefix = "hubspot.ui-extension", name = "enabled", havingValue = "true")
final class DealAuditErrorHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(DealAuditErrorHandler.class);

    @ExceptionHandler(HubSpotUiExtensionAuthenticationException.class)
    ResponseEntity<ErrorEnvelope> authentication(HttpServletRequest request) {
        return error(request, HttpStatus.UNAUTHORIZED, "AUTHENTICATION_FAILED", "AUTHENTICATION_FAILED", null);
    }

    @ExceptionHandler(DealAuditRequestException.class)
    ResponseEntity<ErrorEnvelope> invalidRequest(HttpServletRequest request) {
        return error(request, HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "INVALID_REQUEST", null);
    }

    @ExceptionHandler(AccountUnavailableException.class)
    ResponseEntity<ErrorEnvelope> unavailableAccount(HttpServletRequest request) {
        return error(request, HttpStatus.FORBIDDEN, "ACCOUNT_UNAVAILABLE", "ACCOUNT_UNAVAILABLE", null);
    }

    @ExceptionHandler(AccountResolutionInvariantException.class)
    ResponseEntity<ErrorEnvelope> invariant(HttpServletRequest request) {
        return error(request, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "ACCOUNT_INVARIANT", null);
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ErrorEnvelope> database(HttpServletRequest request, DataAccessException exception) {
        return error(
                request,
                HttpStatus.SERVICE_UNAVAILABLE,
                "SERVICE_UNAVAILABLE",
                "DATABASE_UNAVAILABLE",
                exception.getClass().getName());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorEnvelope> unexpected(HttpServletRequest request, Exception exception) {
        return error(
                request,
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "INTERNAL_FAILURE",
                exception.getClass().getName());
    }

    private static ResponseEntity<ErrorEnvelope> error(
            HttpServletRequest request,
            HttpStatus status,
            String code,
            String category,
            String exceptionType) {
        UUID correlationId = DealAuditCorrelationFilter.correlationId(request);
        if (status.is5xxServerError()) {
            LOGGER.error(
                    "Deal audit read failed correlationId={} category={} exceptionType={}",
                    correlationId,
                    category,
                    exceptionType == null ? "NONE" : exceptionType);
        } else {
            LOGGER.warn(
                    "Deal audit read rejected correlationId={} category={}",
                    correlationId,
                    category);
        }
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(new ErrorEnvelope(new ApiError(code, correlationId)));
    }

    record ErrorEnvelope(ApiError error) {
    }

    record ApiError(String code, UUID correlationId) {
    }
}
