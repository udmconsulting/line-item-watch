package com.udmconsulting.integrations.hubspot.oauth.application;

import java.util.Optional;
import java.util.Set;

public interface HubSpotOAuthGateway {

    IssuedAuthorizationTokens exchangeAuthorizationCode(String authorizationCode);

    IssuedRefreshTokens refresh(String refreshToken);

    AccessTokenMetadata introspectAccessToken(String accessToken);

    void revoke(String refreshToken);

    void uninstall(String accessToken);

    record IssuedAuthorizationTokens(String accessToken, String refreshToken) {

        @Override
        public String toString() {
            return "IssuedAuthorizationTokens[<redacted>]";
        }
    }

    record IssuedRefreshTokens(String accessToken, Optional<String> replacementRefreshToken) {

        public IssuedRefreshTokens {
            replacementRefreshToken = replacementRefreshToken == null
                    ? Optional.empty() : replacementRefreshToken;
        }

        @Override
        public String toString() {
            return "IssuedRefreshTokens[<redacted>]";
        }
    }

    record AccessTokenMetadata(String externalAccountId, Set<String> grantedScopes) {

        public AccessTokenMetadata {
            grantedScopes = Set.copyOf(grantedScopes);
        }

        @Override
        public String toString() {
            return "AccessTokenMetadata[externalAccountId=" + externalAccountId
                    + ", grantedScopes=" + grantedScopes + "]";
        }
    }
}
