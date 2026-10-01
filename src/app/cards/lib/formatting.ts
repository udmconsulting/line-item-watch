import type { Translate } from "../i18n";
import type { MonitoredProperty, ObservedValue } from "./contracts";

export interface ValueFormatter {
  timestamp(value: string): string;
  dateOnly(value: string): string;
  number(value: string): string;
  delay(value: string, unit: "days" | "months", translate: Translate): string;
  dayKey(value: string): string;
  dayLabel(value: string, now: Date, translate: Translate): string;
}

export interface CalendarDate {
  readonly year: number;
  readonly month: number;
  readonly date: number;
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
    dayKey(value) {
      const parsed = new Date(value);
      if (!Number.isFinite(parsed.getTime())) return value;
      return portalDayKey(parsed, timeZone);
    },
    dayLabel(value, now, translate) {
      const parsed = new Date(value);
      if (!Number.isFinite(parsed.getTime())) return value;
      const eventKey = portalDayKey(parsed, timeZone);
      const todayKey = portalDayKey(now, timeZone);
      if (eventKey === todayKey) return translate("common.today");
      const today = parseDayKey(todayKey);
      const yesterday = new Date(
        Date.UTC(today.year, today.month - 1, today.date - 1),
      );
      if (eventKey === utcDayKey(yesterday))
        return translate("common.yesterday");
      return new Intl.DateTimeFormat(locale, {
        dateStyle: "long",
        timeZone,
      }).format(parsed);
    },
  };
}

function portalDayKey(value: Date, timeZone: string): string {
  const parts = new Intl.DateTimeFormat("en-CA", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    timeZone,
  }).formatToParts(value);
  const part = (type: Intl.DateTimeFormatPartTypes) =>
    parts.find((candidate) => candidate.type === type)?.value ?? "";
  return `${part("year")}-${part("month")}-${part("day")}`;
}

function parseDayKey(value: string): CalendarDate {
  const [year, month, date] = value.split("-").map(Number);
  return { year: year ?? 0, month: month ?? 0, date: date ?? 0 };
}

function utcDayKey(value: Date): string {
  return `${value.getUTCFullYear()}-${String(value.getUTCMonth() + 1).padStart(2, "0")}-${String(value.getUTCDate()).padStart(2, "0")}`;
}

export function nextCalendarDate(value: CalendarDate): CalendarDate {
  const next = new Date(Date.UTC(value.year, value.month - 1, value.date + 1));
  return {
    year: next.getUTCFullYear(),
    month: next.getUTCMonth() + 1,
    date: next.getUTCDate(),
  };
}

export function portalDateStartInstant(
  value: CalendarDate,
  timeZone: string,
): string {
  const targetUtc = Date.UTC(value.year, value.month - 1, value.date);
  let candidate = targetUtc;
  const formatter = new Intl.DateTimeFormat("en-US", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hourCycle: "h23",
    timeZone,
  });
  for (let index = 0; index < 4; index += 1) {
    const parts = formatter.formatToParts(new Date(candidate));
    const number = (type: Intl.DateTimeFormatPartTypes) =>
      Number(parts.find((part) => part.type === type)?.value);
    const representedAsUtc = Date.UTC(
      number("year"),
      number("month") - 1,
      number("day"),
      number("hour"),
      number("minute"),
      number("second"),
    );
    const next = targetUtc - (representedAsUtc - candidate);
    if (next === candidate) break;
    candidate = next;
  }
  const result = new Date(candidate);
  if (portalDayKey(result, timeZone) !== utcDayKey(new Date(targetUtc))) {
    throw new Error("Portal calendar date has no representable start.");
  }
  return result.toISOString();
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
