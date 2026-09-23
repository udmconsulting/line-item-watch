package com.udmconsulting.platform.credential.application;

import com.udmconsulting.platform.connection.domain.PlatformConnection;
import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.credential.domain.ConnectionCredential;
import com.udmconsulting.platform.credential.domain.EncryptedSecret;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public final class ConnectionCredentialService {

    private final ConnectionCredentialStore credentialStore;
    private final SecretProtector secretProtector;

    public ConnectionCredentialService(
            ConnectionCredentialStore credentialStore, SecretProtector secretProtector) {
        this.credentialStore = Objects.requireNonNull(credentialStore);
        this.secretProtector = Objects.requireNonNull(secretProtector);
    }

    public LoadedCredential load(PlatformConnection connection) {
        ConnectionCredential credential = credentialStore.findByConnectionId(connection.id())
                .orElseThrow(ReauthenticationRequiredException::new);
        SecretContext context = new SecretContext(connection.provider(), connection.id());
        String refreshToken = secretProtector.reveal(credential.refreshCredential(), context);
        return new LoadedCredential(
                connection.id(), connection.provider(), refreshToken,
                credential.grantedScopes(), credential.credentialGeneration());
    }

    public long replace(
            LoadedCredential loadedCredential,
            String replacementRefreshToken,
            Set<String> grantedScopes) {
        Objects.requireNonNull(replacementRefreshToken, "replacementRefreshToken must not be null");
        EncryptedSecret encrypted = secretProtector.protect(
                replacementRefreshToken,
                new SecretContext(loadedCredential.provider(), loadedCredential.connectionId()));
        boolean replaced = credentialStore.replaceIfGeneration(
                loadedCredential.connectionId(), loadedCredential.credentialGeneration(), encrypted, grantedScopes);
        if (!replaced) {
            throw new ConcurrentCredentialChangeException();
        }
        return loadedCredential.credentialGeneration() + 1;
    }

    public void requireReauthentication(LoadedCredential loadedCredential) {
        if (!credentialStore.requireReauthenticationIfGeneration(
                loadedCredential.connectionId(), loadedCredential.credentialGeneration())) {
            throw new ConcurrentCredentialChangeException();
        }
    }

    public void disconnect(PlatformConnectionId connectionId, long expectedGeneration) {
        if (!credentialStore.disconnectIfGeneration(connectionId, expectedGeneration)) {
            throw new ConcurrentCredentialChangeException();
        }
    }

    public record LoadedCredential(
            PlatformConnectionId connectionId,
            com.udmconsulting.platform.connection.domain.Provider provider,
            String refreshToken,
            Set<String> grantedScopes,
            long credentialGeneration) {

        public LoadedCredential {
            Objects.requireNonNull(connectionId);
            Objects.requireNonNull(provider);
            Objects.requireNonNull(refreshToken);
            grantedScopes = Set.copyOf(grantedScopes);
        }

        @Override
        public String toString() {
            return "LoadedCredential[connectionId=" + connectionId
                    + ", provider=" + provider
                    + ", refreshToken=<redacted>, grantedScopes=" + grantedScopes
                    + ", credentialGeneration=" + credentialGeneration + "]";
        }
    }
}
