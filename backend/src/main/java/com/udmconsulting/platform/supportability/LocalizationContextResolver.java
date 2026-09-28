package com.udmconsulting.platform.supportability;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class LocalizationContextResolver {

    public static final Locale FALLBACK_LOCALE = Locale.ENGLISH;
    public static final ZoneId FALLBACK_TIME_ZONE = ZoneOffset.UTC;

    private LocalizationContextResolver() {
    }

    public static ResolvedLocalizationContext resolve(
            String userLanguage,
            String userLocale,
            String portalTimeZone,
            Set<Locale> supportedMessageLocales,
            Set<Locale> supportedFormatLocales) {
        return new ResolvedLocalizationContext(
                resolveLocale(userLanguage, supportedMessageLocales),
                resolveLocale(userLocale, supportedFormatLocales),
                resolveTimeZone(portalTimeZone));
    }

    public static Locale resolveLocale(String value, Set<Locale> supportedLocales) {
        Objects.requireNonNull(supportedLocales, "supportedLocales must not be null");
        Map<String, Locale> supported = new LinkedHashMap<>();
        for (Locale locale : supportedLocales) {
            Objects.requireNonNull(locale, "supported locale must not be null");
            supported.put(locale.toLanguageTag(), locale);
        }
        if (value == null || value.isBlank()) {
            return FALLBACK_LOCALE;
        }
        Locale candidate;
        try {
            candidate = new Locale.Builder()
                    .setLanguageTag(value.trim().replace('_', '-'))
                    .build();
        } catch (RuntimeException exception) {
            return FALLBACK_LOCALE;
        }
        if (candidate.getLanguage().isBlank() || candidate.equals(Locale.ROOT)) {
            return FALLBACK_LOCALE;
        }
        Locale exact = supported.get(candidate.toLanguageTag());
        if (exact != null) {
            return exact;
        }
        Locale base = supported.get(candidate.getLanguage());
        return base == null ? FALLBACK_LOCALE : base;
    }

    public static ZoneId resolveTimeZone(String value) {
        if (value == null || value.isBlank()) {
            return FALLBACK_TIME_ZONE;
        }
        try {
            return ZoneId.of(value.trim());
        } catch (DateTimeException exception) {
            return FALLBACK_TIME_ZONE;
        }
    }

    public record ResolvedLocalizationContext(
            Locale messageLocale, Locale formatLocale, ZoneId timeZone) {

        public ResolvedLocalizationContext {
            Objects.requireNonNull(messageLocale);
            Objects.requireNonNull(formatLocale);
            Objects.requireNonNull(timeZone);
        }
    }
}
