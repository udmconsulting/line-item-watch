package com.udmconsulting.platform.credential.application;

import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.credential.domain.ConnectionCredential;
import com.udmconsulting.platform.credential.domain.EncryptedSecret;
import java.util.Optional;
import java.util.Set;

public interface ConnectionCredentialStore {

    Optional<ConnectionCredential> findByConnectionId(PlatformConnectionId connectionId);

    boolean replaceIfGeneration(
            PlatformConnectionId connectionId,
            long expectedGeneration,
            EncryptedSecret replacement,
            Set<String> grantedScopes);

    boolean requireReauthenticationIfGeneration(
            PlatformConnectionId connectionId, long expectedGeneration);

    boolean disconnectIfGeneration(PlatformConnectionId connectionId, long expectedGeneration);
}
