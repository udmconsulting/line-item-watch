package com.udmconsulting.platform.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WorkerShutdownSignalTest {

    @Test
    void lifecycleStopsNewClaimsAtTheHighestShutdownPhase() {
        WorkerShutdownSignal signal = new WorkerShutdownSignal();

        assertThat(signal.acceptingClaims()).isFalse();
        signal.start();
        assertThat(signal.acceptingClaims()).isTrue();
        assertThat(signal.getPhase()).isEqualTo(Integer.MAX_VALUE);
        signal.stop();
        assertThat(signal.acceptingClaims()).isFalse();
    }
}
