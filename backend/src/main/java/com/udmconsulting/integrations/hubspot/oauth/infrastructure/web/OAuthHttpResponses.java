package com.udmconsulting.integrations.hubspot.oauth.infrastructure.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import java.nio.charset.StandardCharsets;

final class OAuthHttpResponses {

    private static final MediaType HTML_UTF8 =
            new MediaType("text", "html", StandardCharsets.UTF_8);

    static final String CONTENT_SECURITY_POLICY =
            "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";
    static final String SUCCESS_HTML = """
            <!doctype html><html lang="en"><head><meta charset="utf-8"><title>Installation complete</title></head>
            <body><main><h1>Installation complete</h1><p>You may close this window.</p></main></body></html>
            """;
    static final String ERROR_HTML = """
            <!doctype html><html lang="en"><head><meta charset="utf-8"><title>Installation not completed</title></head>
            <body><main><h1>Installation not completed</h1><p>Please start the installation again.</p></main></body></html>
            """;

    private OAuthHttpResponses() {
    }

    static ResponseEntity<String> success() {
        return secured(ResponseEntity.ok())
                .contentType(HTML_UTF8)
                .body(SUCCESS_HTML);
    }

    static ResponseEntity<String> error(HttpStatus status) {
        return secured(ResponseEntity.status(status))
                .contentType(HTML_UTF8)
                .body(ERROR_HTML);
    }

    static ResponseEntity.BodyBuilder secured(ResponseEntity.BodyBuilder builder) {
        return builder
                .header("Cache-Control", "no-store")
                .header("Pragma", "no-cache")
                .header("Referrer-Policy", "no-referrer")
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", CONTENT_SECURITY_POLICY);
    }
}
