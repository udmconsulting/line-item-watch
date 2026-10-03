package com.udmconsulting.platform.credential.application;

import com.udmconsulting.platform.activity.application.ActivityContext;
import com.udmconsulting.platform.credential.application.ConnectionCredentialStore.CredentialRewrapCandidate;
import com.udmconsulting.platform.credential.domain.EncryptedSecret;
import com.udmconsulting.platform.tenant.domain.TenantId;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public final class CredentialKeyRotation {

    private final ConnectionCredentialStore store;
    private final SecretProtector protector;
    private final CredentialKeyRotationMetrics metrics;

    public CredentialKeyRotation(
            ConnectionCredentialStore store,
            SecretProtector protector,
            CredentialKeyRotationMetrics metrics) {
        this.store = Objects.requireNonNull(store);
        this.protector = Objects.requireNonNull(protector);
        this.metrics = Objects.requireNonNull(metrics);
    }

    public RewrapResult rewrap(TenantId tenantId, int limit, ActivityContext activityContext) {
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(activityContext);
        String previousKeyId = requirePreviousKey();
        List<CredentialRewrapCandidate> candidates =
                store.findForRewrap(tenantId, previousKeyId, limit);
        int rewrapped = 0;
        int concurrentChanges = 0;
        for (CredentialRewrapCandidate candidate : candidates) {
            SecretContext context = new SecretContext(
                    candidate.provider(), candidate.connectionId());
            String plaintext = protector.reveal(candidate.encryptedSecret(), context);
            EncryptedSecret replacement = protector.protect(plaintext, context);
            plaintext = null;
            if (store.rewrapIfUnchanged(candidate, replacement, activityContext)) {
                rewrapped++;
            } else {
                concurrentChanges++;
            }
        }
        long remaining = store.countByKeyId(tenantId, previousKeyId);
        metrics.remaining(remaining);
        return new RewrapResult(rewrapped, concurrentChanges, remaining);
    }

    public long remaining(TenantId tenantId) {
        long remaining = store.countByKeyId(tenantId, requirePreviousKey());
        metrics.remaining(remaining);
        return remaining;
    }

    private String requirePreviousKey() {
        return protector.previousKeyId().orElseThrow(() ->
                new IllegalStateException("No previous credential encryption key is configured"));
    }

    public record RewrapResult(int rewrapped, int concurrentChanges, long remaining) {
    }
}
