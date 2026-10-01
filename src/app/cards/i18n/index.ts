import en from "./en.json";
import hu from "./hu.json";
import type {
  AuditEventType,
  HistoryCoverageMode,
  MembershipState,
  MonitoredProperty,
  ObservedValueState,
  PublicErrorCode,
} from "../lib/contracts";

export type SupportedLanguage = "en" | "hu";
export type LocalErrorCode =
  | "NETWORK"
  | "TIMEOUT"
  | "RATE_LIMITED"
  | "MALFORMED_RESPONSE"
  | "LOCAL_CONFIGURATION"
  | "LOCAL_CONTEXT";
export type ErrorCode = PublicErrorCode | LocalErrorCode;
export type CommonKey =
  | "title"
  | "intro"
  | "lineItems"
  | "recentChanges"
  | "filters"
  | "searchLabel"
  | "searchPlaceholder"
  | "searchDescription"
  | "eventTypeLabel"
  | "anyEventType"
  | "fieldLabel"
  | "anyField"
  | "lineItemIdLabel"
  | "lineItemIdDescription"
  | "fromDateLabel"
  | "throughDateLabel"
  | "applyFilters"
  | "clearAll"
  | "refresh"
  | "refreshing"
  | "lastRefreshed"
  | "filterValidationTitle"
  | "searchValidation"
  | "lineItemIdValidation"
  | "dateRangeValidation"
  | "searchFilterTag"
  | "eventTypeFilterTag"
  | "fieldFilterTag"
  | "lineItemFilterTag"
  | "fromFilterTag"
  | "throughFilterTag"
  | "refreshFailedTitle"
  | "refreshFailedDescription"
  | "unnamedLineItem"
  | "deleted"
  | "deletedAt"
  | "commercialDetails"
  | "billingDetails"
  | "loadMoreLineItems"
  | "loadMoreEvents"
  | "retry"
  | "restartResults"
  | "copyReference"
  | "reference"
  | "noHistoryTitle"
  | "noHistoryDescription"
  | "noLineItemsTitle"
  | "noLineItemsDescription"
  | "noLineItemSearchResultsTitle"
  | "noLineItemSearchResultsDescription"
  | "noEventsTitle"
  | "noEventsDescription"
  | "noFilteredEventsTitle"
  | "noFilteredEventsDescription"
  | "initialLoading"
  | "lineItemsLoading"
  | "eventsLoading"
  | "paginationErrorTitle"
  | "paginationErrorDescription"
  | "lineItemReference"
  | "lineItemName"
  | "showHistory"
  | "eventLineItemIdentity"
  | "latestRetainedNameDisclaimer"
  | "lineItemCapTitle"
  | "lineItemCapDescription"
  | "eventCapTitle"
  | "eventCapDescription"
  | "today"
  | "yesterday"
  | "changedField"
  | "before"
  | "after"
  | "truncated"
  | "unavailable"
  | "days.one"
  | "days.other"
  | "months.one"
  | "months.other";

export type TranslationKey =
  | `errors.${ErrorCode}.${"title" | "description" | "action"}`
  | `fields.${MonitoredProperty}`
  | `events.${AuditEventType}`
  | `valueStates.${ObservedValueState}`
  | `membership.${MembershipState}`
  | "membership.atDeletion"
  | `historyCoverage.${HistoryCoverageMode}`
  | "historyCoverage.unknownState"
  | "historyCoverage.disclaimer"
  | `common.${CommonKey}`;

const bundles = { en, hu } as const;
const safeFallbackKey = "common.unavailable";

function flattenKeys(value: object, prefix = ""): string[] {
  return Object.entries(value).flatMap(([key, child]) => {
    const path = prefix ? `${prefix}.${key}` : key;
    return typeof child === "string"
      ? [path]
      : flattenKeys(child as object, path);
  });
}

const ownedKeys = new Set(flattenKeys(en));

function canonicalize(locale: unknown): string | null {
  if (typeof locale !== "string") return null;
  const candidate = locale.trim().replaceAll("_", "-");
  if (candidate === "") return null;
  try {
    return Intl.getCanonicalLocales(candidate)[0] ?? null;
  } catch {
    return null;
  }
}

export function resolveMessageLanguage(locale: unknown): SupportedLanguage {
  const canonical = canonicalize(locale);
  if (!canonical) return "en";
  const exact = canonical.toLowerCase();
  if (exact === "en" || exact === "hu") return exact;
  const base = exact.split("-")[0];
  return base === "hu" ? "hu" : "en";
}

export function resolveFormatLocale(locale: unknown): string {
  const canonical = canonicalize(locale);
  if (!canonical) return "en";
  if (Intl.DateTimeFormat.supportedLocalesOf([canonical]).length > 0)
    return canonical;
  const base = canonical.split("-")[0] ?? "en";
  return Intl.DateTimeFormat.supportedLocalesOf([base]).length > 0
    ? base
    : "en";
}

function lookup(bundle: object, key: string): string | null {
  let current: unknown = bundle;
  for (const segment of key.split(".")) {
    if (
      typeof current !== "object" ||
      current === null ||
      !Object.prototype.hasOwnProperty.call(current, segment)
    )
      return null;
    current = (current as Record<string, unknown>)[segment];
  }
  return typeof current === "string" ? current : null;
}

export type TranslationLogger = (missingOwnedKey: TranslationKey) => void;
export type Translate = (
  key: TranslationKey,
  values?: Readonly<Record<string, string>>,
) => string;

export function createTranslator(
  language: SupportedLanguage,
  logMissing?: TranslationLogger,
): Translate {
  return (key, values = {}) => {
    if (!ownedKeys.has(key)) {
      return (
        lookup(bundles[language], safeFallbackKey) ??
        lookup(bundles.en, safeFallbackKey) ??
        "Information unavailable."
      );
    }
    const localized = lookup(bundles[language], key);
    const fallback = lookup(bundles.en, key);
    if (localized === null) logMissing?.(key);
    let result =
      localized ??
      fallback ??
      lookup(bundles[language], safeFallbackKey) ??
      lookup(bundles.en, safeFallbackKey) ??
      "Information unavailable.";
    for (const [name, value] of Object.entries(values)) {
      result = result.replaceAll(`{${name}}`, value);
    }
    return result;
  };
}
