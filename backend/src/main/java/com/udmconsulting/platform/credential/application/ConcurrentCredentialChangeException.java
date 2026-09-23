package com.udmconsulting.platform.credential.application;

public final class ConcurrentCredentialChangeException extends RuntimeException {

    public ConcurrentCredentialChangeException() {
        super("The connection credential changed while the provider operation was in progress");
    }
}
