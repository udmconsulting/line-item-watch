package com.udmconsulting.modules.lineitemwatch.application;

import com.udmconsulting.platform.supportability.OperationalErrorCode;

public final class SignalProcessingException extends RuntimeException {

    private final String errorCode;
    private final boolean retryable;

    public SignalProcessingException(String errorCode, boolean retryable, Throwable cause) {
        super("Line Item signal processing failed", cause);
        try {
            OperationalErrorCode.valueOf(errorCode);
        } catch (NullPointerException | IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "errorCode must be a bounded application-owned category", exception);
        }
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public String errorCode() {
        return errorCode;
    }

    public boolean retryable() {
        return retryable;
    }
}
