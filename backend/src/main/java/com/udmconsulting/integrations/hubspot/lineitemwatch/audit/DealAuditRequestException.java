package com.udmconsulting.integrations.hubspot.lineitemwatch.audit;

final class DealAuditRequestException extends RuntimeException {
    DealAuditRequestException(String message) {
        super(message);
    }

    DealAuditRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
