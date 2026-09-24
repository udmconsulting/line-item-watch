package com.udmconsulting.integrations.hubspot.oauth.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public final class OAuthStateService {

    static final Duration STATE_LIFETIME = Duration.ofMinutes(10);
    static final Duration RETENTION = Duration.ofHours(24);
    private static final int STATE_BYTES = 32;

    private final OAuthStateStore stateStore;
    private final Clock clock;
    private final SecureRandom secureRandom;

    @Autowired
    public OAuthStateService(OAuthStateStore stateStore, Clock clock) {
        this(stateStore, clock, new SecureRandom());
    }

    OAuthStateService(OAuthStateStore stateStore, Clock clock, SecureRandom secureRandom) {
        this.stateStore = Objects.requireNonNull(stateStore);
        this.clock = Objects.requireNonNull(clock);
        this.secureRandom = Objects.requireNonNull(secureRandom);
    }

    public IssuedState issue() {
        byte[] rawState = new byte[STATE_BYTES];
        secureRandom.nextBytes(rawState);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(rawState);
        Instant createdAt = clock.instant();
        UUID correlationId = UUID.randomUUID();
        stateStore.deleteRetainedBefore(createdAt.minus(RETENTION));
        stateStore.store(hash(state), correlationId, createdAt, createdAt.plus(STATE_LIFETIME));
        return new IssuedState(state, correlationId);
    }

    public UUID consume(String state) {
        if (state == null || state.isBlank()) {
            throw new OAuthStateException(OAuthStateException.Reason.INVALID);
        }
        OAuthStateStore.Consumption consumption = stateStore.consume(hash(state), clock.instant());
        return switch (consumption.result()) {
            case CONSUMED -> Objects.requireNonNull(consumption.correlationId());
            case INVALID -> throw new OAuthStateException(OAuthStateException.Reason.INVALID);
            case EXPIRED -> throw new OAuthStateException(OAuthStateException.Reason.EXPIRED);
            case REPLAYED -> throw new OAuthStateException(OAuthStateException.Reason.REPLAYED);
        };
    }

    static byte[] hash(String state) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(state.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record IssuedState(String value, UUID correlationId) {

        @Override
        public String toString() {
            return "IssuedState[value=<redacted>, correlationId=" + correlationId + "]";
        }
    }
}
