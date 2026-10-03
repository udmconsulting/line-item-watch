package com.udmconsulting.platform.credential.application;

import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.activity.application.ActivityContext;
import com.udmconsulting.platform.credential.domain.ConnectionCredential;
import com.udmconsulting.platform.credential.domain.EncryptedSecret;
import com.udmconsulting.platform.connection.domain.Provider;
import com.udmconsulting.platform.tenant.domain.TenantId;
import java.util.List;
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
            PlatformConnectionId connectionId,
            long expectedGeneration,
            ActivityContext activityContext);

    boolean disconnectIfGeneration(
            PlatformConnectionId connectionId,
            long expectedGeneration,
            ActivityContext activityContext);

    List<CredentialRewrapCandidate> findForRewrap(
            TenantId tenantId, String sourceKeyId, int limit);

    boolean rewrapIfUnchanged(
            CredentialRewrapCandidate candidate,
            EncryptedSecret replacement,
            ActivityContext activityContext);

    long countByKeyId(TenantId tenantId, String keyId);

    record CredentialRewrapCandidate(
            TenantId tenantId,
            PlatformConnectionId connectionId,
            Provider provider,
            long credentialGeneration,
            EncryptedSecret encryptedSecret) {
    }
}
