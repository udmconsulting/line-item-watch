package com.udmconsulting.platform.credential.application;

public final class ReauthenticationRequiredException extends RuntimeException {

    public ReauthenticationRequiredException() {
        super("The connection requires reauthentication");
    }
}
