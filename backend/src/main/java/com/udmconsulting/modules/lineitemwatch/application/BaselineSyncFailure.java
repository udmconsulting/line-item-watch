package com.udmconsulting.modules.lineitemwatch.application;

public enum BaselineSyncFailure {
    CONNECTION_NOT_FOUND(false),
    CONNECTION_PROVIDER_UNSUPPORTED(false),
    CONNECTION_NOT_ACTIVE(false),
    MODULE_NOT_ENTITLED(false),
    PROVIDER_DEAL_NOT_FOUND(false),
    PROVIDER_DEAL_IDENTITY_MISMATCH(false),
    PROVIDER_LINE_ITEM_IDENTITY_MISMATCH(false),
    PROVIDER_LINE_ITEM_IDENTITY_DUPLICATE(false),
    PROVIDER_STATE_CHANGED(true),
    PROVIDER_AUTHORIZATION_REJECTED(false),
    PROVIDER_UNAVAILABLE(true),
    PROVIDER_RESPONSE_INVALID(false),
    PROVIDER_ASSOCIATION_READ_INCOMPLETE(true),
    PROVIDER_OBJECT_ID_INVALID(false);

    private final boolean retryable;

    BaselineSyncFailure(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
