package com.udmconsulting.platform.credential.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.udmconsulting.platform.activity.application.ActivityContext;
import com.udmconsulting.platform.activity.domain.ActivityActor;
import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.connection.domain.Provider;
import com.udmconsulting.platform.credential.application.ConnectionCredentialStore.CredentialRewrapCandidate;
import com.udmconsulting.platform.credential.domain.ConnectionCredential;
import com.udmconsulting.platform.credential.domain.EncryptedSecret;
import com.udmconsulting.platform.credential.infrastructure.crypto.AesGcmSecretProtector;
import com.udmconsulting.platform.credential.infrastructure.crypto.KeyRingSecretProtector;
import com.udmconsulting.platform.tenant.domain.TenantId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CredentialKeyRotationTest {

    @Test
    void rewrapIsTenantScopedRestartableAndIdempotent() {
        TenantId targetTenant = TenantId.newId();
        TenantId otherTenant = TenantId.newId();
        PlatformConnectionId targetConnection = PlatformConnectionId.newId();
        PlatformConnectionId otherConnection = PlatformConnectionId.newId();
        AesGcmSecretProtector active = new AesGcmSecretProtector("active", key((byte) 2));
        AesGcmSecretProtector previous = new AesGcmSecretProtector("previous", key((byte) 1));
        KeyRingSecretProtector ring = new KeyRingSecretProtector(active, previous);
        InMemoryStore store = new InMemoryStore(List.of(
                candidate(targetTenant, targetConnection,
                        previous.protect("target-secret", context(targetConnection))),
                candidate(otherTenant, otherConnection,
                        previous.protect("other-secret", context(otherConnection)))));
        CredentialKeyRotation rotation = new CredentialKeyRotation(
                store, ring, new CredentialKeyRotationMetrics(new SimpleMeterRegistry()));

        CredentialKeyRotation.RewrapResult first =
                rotation.rewrap(targetTenant, 10, activity());
        CredentialKeyRotation.RewrapResult second =
                rotation.rewrap(targetTenant, 10, activity());

        assertThat(first).isEqualTo(new CredentialKeyRotation.RewrapResult(1, 0, 0));
        assertThat(second).isEqualTo(new CredentialKeyRotation.RewrapResult(0, 0, 0));
        assertThat(store.rows.get(0).encryptedSecret().keyId()).isEqualTo("active");
        assertThat(ring.reveal(store.rows.get(0).encryptedSecret(), context(targetConnection)))
                .isEqualTo("target-secret");
        assertThat(store.rows.get(1).encryptedSecret().keyId()).isEqualTo("previous");
        assertThat(store.seenTenants).containsOnly(targetTenant);
    }

    @Test
    void requiresAnExplicitPreviousKeyAndDoesNotExposePlaintextInFailure() {
        AesGcmSecretProtector active = new AesGcmSecretProtector("active", key((byte) 2));
        CredentialKeyRotation rotation = new CredentialKeyRotation(
                new InMemoryStore(List.of()), active,
                new CredentialKeyRotationMetrics(new SimpleMeterRegistry()));

        assertThatThrownBy(() -> rotation.rewrap(TenantId.newId(), 1, activity()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("secret");
    }

    private static CredentialRewrapCandidate candidate(
            TenantId tenantId, PlatformConnectionId connectionId, EncryptedSecret encrypted) {
        return new CredentialRewrapCandidate(
                tenantId, connectionId, Provider.HUBSPOT, 7, encrypted);
    }

    private static SecretContext context(PlatformConnectionId connectionId) {
        return new SecretContext(Provider.HUBSPOT, connectionId);
    }

    private static ActivityContext activity() {
        return new ActivityContext(ActivityActor.system(), UUID.randomUUID());
    }

    private static byte[] key(byte value) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, value);
        return bytes;
    }

    private static final class InMemoryStore implements ConnectionCredentialStore {
        private final List<CredentialRewrapCandidate> rows;
        private final List<TenantId> seenTenants = new ArrayList<>();

        private InMemoryStore(List<CredentialRewrapCandidate> rows) {
            this.rows = new ArrayList<>(rows);
        }

        @Override
        public List<CredentialRewrapCandidate> findForRewrap(
                TenantId tenantId, String sourceKeyId, int limit) {
            seenTenants.add(tenantId);
            return rows.stream()
                    .filter(row -> row.tenantId().equals(tenantId))
                    .filter(row -> row.encryptedSecret().keyId().equals(sourceKeyId))
                    .limit(limit)
                    .toList();
        }

        @Override
        public boolean rewrapIfUnchanged(
                CredentialRewrapCandidate candidate,
                EncryptedSecret replacement,
                ActivityContext activityContext) {
            int index = rows.indexOf(candidate);
            if (index < 0) {
                return false;
            }
            rows.set(index, new CredentialRewrapCandidate(
                    candidate.tenantId(), candidate.connectionId(), candidate.provider(),
                    candidate.credentialGeneration(), replacement));
            return true;
        }

        @Override
        public long countByKeyId(TenantId tenantId, String keyId) {
            seenTenants.add(tenantId);
            return rows.stream()
                    .filter(row -> row.tenantId().equals(tenantId))
                    .filter(row -> row.encryptedSecret().keyId().equals(keyId))
                    .count();
        }

        @Override
        public Optional<ConnectionCredential> findByConnectionId(PlatformConnectionId id) {
            return Optional.empty();
        }

        @Override
        public boolean replaceIfGeneration(
                PlatformConnectionId id, long generation, EncryptedSecret replacement,
                Set<String> scopes) {
            return false;
        }

        @Override
        public boolean requireReauthenticationIfGeneration(
                PlatformConnectionId id, long generation, ActivityContext context) {
            return false;
        }

        @Override
        public boolean disconnectIfGeneration(
                PlatformConnectionId id, long generation, ActivityContext context) {
            return false;
        }
    }
}
