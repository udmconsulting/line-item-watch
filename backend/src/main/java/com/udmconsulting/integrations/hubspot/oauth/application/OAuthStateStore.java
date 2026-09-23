package com.udmconsulting.integrations.hubspot.oauth.application;

import java.time.Instant;
import java.util.UUID;

public interface OAuthStateStore {

    void store(byte[] stateHash, UUID correlationId, Instant createdAt, Instant expiresAt);

    ConsumptionResult consume(byte[] stateHash, Instant consumedAt);

    void deleteRetainedBefore(Instant retentionThreshold);

    enum ConsumptionResult {
        CONSUMED,
        INVALID,
        EXPIRED,
        REPLAYED
    }
}
