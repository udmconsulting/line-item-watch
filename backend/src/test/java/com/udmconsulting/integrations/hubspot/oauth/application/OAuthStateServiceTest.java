package com.udmconsulting.integrations.hubspot.oauth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OAuthStateServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-23T10:00:00Z");

    @Test
    void storesOnlyAHashAndConsumesStateOnce() {
        InMemoryStateStore store = new InMemoryStateStore();
        OAuthStateService service = new OAuthStateService(
                store, Clock.fixed(NOW, ZoneOffset.UTC), new java.security.SecureRandom());

        OAuthStateService.IssuedState issued = service.issue();

        assertThat(issued.value()).hasSize(43);
        assertThat(store.rows).hasSize(1);
        assertThat(store.rows.keySet().iterator().next().bytes)
                .isEqualTo(OAuthStateService.hash(issued.value()))
                .isNotEqualTo(java.util.Base64.getUrlDecoder().decode(issued.value()));

        service.consume(issued.value());
        assertThatThrownBy(() -> service.consume(issued.value()))
                .isInstanceOfSatisfying(OAuthStateException.class,
                        exception -> assertThat(exception.reason())
                                .isEqualTo(OAuthStateException.Reason.REPLAYED));
    }

    @Test
    void distinguishesInvalidAndExpiredState() {
        InMemoryStateStore store = new InMemoryStateStore();
        OAuthStateService service = new OAuthStateService(
                store, Clock.fixed(NOW, ZoneOffset.UTC), new java.security.SecureRandom());

        assertThatThrownBy(() -> service.consume("unknown"))
                .isInstanceOfSatisfying(OAuthStateException.class,
                        exception -> assertThat(exception.reason())
                                .isEqualTo(OAuthStateException.Reason.INVALID));

        OAuthStateService.IssuedState issued = service.issue();
        store.rows.get(new Bytes(OAuthStateService.hash(issued.value()))).expiresAt = NOW;

        assertThatThrownBy(() -> service.consume(issued.value()))
                .isInstanceOfSatisfying(OAuthStateException.class,
                        exception -> assertThat(exception.reason())
                                .isEqualTo(OAuthStateException.Reason.EXPIRED));
    }

    private static final class InMemoryStateStore implements OAuthStateStore {

        private final Map<Bytes, Row> rows = new HashMap<>();

        @Override
        public void store(byte[] stateHash, UUID correlationId, Instant createdAt, Instant expiresAt) {
            rows.put(new Bytes(stateHash), new Row(expiresAt));
        }

        @Override
        public ConsumptionResult consume(byte[] stateHash, Instant consumedAt) {
            Row row = rows.get(new Bytes(stateHash));
            if (row == null) {
                return ConsumptionResult.INVALID;
            }
            if (row.consumed) {
                return ConsumptionResult.REPLAYED;
            }
            if (!row.expiresAt.isAfter(consumedAt)) {
                return ConsumptionResult.EXPIRED;
            }
            row.consumed = true;
            return ConsumptionResult.CONSUMED;
        }

        @Override
        public void deleteRetainedBefore(Instant retentionThreshold) {
        }
    }

    private static final class Row {
        private Instant expiresAt;
        private boolean consumed;

        private Row(Instant expiresAt) {
            this.expiresAt = expiresAt;
        }
    }

    private static final class Bytes {
        private final byte[] bytes;

        private Bytes(byte[] bytes) {
            this.bytes = bytes.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Bytes that && Arrays.equals(bytes, that.bytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(bytes);
        }
    }
}
