package com.udmconsulting.modules.lineitemwatch.application;

public final class SignalProcessingException extends RuntimeException {

    private final String errorCode;
    private final boolean retryable;

    public SignalProcessingException(String errorCode, boolean retryable, Throwable cause) {
        super("Line Item signal processing failed", cause);
        if (errorCode == null || !errorCode.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("errorCode must be a sanitized uppercase code");
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
