package com.udmconsulting.integrations.hubspot.oauth.application;

import java.util.Optional;
import java.util.Set;

public interface HubSpotOAuthGateway {

    AuthorizationGrant exchangeAuthorizationCode(String authorizationCode);

    RefreshGrant refresh(String refreshToken);

    void revoke(String refreshToken);

    void uninstall(String accessToken);

    record AuthorizationGrant(
            String accessToken,
            String refreshToken,
            String externalAccountId,
            Set<String> grantedScopes) {

        public AuthorizationGrant {
            grantedScopes = Set.copyOf(grantedScopes);
        }

        @Override
        public String toString() {
            return "AuthorizationGrant[<redacted>]";
        }
    }

    record RefreshGrant(
            String accessToken,
            Optional<String> replacementRefreshToken,
            String externalAccountId,
            Set<String> grantedScopes) {

        public RefreshGrant {
            replacementRefreshToken = replacementRefreshToken == null
                    ? Optional.empty() : replacementRefreshToken;
            grantedScopes = Set.copyOf(grantedScopes);
        }

        @Override
        public String toString() {
            return "RefreshGrant[<redacted>]";
        }
    }
}
