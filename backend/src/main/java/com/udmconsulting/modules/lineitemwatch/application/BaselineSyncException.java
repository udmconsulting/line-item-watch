package com.udmconsulting.modules.lineitemwatch.application;

import java.util.Objects;

public final class BaselineSyncException extends RuntimeException {

    private final BaselineSyncFailure failure;

    public BaselineSyncException(BaselineSyncFailure failure) {
        super(diagnosticDescription(failure));
        this.failure = failure;
    }

    public BaselineSyncFailure failure() {
        return failure;
    }

    public boolean retryable() {
        return failure.retryable();
    }

    private static String diagnosticDescription(BaselineSyncFailure failure) {
        return switch (Objects.requireNonNull(failure, "failure must not be null")) {
            case CONNECTION_NOT_FOUND -> "Platform Connection was not found for Tenant";
            case CONNECTION_PROVIDER_UNSUPPORTED -> "Platform Connection provider is not supported";
            case CONNECTION_NOT_ACTIVE -> "Platform Connection is not active";
            case MODULE_NOT_ENTITLED -> "Tenant is not entitled to LINE_ITEM_WATCH";
            case PROVIDER_DEAL_NOT_FOUND -> "Provider Deal was not found";
            case PROVIDER_DEAL_IDENTITY_MISMATCH ->
                    "Provider returned an unexpected Deal identity";
            case PROVIDER_LINE_ITEM_IDENTITY_MISMATCH ->
                    "Provider returned an unexpected Line Item identity";
            case PROVIDER_LINE_ITEM_IDENTITY_DUPLICATE ->
                    "Provider returned a duplicate Line Item identity";
            case PROVIDER_STATE_CHANGED ->
                    "Provider state changed while the Deal baseline was read";
            case PROVIDER_AUTHORIZATION_REJECTED ->
                    "Provider baseline read was not authorized";
            case PROVIDER_UNAVAILABLE -> "Provider baseline read was unavailable";
            case PROVIDER_RESPONSE_INVALID -> "Provider returned an invalid baseline response";
            case PROVIDER_ASSOCIATION_READ_INCOMPLETE ->
                    "Provider baseline association read was incomplete";
            case PROVIDER_OBJECT_ID_INVALID -> "Provider object ID is invalid";
        };
    }
}
