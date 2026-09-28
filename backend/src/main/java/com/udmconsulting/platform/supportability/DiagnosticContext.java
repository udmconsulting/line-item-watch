package com.udmconsulting.platform.supportability;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;

public final class DiagnosticContext {

    public static final String CORRELATION_ID = "correlationId";
    public static final String OPERATION_ID = "operationId";

    private DiagnosticContext() {
    }

    public static Scope withCorrelationId(UUID correlationId) {
        return open(CORRELATION_ID, correlationId);
    }

    public static Scope withOperationId(UUID operationId) {
        return open(OPERATION_ID, operationId);
    }

    public static Optional<UUID> correlationId() {
        return id(CORRELATION_ID);
    }

    public static Optional<UUID> operationId() {
        return id(OPERATION_ID);
    }

    public static UUID currentDiagnosticIdOrNew() {
        return correlationId().or(() -> operationId()).orElseGet(UUID::randomUUID);
    }

    private static Optional<UUID> id(String key) {
        String value = MDC.get(key);
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private static Scope open(String key, UUID id) {
        if (id == null) {
            throw new IllegalArgumentException("diagnostic ID must not be null");
        }
        Map<String, String> prior = MDC.getCopyOfContextMap();
        MDC.put(key, id.toString());
        return new Scope(prior);
    }

    public static final class Scope implements AutoCloseable {

        private final Map<String, String> prior;
        private boolean closed;

        private Scope(Map<String, String> prior) {
            this.prior = prior;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (prior == null || prior.isEmpty()) {
                MDC.clear();
            } else {
                MDC.setContextMap(prior);
            }
        }
    }
}
