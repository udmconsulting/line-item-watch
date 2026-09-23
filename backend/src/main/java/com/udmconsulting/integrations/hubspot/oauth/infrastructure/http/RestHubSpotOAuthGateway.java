package com.udmconsulting.integrations.hubspot.oauth.infrastructure.http;

import com.udmconsulting.integrations.hubspot.config.HubSpotOAuthProperties;
import com.udmconsulting.integrations.hubspot.oauth.application.HubSpotAuthorizationException;
import com.udmconsulting.integrations.hubspot.oauth.application.HubSpotOAuthGateway;
import com.udmconsulting.integrations.hubspot.oauth.application.HubSpotProviderUnavailableException;
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
    public AuthorizationGrant exchangeAuthorizationCode(String authorizationCode) {
        MultiValueMap<String, String> form = commonTokenForm("authorization_code");
        form.add("code", authorizationCode);
        form.add("redirect_uri", properties.redirectUri().toString());
        JsonNode response = tokenRequest(form, false);
        return new AuthorizationGrant(
                requiredText(response, "access_token"),
                requiredText(response, "refresh_token"),
                requiredAccountId(response),
                scopes(response));
    }

    @Override
    public RefreshGrant refresh(String refreshToken) {
        MultiValueMap<String, String> form = commonTokenForm("refresh_token");
        form.add("refresh_token", refreshToken);
        JsonNode response = tokenRequest(form, true);
        String replacement = optionalText(response, "refresh_token");
        return new RefreshGrant(
                requiredText(response, "access_token"),
                Optional.ofNullable(replacement),
                requiredAccountId(response),
                scopes(response));
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
            throw new HubSpotProviderUnavailableException(exception);
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
                                throw new HubSpotProviderUnavailableException();
                            }
                            throw new HubSpotUninstallException();
                        }
                        return null;
                    });
        } catch (ResourceAccessException exception) {
            throw new HubSpotProviderUnavailableException(exception);
        }
    }

    private JsonNode tokenRequest(MultiValueMap<String, String> form, boolean refresh) {
        try {
            return restClient.post()
                    .uri(TOKEN_PATH)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .exchange((request, response) -> {
                        JsonNode body = readBody(response.getBody());
                        if (response.getStatusCode().is2xxSuccessful()) {
                            return body;
                        }
                        if (refresh && isConfirmedInvalidCredential(body)) {
                            throw new InvalidRefreshCredentialException();
                        }
                        if (response.getStatusCode().is5xxServerError()
                                || response.getStatusCode().value() == 429) {
                            throw new HubSpotProviderUnavailableException();
                        }
                        throw new HubSpotAuthorizationException();
                    });
        } catch (ResourceAccessException exception) {
            throw new HubSpotProviderUnavailableException(exception);
        }
    }

    private MultiValueMap<String, String> commonTokenForm(String grantType) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", grantType);
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        return form;
    }

    private JsonNode readBody(java.io.InputStream inputStream) {
        try {
            JsonNode body = objectMapper.readTree(inputStream);
            if (body == null) {
                throw new HubSpotProviderUnavailableException();
            }
            return body;
        } catch (JacksonException exception) {
            throw new HubSpotProviderUnavailableException(exception);
        }
    }

    private static RuntimeException classifyTechnicalFailure(int status) {
        if (status == 429 || status >= 500) {
            return new HubSpotProviderUnavailableException();
        }
        return new HubSpotAuthorizationException();
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

    private static String requiredAccountId(JsonNode response) {
        for (String field : new String[] {"hub_id", "hubId"}) {
            JsonNode value = response.get(field);
            if (value != null && !value.isNull() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        throw new HubSpotAuthorizationException();
    }

    private static String requiredText(JsonNode response, String field) {
        String value = optionalText(response, field);
        if (value == null) {
            throw new HubSpotAuthorizationException();
        }
        return value;
    }

    private static String optionalText(JsonNode response, String field) {
        JsonNode value = response.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            return null;
        }
        return value.asText();
    }

    private static Set<String> scopes(JsonNode response) {
        JsonNode scopes = response.get("scopes");
        if (scopes == null || scopes.isNull()) {
            return Set.of();
        }
        Set<String> values = new LinkedHashSet<>();
        if (scopes.isArray()) {
            scopes.forEach(value -> values.add(value.asText()));
        } else {
            for (String value : scopes.asText().split("[ ,]+")) {
                if (!value.isBlank()) {
                    values.add(value);
                }
            }
        }
        return Set.copyOf(values);
    }
}
