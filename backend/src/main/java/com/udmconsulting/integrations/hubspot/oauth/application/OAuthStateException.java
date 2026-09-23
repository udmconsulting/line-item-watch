package com.udmconsulting.integrations.hubspot.oauth.application;

public final class OAuthStateException extends RuntimeException {

    private final Reason reason;

    public OAuthStateException(Reason reason) {
        super(switch (reason) {
            case INVALID -> "Invalid OAuth state";
            case EXPIRED -> "Expired OAuth state";
            case REPLAYED -> "OAuth state has already been used";
        });
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        INVALID,
        EXPIRED,
        REPLAYED
    }
}
