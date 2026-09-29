import type { Translate } from "../i18n";
import type { MonitoredProperty, ObservedValue } from "./contracts";

export interface ValueFormatter {
  timestamp(value: string): string;
  dateOnly(value: string): string;
  number(value: string): string;
  delay(value: string, unit: "days" | "months", translate: Translate): string;
}

export function resolveTimeZone(value: unknown): string {
  if (typeof value !== "string" || value.trim() === "") return "UTC";
  try {
    new Intl.DateTimeFormat("en", { timeZone: value }).format(0);
    return value;
  } catch {
    return "UTC";
  }
}

const decimal = /^-?(?:0|[1-9]\d*)(?:\.\d+)?$/;
const dateOnly = /^(\d{4})-(\d{2})-(\d{2})$/;

function safelyRepresentableNumber(
  value: string,
): { numeric: number; fractionDigits: number } | null {
  if (!decimal.test(value)) return null;
  const unsigned = value.replace(/^[+-]/, "");
  const [integerPart = "", fractionPart = ""] = unsigned.split(".");
  const significant = `${integerPart}${fractionPart}`.replace(/^0+/, "").length;
  const numeric = Number(value);
  if (!Number.isFinite(numeric) || significant > 15 || fractionPart.length > 15)
    return null;
  return { numeric, fractionDigits: fractionPart.length };
}

export function createValueFormatter(
  locale: string,
  timeZone: string,
  pluralLocale = locale,
): ValueFormatter {
  return {
    timestamp(value) {
      const parsed = new Date(value);
      if (!Number.isFinite(parsed.getTime())) return value;
      return new Intl.DateTimeFormat(locale, {
        dateStyle: "medium",
        timeStyle: "short",
        timeZone,
      }).format(parsed);
    },
    dateOnly(value) {
      const match = dateOnly.exec(value);
      if (!match) return value;
      const year = Number(match[1]);
      const month = Number(match[2]);
      const day = Number(match[3]);
      const parsed = new Date(Date.UTC(year, month - 1, day));
      if (
        parsed.getUTCFullYear() !== year ||
        parsed.getUTCMonth() !== month - 1 ||
        parsed.getUTCDate() !== day
      ) {
        return value;
      }
      return new Intl.DateTimeFormat(locale, {
        dateStyle: "medium",
        timeZone: "UTC",
      }).format(parsed);
    },
    number(value) {
      const parsed = safelyRepresentableNumber(value);
      if (!parsed) return value;
      return new Intl.NumberFormat(locale, {
        minimumFractionDigits: parsed.fractionDigits,
        maximumFractionDigits: parsed.fractionDigits,
      }).format(parsed.numeric);
    },
    delay(value, unit, translate) {
      const parsed = safelyRepresentableNumber(value);
      if (!parsed || !Number.isSafeInteger(parsed.numeric)) return value;
      const plural =
        new Intl.PluralRules(pluralLocale).select(parsed.numeric) === "one"
          ? "one"
          : "other";
      return translate(`common.${unit}.${plural}`, {
        value: this.number(value),
      });
    },
  };
}

export function formatObservedValue(
  property: MonitoredProperty,
  observed: ObservedValue,
  formatter: ValueFormatter,
  translate: Translate,
): string {
  if (observed.state !== "VALUE" || observed.value === null) {
    return translate(`valueStates.${observed.state}`);
  }
  switch (property) {
    case "quantity":
    case "unitPrice":
    case "unitDiscount":
    case "discountPercentage":
      return formatter.number(observed.value);
    case "billingStartDelayDays":
      return formatter.delay(observed.value, "days", translate);
    case "billingStartDelayMonths":
      return formatter.delay(observed.value, "months", translate);
    case "billingStartDate":
      return formatter.dateOnly(observed.value);
    default:
      return observed.value;
  }
}
