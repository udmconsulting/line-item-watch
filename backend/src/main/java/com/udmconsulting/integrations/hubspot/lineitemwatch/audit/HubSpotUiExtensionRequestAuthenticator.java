package com.udmconsulting.integrations.hubspot.lineitemwatch.audit;

import com.udmconsulting.integrations.hubspot.authentication.HubSpotRequestAuthenticationException;
import com.udmconsulting.integrations.hubspot.authentication.HubSpotV3RequestSignatureVerifier;
import com.udmconsulting.integrations.hubspot.config.HubSpotUiExtensionProperties;
import com.udmconsulting.platform.connection.domain.ExternalAccountId;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "hubspot.ui-extension", name = "enabled", havingValue = "true")
final class HubSpotUiExtensionRequestAuthenticator {

    static final String SIGNATURE_HEADER = "X-HubSpot-Signature-v3";
    static final String TIMESTAMP_HEADER = "X-HubSpot-Request-Timestamp";
    private static final int MAX_QUERY_LENGTH = 4096;
    private static final Set<String> ALLOWED_PARAMETERS = Set.of(
            "portalId", "userId", "userEmail", "appId",
            "lineItemsLimit", "lineItemsCursor", "eventsLimit", "eventsCursor");
    private static final Map<String, Character> SIGNATURE_DECODE = Map.ofEntries(
            Map.entry("3A", ':'), Map.entry("2F", '/'), Map.entry("3F", '?'),
            Map.entry("40", '@'), Map.entry("21", '!'), Map.entry("24", '$'),
            Map.entry("27", '\''), Map.entry("28", '('), Map.entry("29", ')'),
            Map.entry("2A", '*'), Map.entry("2C", ','), Map.entry("3B", ';'));

    private final HubSpotUiExtensionProperties properties;
    private final HubSpotV3RequestSignatureVerifier signatureVerifier;

    HubSpotUiExtensionRequestAuthenticator(
            HubSpotUiExtensionProperties properties,
            HubSpotV3RequestSignatureVerifier signatureVerifier) {
        this.properties = properties;
        this.signatureVerifier = signatureVerifier;
    }

    AuthenticatedHubSpotUiCaller authenticate(HttpServletRequest request) {
        if (!"GET".equals(request.getMethod())
                || hasRequestBody(request)) {
            throw new HubSpotUiExtensionAuthenticationException("unsupported signed request shape");
        }
        String rawQuery = request.getQueryString();
        if (rawQuery == null || rawQuery.length() > MAX_QUERY_LENGTH) {
            throw new HubSpotUiExtensionAuthenticationException("missing or oversized signed query");
        }
        validateRawQuery(rawQuery, request);
        for (Map.Entry<String, String[]> parameter : request.getParameterMap().entrySet()) {
            if (!ALLOWED_PARAMETERS.contains(parameter.getKey())
                    || parameter.getValue() == null
                    || parameter.getValue().length != 1) {
                throw new HubSpotUiExtensionAuthenticationException("invalid signed query parameters");
            }
        }

        String portalId = metadata(request, "portalId", 64, true);
        metadata(request, "userId", 64, true);
        String userEmail = metadata(request, "userEmail", 320, false);
        if (userEmail.isBlank()) {
            throw new HubSpotUiExtensionAuthenticationException("invalid signed user metadata");
        }
        String appId = metadata(request, "appId", 64, true);
        if (!properties.appId().equals(appId)) {
            throw new HubSpotUiExtensionAuthenticationException("signed app does not match");
        }

        String signature = singleHeader(request, SIGNATURE_HEADER, 128);
        String timestamp = singleHeader(request, TIMESTAMP_HEADER, 32);
        String signedUri = properties.publicOrigin()
                + request.getRequestURI()
                + "?"
                + decodeForV3Signature(rawQuery);
        try {
            signatureVerifier.verify(
                    request.getMethod(),
                    signedUri,
                    new byte[0],
                    signature,
                    timestamp);
        } catch (HubSpotRequestAuthenticationException exception) {
            throw new HubSpotUiExtensionAuthenticationException("signed request validation failed");
        }
        return new AuthenticatedHubSpotUiCaller(new ExternalAccountId(portalId));
    }

    private static boolean hasRequestBody(HttpServletRequest request) {
        if (request.getContentLengthLong() > 0
                || request.getHeader("Transfer-Encoding") != null) {
            return true;
        }
        try {
            return request.getInputStream().read() != -1;
        } catch (IOException exception) {
            throw new HubSpotUiExtensionAuthenticationException("signed request body could not be read");
        }
    }

    private static void validateRawQuery(String rawQuery, HttpServletRequest request) {
        Set<String> names = new HashSet<>();
        for (String component : rawQuery.split("&", -1)) {
            int separator = component.indexOf('=');
            if (separator <= 0) {
                throw new HubSpotUiExtensionAuthenticationException("invalid signed query syntax");
            }
            String rawName = component.substring(0, separator);
            String name = strictFormDecode(rawName);
            String value = strictFormDecode(component.substring(separator + 1));
            String[] servletValues = request.getParameterMap().get(name);
            if (!rawName.equals(name)
                    || !ALLOWED_PARAMETERS.contains(name)
                    || !names.add(name)
                    || servletValues == null
                    || servletValues.length != 1
                    || !value.equals(servletValues[0])) {
                throw new HubSpotUiExtensionAuthenticationException("ambiguous signed query parameters");
            }
        }
        if (names.size() != request.getParameterMap().size()) {
            throw new HubSpotUiExtensionAuthenticationException("ambiguous signed query parameters");
        }
    }

    private static String strictFormDecode(String value) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '%') {
                if (index + 2 >= value.length()) {
                    throw new HubSpotUiExtensionAuthenticationException("malformed signed query encoding");
                }
                int high = Character.digit(value.charAt(index + 1), 16);
                int low = Character.digit(value.charAt(index + 2), 16);
                if (high < 0 || low < 0) {
                    throw new HubSpotUiExtensionAuthenticationException("malformed signed query encoding");
                }
                bytes.write((high << 4) | low);
                index += 2;
            } else if (current == '+') {
                bytes.write(' ');
            } else if (current <= 0x7f) {
                bytes.write(current);
            } else {
                throw new HubSpotUiExtensionAuthenticationException("non-ASCII signed query octet");
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray()))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new HubSpotUiExtensionAuthenticationException("malformed signed query encoding");
        }
    }

    private static String metadata(
            HttpServletRequest request, String name, int maxLength, boolean decimal) {
        String[] values = request.getParameterMap().get(name);
        if (values == null || values.length != 1) {
            throw new HubSpotUiExtensionAuthenticationException("missing signed metadata");
        }
        String value = values[0];
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new HubSpotUiExtensionAuthenticationException("invalid signed metadata");
        }
        if (decimal && !value.matches("[1-9][0-9]*")) {
            throw new HubSpotUiExtensionAuthenticationException("invalid signed numeric metadata");
        }
        return value;
    }

    private static String singleHeader(HttpServletRequest request, String name, int maxLength) {
        Enumeration<String> headers = request.getHeaders(name);
        if (headers == null || !headers.hasMoreElements()) {
            throw new HubSpotUiExtensionAuthenticationException("missing signed request header");
        }
        String value = headers.nextElement();
        if (headers.hasMoreElements()
                || value == null
                || value.isBlank()
                || value.length() > maxLength) {
            throw new HubSpotUiExtensionAuthenticationException("invalid signed request header");
        }
        return value;
    }

    static String decodeForV3Signature(String rawQuery) {
        StringBuilder decoded = new StringBuilder(rawQuery.length());
        for (int index = 0; index < rawQuery.length(); index++) {
            if (rawQuery.charAt(index) == '%' && index + 2 < rawQuery.length()) {
                String code = rawQuery.substring(index + 1, index + 3).toUpperCase(Locale.ROOT);
                Character replacement = SIGNATURE_DECODE.get(code);
                if (replacement != null) {
                    decoded.append(replacement);
                    index += 2;
                    continue;
                }
            }
            decoded.append(rawQuery.charAt(index));
        }
        return decoded.toString();
    }
}
