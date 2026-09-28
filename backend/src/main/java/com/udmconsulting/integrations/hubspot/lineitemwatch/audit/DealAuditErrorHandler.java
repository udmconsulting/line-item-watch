package com.udmconsulting.integrations.hubspot.lineitemwatch.audit;

import com.udmconsulting.platform.supportability.CorrelationFilter;
import com.udmconsulting.platform.supportability.OperationalErrorCode;
import com.udmconsulting.platform.supportability.PublicErrorCode;
import com.udmconsulting.platform.supportability.RequestSupportability;
import com.udmconsulting.platform.supportability.SafeDiagnosticException;
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
        return error(request, HttpStatus.UNAUTHORIZED,
                PublicErrorCode.AUTHENTICATION_FAILED,
                OperationalErrorCode.AUTHENTICATION_FAILED, null);
    }

    @ExceptionHandler(DealAuditRequestException.class)
    ResponseEntity<ErrorEnvelope> invalidRequest(HttpServletRequest request) {
        return error(request, HttpStatus.BAD_REQUEST,
                PublicErrorCode.INVALID_REQUEST, OperationalErrorCode.INVALID_REQUEST, null);
    }

    @ExceptionHandler(AccountUnavailableException.class)
    ResponseEntity<ErrorEnvelope> unavailableAccount(HttpServletRequest request) {
        return error(request, HttpStatus.FORBIDDEN,
                PublicErrorCode.ACCOUNT_UNAVAILABLE,
                OperationalErrorCode.ACCOUNT_UNAVAILABLE, null);
    }

    @ExceptionHandler(AccountResolutionInvariantException.class)
    ResponseEntity<ErrorEnvelope> invariant(HttpServletRequest request) {
        return error(request, HttpStatus.INTERNAL_SERVER_ERROR,
                PublicErrorCode.INTERNAL_ERROR,
                OperationalErrorCode.INVARIANT_VIOLATION, null);
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ErrorEnvelope> database(HttpServletRequest request, DataAccessException exception) {
        return error(
                request,
                HttpStatus.SERVICE_UNAVAILABLE,
                PublicErrorCode.SERVICE_UNAVAILABLE,
                OperationalErrorCode.DATABASE_UNAVAILABLE,
                null);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorEnvelope> unexpected(HttpServletRequest request, Exception exception) {
        return error(
                request,
                HttpStatus.INTERNAL_SERVER_ERROR,
                PublicErrorCode.INTERNAL_ERROR,
                OperationalErrorCode.INTERNAL_ERROR,
                exception);
    }

    private static ResponseEntity<ErrorEnvelope> error(
            HttpServletRequest request,
            HttpStatus status,
            PublicErrorCode code,
            OperationalErrorCode category,
            Throwable exception) {
        UUID correlationId = CorrelationFilter.correlationId(request);
        RequestSupportability.error(request, category);
        if (status.is5xxServerError()) {
            var event = LOGGER.atError()
                    .addKeyValue("component", "line_item_watch")
                    .addKeyValue("operation", "deal_audit_read")
                    .addKeyValue("result", "FAILED")
                    .addKeyValue("errorCode", category.name());
            if (exception != null) {
                event.setCause(SafeDiagnosticException.from(exception));
            }
            event.log("Deal audit read failed");
        } else {
            LOGGER.atWarn()
                    .addKeyValue("component", "line_item_watch")
                    .addKeyValue("operation", "deal_audit_read")
                    .addKeyValue("result", "REJECTED")
                    .addKeyValue("errorCode", category.name())
                    .log("Deal audit read rejected");
        }
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(new ErrorEnvelope(new ApiError(code.name(), correlationId)));
    }

    record ErrorEnvelope(ApiError error) {
    }

    record ApiError(String code, UUID correlationId) {
    }
}
