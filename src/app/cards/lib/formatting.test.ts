import { describe, expect, it, vi } from "vitest";
import {
  createTranslator,
  resolveFormatLocale,
  resolveMessageLanguage,
  type TranslationKey,
} from "../i18n";
import {
  createValueFormatter,
  formatObservedValue,
  nextCalendarDate,
  portalDateStartInstant,
  resolveTimeZone,
} from "./formatting";

describe("locale and value formatting", () => {
  it.each([
    [" en_US ", "en"],
    ["en-GB", "en"],
    ["hu-HU", "hu"],
    ["not_a_locale_@", "en"],
    ["", "en"],
    [undefined, "en"],
  ])("resolves message locale %s", (input, expected) => {
    expect(resolveMessageLanguage(input)).toBe(expected);
  });

  it("keeps a supported exact format locale and falls back safely", () => {
    expect(resolveFormatLocale("hu_HU")).toBe("hu-HU");
    expect(resolveFormatLocale("not_a_locale_@")).toBe("en");
  });

  it("falls back to UTC for missing and invalid portal time zones", () => {
    expect(resolveTimeZone(undefined)).toBe("UTC");
    expect(resolveTimeZone("Mars/Olympus")).toBe("UTC");
    expect(resolveTimeZone("Europe/Budapest")).toBe("Europe/Budapest");
  });

  it("formats locale-aware numbers, timestamps, date-only values, and plurals", () => {
    const translate = createTranslator("hu");
    const formatter = createValueFormatter("hu-HU", "Europe/Budapest");

    expect(formatter.number("1234.50")).toMatch(/1(?:[ .\u00a0\u202f])?234,50/);
    expect(formatter.timestamp("2026-09-28T10:00:00Z")).toContain("12:00");
    expect(formatter.dateOnly("2026-09-28")).toMatch(/2026.*(?:09|szept).*28/);
    expect(formatter.delay("1", "days", translate)).toBe("1 nap");
    expect(formatter.delay("3", "months", translate)).toBe("3 hónap");
  });

  it("preserves high-precision and malformed canonical text instead of rounding", () => {
    const formatter = createValueFormatter("en-US", "UTC");
    expect(formatter.number("123456789012345.67")).toBe("123456789012345.67");
    expect(formatter.number("12.3.4")).toBe("12.3.4");
    expect(formatter.number("+12")).toBe("+12");
    expect(formatter.dateOnly("2026-02-30")).toBe("2026-02-30");
  });

  it("never infers a currency and distinguishes semantic states", () => {
    const translate = createTranslator("en");
    const formatter = createValueFormatter("en-US", "UTC");
    expect(
      formatObservedValue(
        "unitPrice",
        { state: "VALUE", value: "10.00", truncated: false },
        formatter,
        translate,
      ),
    ).toBe("10.00");
    expect(
      formatObservedValue(
        "unitPrice",
        { state: "UNKNOWN", value: null, truncated: false },
        formatter,
        translate,
      ),
    ).toBe("Unknown");
    expect(
      formatObservedValue(
        "unitPrice",
        { state: "ABSENT", value: null, truncated: false },
        formatter,
        translate,
      ),
    ).toBe("No value");
  });

  it("uses fallback copy and logs only a closed app-owned key when a locale key is missing", () => {
    const logger = vi.fn();
    const translate = createTranslator("hu", logger);
    expect(translate("common.title")).toBe("Line Item Watch");
    expect(logger).not.toHaveBeenCalled();

    expect(translate("raw.backend.value" as TranslationKey)).toBe(
      "Az információ nem érhető el.",
    );
    expect(logger).not.toHaveBeenCalled();
  });

  it("uses portal calendar days for Today, Yesterday, and locale headings", () => {
    const translate = createTranslator("en");
    const formatter = createValueFormatter("en-US", "Europe/Budapest");
    const now = new Date("2026-09-29T22:30:00Z");

    expect(formatter.dayKey("2026-09-29T22:15:00Z")).toBe("2026-09-30");
    expect(formatter.dayLabel("2026-09-29T22:15:00Z", now, translate)).toBe(
      "Today",
    );
    expect(formatter.dayLabel("2026-09-28T22:15:00Z", now, translate)).toBe(
      "Yesterday",
    );
    expect(
      formatter.dayLabel("2026-09-27T12:00:00Z", now, translate),
    ).toContain("September 27");
  });

  it("converts portal date boundaries across DST without browser-timezone input", () => {
    const spring = { year: 2026, month: 3, date: 29 };
    expect(portalDateStartInstant(spring, "Europe/Budapest")).toBe(
      "2026-03-28T23:00:00.000Z",
    );
    expect(
      portalDateStartInstant(nextCalendarDate(spring), "Europe/Budapest"),
    ).toBe("2026-03-29T22:00:00.000Z");

    const autumn = { year: 2026, month: 10, date: 25 };
    expect(portalDateStartInstant(autumn, "Europe/Budapest")).toBe(
      "2026-10-24T22:00:00.000Z",
    );
    expect(
      portalDateStartInstant(nextCalendarDate(autumn), "Europe/Budapest"),
    ).toBe("2026-10-25T23:00:00.000Z");
  });
});
