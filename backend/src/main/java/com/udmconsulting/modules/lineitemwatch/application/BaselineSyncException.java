package com.udmconsulting.modules.lineitemwatch.application;

public class BaselineSyncException extends RuntimeException {

    private final boolean retryable;

    public BaselineSyncException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
