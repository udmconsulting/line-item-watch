package com.udmconsulting.platform.supportability;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class LocalizationContractTest {

    private static final Pattern ERROR_MAPPING = Pattern.compile(
            "\\\"([A-Z_]+)\\\"\\s*:\\s*\\\"errors\\.([A-Z_]+)\\\"");

    @Test
    void contractCoversPublicErrorsAndStableSemanticNamespacesWithoutVisibleCopy() throws Exception {
        String contract = contract();
        assertThat(contract).contains("\"version\": 1", "\"fallbackLocale\": \"en\"");

        Set<String> covered = new HashSet<>();
        Matcher matcher = ERROR_MAPPING.matcher(contract);
        while (matcher.find()) {
            assertThat(matcher.group(2)).isEqualTo(matcher.group(1));
            covered.add(matcher.group(1));
        }
        assertThat(covered).containsExactlyInAnyOrder(
                java.util.Arrays.stream(PublicErrorCode.values())
                        .map(Enum::name)
                        .toArray(String[]::new));

        assertThat(contract).contains(
                "\"errors\"", "\"fields\"", "\"events\"", "\"valueStates\"",
                "\"membership\"", "\"historyCoverage\"", "\"common\""
        ).doesNotContain("Something went wrong", "Try again", "Contact support");

        var namespaces = JsonMapper.builder().build().readTree(contract).path("keyNamespaces");
        assertThat(namespaces.propertyStream().map(java.util.Map.Entry::getKey).toList())
                .containsExactlyInAnyOrder(
                        "errors", "fields", "events", "valueStates",
                        "membership", "historyCoverage", "common")
                .doesNotHaveDuplicates();
        namespaces.properties().forEach(entry -> assertThat(entry.getValue().textValue())
                .startsWith(entry.getKey() + ".")
                .doesNotContain(" "));
    }

    @Test
    void documentedLocaleResolutionVectorsAreDeterministic() throws Exception {
        String contract = contract();
        assertThat(contract).contains(
                "\"input\": \" en_US \"",
                "\"input\": \"en-GB\"",
                "\"input\": \"hu-HU\"",
                "\"input\": \"not_a_locale_@\"",
                "\"input\": \"\"");
        assertThat(resolve(" en_US ", Set.of("en", "en-US"))).isEqualTo("en-US");
        assertThat(resolve("en-GB", Set.of("en"))).isEqualTo("en");
        assertThat(resolve("hu-HU", Set.of("en"))).isEqualTo("en");
        assertThat(resolve("not_a_locale_@", Set.of("en"))).isEqualTo("en");
        assertThat(resolve("", Set.of("en"))).isEqualTo("en");
        assertThat(LocalizationContextResolver.resolveTimeZone(" Europe/Budapest ").getId())
                .isEqualTo("Europe/Budapest");
        assertThat(LocalizationContextResolver.resolveTimeZone("invalid/timezone"))
                .isEqualTo(ZoneOffset.UTC);
    }

    private String contract() throws Exception {
        try (var input = getClass().getResourceAsStream(
                "/supportability/localization-contract-v1.json")) {
            assertThat(input).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String resolve(String input, Set<String> supported) {
        Set<Locale> locales = supported.stream().map(Locale::forLanguageTag)
                .collect(java.util.stream.Collectors.toSet());
        return LocalizationContextResolver.resolveLocale(input, locales).toLanguageTag();
    }
}
