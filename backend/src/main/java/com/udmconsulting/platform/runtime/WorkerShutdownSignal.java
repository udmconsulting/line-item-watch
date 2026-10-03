package com.udmconsulting.platform.runtime;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** Stops new durable claims before lower-phase database infrastructure is closed. */
@Component
public final class WorkerShutdownSignal implements SmartLifecycle {

    private final AtomicBoolean acceptingClaims = new AtomicBoolean();

    public boolean acceptingClaims() {
        return acceptingClaims.get();
    }

    @Override
    public void start() {
        acceptingClaims.set(true);
    }

    @Override
    public void stop() {
        acceptingClaims.set(false);
    }

    @Override
    public boolean isRunning() {
        return acceptingClaims();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
