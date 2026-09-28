package com.udmconsulting.integrations.hubspot.lineitemwatch.audit;

final class AccountResolutionInvariantException extends RuntimeException {
    AccountResolutionInvariantException() {
        super("account resolution invariant failed");
    }
}
