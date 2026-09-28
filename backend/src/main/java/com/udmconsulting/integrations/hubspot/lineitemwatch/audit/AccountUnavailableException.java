package com.udmconsulting.integrations.hubspot.lineitemwatch.audit;

final class AccountUnavailableException extends RuntimeException {
    AccountUnavailableException() {
        super("account unavailable");
    }
}
