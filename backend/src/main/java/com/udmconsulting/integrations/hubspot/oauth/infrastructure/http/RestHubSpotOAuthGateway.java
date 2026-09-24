package com.udmconsulting.integrations.hubspot.oauth.infrastructure.http;

import com.udmconsulting.integrations.hubspot.config.HubSpotOAuthProperties;
import com.udmconsulting.integrations.hubspot.oauth.application.HubSpotAuthorizationException;
import com.udmconsulting.integrations.hubspot.oauth.application.HubSpotInactiveAccessTokenException;
import com.udmconsulting.integrations.hubspot.oauth.application.HubSpotOAuthGateway;
import com.udmconsulting.integrations.hubspot.oauth.application.HubSpotOAuthFailureCategory;
import com.udmconsulting.integrations.hubspot.oauth.application.HubSpotProviderUnavailableException;
import com.udmconsulting.integrations.hubspot.oauth.application.HubSpotTokenMetadataException;
import com.udmconsulting.integrations.hubspot.oauth.application.HubSpotUninstallException;
import com.udmconsulting.integrations.hubspot.oauth.application.InvalidRefreshCredentialException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.JacksonException;

@Component
public final class RestHubSpotOAuthGateway implements HubSpotOAuthGateway {

    static final String TOKEN_PATH = "/oauth/2026-09/token";
    static final String INTROSPECTION_PATH = "/oauth/2026-09/token/introspect";
    static final String REVOKE_PATH = "/oauth/2026-09/token/revoke";
    static final String UNINSTALL_PATH = "/appinstalls/2026-09/external-install";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final HubSpotOAuthProperties properties;

    public RestHubSpotOAuthGateway(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            HubSpotOAuthProperties properties) {
        this.restClient = restClientBuilder.baseUrl(properties.apiBaseUrl().toString()).build();
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public IssuedAuthorizationTokens exchangeAuthorizationCode(String authorizationCode) {
        MultiValueMap<String, String> form = commonTokenForm("authorization_code");
        form.add("code", authorizationCode);
        form.add("redirect_uri", properties.redirectUri().toString());
        JsonNode response = tokenRequest(form, false);
        validateIssuedAccessToken(response);
        return new IssuedAuthorizationTokens(
                requiredText(response, "access_token"),
                requiredText(response, "refresh_token"));
    }

    @Override
    public IssuedRefreshTokens refresh(String refreshToken) {
        MultiValueMap<String, String> form = commonTokenForm("refresh_token");
        form.add("refresh_token", refreshToken);
        JsonNode response = tokenRequest(form, true);
        validateIssuedAccessToken(response);
        String replacement = optionalText(response, "refresh_token");
        return new IssuedRefreshTokens(
                requiredText(response, "access_token"),
                Optional.ofNullable(replacement));
    }

    @Override
    public AccessTokenMetadata introspectAccessToken(String accessToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        form.add("token", accessToken);
        form.add("token_type_hint", "access_token");
        JsonNode response;
        try {
            response = restClient.post()
                    .uri(INTROSPECTION_PATH)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .exchange((request, providerResponse) -> {
                        if (providerResponse.getStatusCode().is2xxSuccessful()) {
                            return readIntrospectionBody(providerResponse.getBody());
                        }
                        if (providerResponse.getStatusCode().is5xxServerError()
                                || providerResponse.getStatusCode().value() == 429) {
                            throw new HubSpotProviderUnavailableException(
                                    HubSpotOAuthFailureCategory.TOKEN_INTROSPECTION_PROVIDER_UNAVAILABLE);
                        }
                        throw new HubSpotAuthorizationException(
                                HubSpotOAuthFailureCategory.TOKEN_INTROSPECTION_REJECTED);
                    });
        } catch (ResourceAccessException exception) {
            throw new HubSpotProviderUnavailableException(
                    HubSpotOAuthFailureCategory.TOKEN_INTROSPECTION_PROVIDER_UNAVAILABLE, exception);
        }
        return validateIntrospection(response);
    }

    @Override
    public void revoke(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        form.add("token", refreshToken);
        form.add("token_type_hint", "refresh_token");
        try {
            restClient.post()
                    .uri(REVOKE_PATH)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            throw classifyTechnicalFailure(response.getStatusCode().value());
                        }
                        return null;
                    });
        } catch (ResourceAccessException exception) {
            throw new HubSpotProviderUnavailableException(
                    HubSpotOAuthFailureCategory.TOKEN_EXCHANGE_PROVIDER_UNAVAILABLE, exception);
        }
    }

    @Override
    public void uninstall(String accessToken) {
        try {
            restClient.delete()
                    .uri(UNINSTALL_PATH)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            if (response.getStatusCode().is5xxServerError()
                                    || response.getStatusCode().value() == 429) {
                                throw new HubSpotProviderUnavailableException(
                                        HubSpotOAuthFailureCategory.TOKEN_EXCHANGE_PROVIDER_UNAVAILABLE);
                            }
                            throw new HubSpotUninstallException();
                        }
                        return null;
                    });
        } catch (ResourceAccessException exception) {
            throw new HubSpotProviderUnavailableException(
                    HubSpotOAuthFailureCategory.TOKEN_EXCHANGE_PROVIDER_UNAVAILABLE, exception);
        }
    }

    private JsonNode tokenRequest(MultiValueMap<String, String> form, boolean refresh) {
        try {
            return restClient.post()
                    .uri(TOKEN_PATH)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .exchange((request, response) -> {
                        if (response.getStatusCode().is2xxSuccessful()) {
                            return readTokenBody(response.getBody());
                        }
                        JsonNode body = readBodyIfJson(response.getBody());
                        if (refresh && isConfirmedInvalidCredential(body)) {
                            throw new InvalidRefreshCredentialException();
                        }
                        if (response.getStatusCode().is5xxServerError()
                                || response.getStatusCode().value() == 429) {
                            throw new HubSpotProviderUnavailableException(
                                    HubSpotOAuthFailureCategory.TOKEN_EXCHANGE_PROVIDER_UNAVAILABLE);
                        }
                        throw new HubSpotAuthorizationException(
                                HubSpotOAuthFailureCategory.TOKEN_EXCHANGE_REJECTED);
                    });
        } catch (ResourceAccessException exception) {
            throw new HubSpotProviderUnavailableException(
                    HubSpotOAuthFailureCategory.TOKEN_EXCHANGE_PROVIDER_UNAVAILABLE, exception);
        }
    }

    private MultiValueMap<String, String> commonTokenForm(String grantType) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", grantType);
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        return form;
    }

    private JsonNode readTokenBody(java.io.InputStream inputStream) {
        try {
            JsonNode body = objectMapper.readTree(inputStream);
            if (body == null) {
                throw new HubSpotAuthorizationException(
                        HubSpotOAuthFailureCategory.TOKEN_RESPONSE_INVALID);
            }
            return body;
        } catch (JacksonException exception) {
            throw new HubSpotAuthorizationException(
                    HubSpotOAuthFailureCategory.TOKEN_RESPONSE_INVALID);
        }
    }

    private JsonNode readIntrospectionBody(java.io.InputStream inputStream) {
        try {
            JsonNode body = objectMapper.readTree(inputStream);
            if (body == null) {
                throw new HubSpotTokenMetadataException();
            }
            return body;
        } catch (JacksonException exception) {
            throw new HubSpotTokenMetadataException();
        }
    }

    private JsonNode readBodyIfJson(java.io.InputStream inputStream) {
        try {
            return objectMapper.readTree(inputStream);
        } catch (JacksonException exception) {
            return null;
        }
    }

    private static RuntimeException classifyTechnicalFailure(int status) {
        if (status == 429 || status >= 500) {
            return new HubSpotProviderUnavailableException(
                    HubSpotOAuthFailureCategory.TOKEN_EXCHANGE_PROVIDER_UNAVAILABLE);
        }
        return new HubSpotAuthorizationException(
                HubSpotOAuthFailureCategory.TOKEN_EXCHANGE_REJECTED);
    }

    private static boolean isConfirmedInvalidCredential(JsonNode body) {
        for (String field : new String[] {"error", "errorType", "category"}) {
            String value = optionalText(body, field);
            if (value != null) {
                String normalized = value.toLowerCase(Locale.ROOT);
                if (normalized.equals("invalid_grant")
                        || normalized.equals("invalid_refresh_token")
                        || normalized.equals("revoked_refresh_token")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String requiredText(JsonNode response, String field) {
        String value = optionalText(response, field);
        if (value == null) {
            throw new HubSpotAuthorizationException(
                    HubSpotOAuthFailureCategory.TOKEN_RESPONSE_INVALID);
        }
        return value;
    }

    private static void validateIssuedAccessToken(JsonNode response) {
        requiredText(response, "access_token");
        if (!"access_token".equals(optionalText(response, "token_use"))) {
            throw new HubSpotAuthorizationException(
                    HubSpotOAuthFailureCategory.TOKEN_RESPONSE_INVALID);
        }
        String tokenType = optionalText(response, "token_type");
        if (tokenType == null || !"bearer".equalsIgnoreCase(tokenType)) {
            throw new HubSpotAuthorizationException(
                    HubSpotOAuthFailureCategory.TOKEN_RESPONSE_INVALID);
        }
        JsonNode expiresIn = response.get("expires_in");
        if (expiresIn == null || !expiresIn.isIntegralNumber()
                || !expiresIn.canConvertToLong() || expiresIn.longValue() <= 0) {
            throw new HubSpotAuthorizationException(
                    HubSpotOAuthFailureCategory.TOKEN_RESPONSE_INVALID);
        }
    }

    private static String optionalText(JsonNode response, String field) {
        if (response == null) {
            return null;
        }
        JsonNode value = response.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            return null;
        }
        return value.asText();
    }

    private AccessTokenMetadata validateIntrospection(JsonNode response) {
        JsonNode active = response.get("active");
        if (active == null || !active.isBoolean()) {
            throw new HubSpotTokenMetadataException();
        }
        if (!active.booleanValue()) {
            throw new HubSpotInactiveAccessTokenException();
        }
        if (!"access_token".equals(optionalText(response, "token_use"))) {
            throw new HubSpotTokenMetadataException();
        }
        String tokenType = optionalText(response, "token_type");
        if (tokenType == null || !"bearer".equalsIgnoreCase(tokenType)) {
            throw new HubSpotTokenMetadataException();
        }
        if (!properties.clientId().equals(optionalText(response, "client_id"))) {
            throw new HubSpotTokenMetadataException();
        }
        JsonNode hubId = response.get("hub_id");
        if (hubId == null || !hubId.isIntegralNumber() || !hubId.canConvertToLong()
                || hubId.longValue() <= 0) {
            throw new HubSpotTokenMetadataException();
        }
        return new AccessTokenMetadata(Long.toString(hubId.longValue()), scopes(response));
    }

    private static Set<String> scopes(JsonNode response) {
        JsonNode scopes = response.get("scopes");
        if (scopes == null || !scopes.isArray()) {
            throw new HubSpotTokenMetadataException();
        }
        Set<String> values = new LinkedHashSet<>();
        for (JsonNode value : scopes) {
            if (!value.isTextual() || value.textValue().isBlank()) {
                throw new HubSpotTokenMetadataException();
            }
            values.add(value.textValue());
        }
        return Set.copyOf(values);
    }
}
