package com.udmconsulting.integrations.hubspot.oauth.infrastructure.web;

import com.udmconsulting.integrations.hubspot.oauth.application.HubSpotInstallationUseCase;
import com.udmconsulting.integrations.hubspot.oauth.application.HubSpotAuthorizationException;
import com.udmconsulting.integrations.hubspot.oauth.application.OAuthStateException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/integrations/hubspot/oauth")
public final class HubSpotOAuthController {

    private static final int AUTHORIZATION_CODE_MAX_LENGTH = 2048;
    private static final int PROVIDER_ERROR_MAX_LENGTH = 256;
    private static final java.util.regex.Pattern STATE_PATTERN =
            java.util.regex.Pattern.compile("[A-Za-z0-9_-]{43}");

    private final HubSpotInstallationUseCase installationService;

    public HubSpotOAuthController(HubSpotInstallationUseCase installationService) {
        this.installationService = installationService;
    }

    @GetMapping("/install")
    public ResponseEntity<Void> install() {
        HubSpotInstallationUseCase.InstallationStart start = installationService.beginInstallation();
        return OAuthHttpResponses.secured(ResponseEntity.status(302))
                .location(start.authorizationUri())
                .build();
    }

    @GetMapping(value = "/callback", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> callback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error) {
        validateState(state);
        boolean hasCode = code != null;
        boolean hasError = error != null;
        if (hasCode == hasError
                || (hasCode && (code.isBlank() || code.length() > AUTHORIZATION_CODE_MAX_LENGTH))
                || (hasError && (error.isBlank() || error.length() > PROVIDER_ERROR_MAX_LENGTH))) {
            rejectAfterConsumingState(state);
        }
        if (hasError) {
            rejectAfterConsumingState(state);
        }
        installationService.completeInstallation(state, code);
        return OAuthHttpResponses.success();
    }

    private static void validateState(String state) {
        if (state == null || state.isBlank() || !STATE_PATTERN.matcher(state).matches()) {
            throw new OAuthStateException(OAuthStateException.Reason.INVALID);
        }
    }

    private void rejectAfterConsumingState(String state) {
        installationService.rejectInstallation(state);
        throw new HubSpotAuthorizationException();
    }
}
