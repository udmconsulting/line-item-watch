package com.udmconsulting.modules.lineitemwatch.application;

import com.udmconsulting.modules.lineitemwatch.application.LineItemChangeSignalStore.CaptureResult;
import com.udmconsulting.modules.lineitemwatch.domain.LineItemChangeSignal;
import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.tenant.domain.TenantId;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public final class CaptureLineItemChangeSignals {

    private final LineItemChangeSignalStore store;

    public CaptureLineItemChangeSignals(LineItemChangeSignalStore store) {
        this.store = Objects.requireNonNull(store, "store must not be null");
    }

    public CaptureResult capture(
            TenantId tenantId,
            PlatformConnectionId connectionId,
            List<LineItemChangeSignal> signals) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(connectionId, "connectionId must not be null");
        Objects.requireNonNull(signals, "signals must not be null");
        if (signals.isEmpty()) {
            throw new IllegalArgumentException("signals must not be empty");
        }
        if (signals.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("signals must not contain null");
        }
        if (signals.stream().anyMatch(signal ->
                !tenantId.equals(signal.tenantId())
                        || !connectionId.equals(signal.connectionId()))) {
            throw new IllegalArgumentException("all signals must have the requested Tenant and connection provenance");
        }
        return store.capture(tenantId, connectionId, List.copyOf(signals));
    }
}
