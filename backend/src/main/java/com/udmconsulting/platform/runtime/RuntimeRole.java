package com.udmconsulting.platform.runtime;

public enum RuntimeRole {
    LOCAL(false),
    SERVICE(false),
    MIGRATE(true),
    OPERATOR(true);

    private final boolean oneShot;

    RuntimeRole(boolean oneShot) {
        this.oneShot = oneShot;
    }

    public boolean isOneShot() {
        return oneShot;
    }
}
