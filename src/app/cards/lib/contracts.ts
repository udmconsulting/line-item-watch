export const MONITORED_PROPERTIES = [
  "name",
  "quantity",
  "unitPrice",
  "unitDiscount",
  "discountPercentage",
  "billingFrequency",
  "billingStartDate",
  "billingStartDelayDays",
  "billingStartDelayMonths",
  "billingPeriod",
] as const;

export const AUDIT_EVENT_TYPES = [
  "CREATED",
  "PROPERTY_CHANGED",
  "DEAL_ASSOCIATED",
  "DEAL_DISASSOCIATED",
  "DELETED",
] as const;

export const PUBLIC_ERROR_CODES = [
  "INVALID_REQUEST",
  "AUTHENTICATION_FAILED",
  "ACCOUNT_UNAVAILABLE",
  "SERVICE_UNAVAILABLE",
  "INTERNAL_ERROR",
] as const;

export const MEMBERSHIP_STATES = [
  "PRESENT",
  "ABSENT",
  "UNKNOWN",
  "NOT_APPLICABLE",
] as const;

export const HISTORY_COVERAGE_MODES = [
  "BASELINE_ANCHORED",
  "SIGNAL_FIRST",
] as const;

export type MonitoredProperty = (typeof MONITORED_PROPERTIES)[number];
export type AuditEventType = (typeof AUDIT_EVENT_TYPES)[number];
export type PublicErrorCode = (typeof PUBLIC_ERROR_CODES)[number];
export type MembershipState = (typeof MEMBERSHIP_STATES)[number];
export type HistoryCoverageMode = (typeof HISTORY_COVERAGE_MODES)[number];
export type PropertyValueState = "UNKNOWN" | "ABSENT" | "VALUE";
export type ObservedValueState = PropertyValueState | "PRESENT";

export interface ObservedValue {
  readonly state: ObservedValueState;
  readonly value: string | null;
  readonly truncated: boolean;
}

export interface LineItemSummary {
  readonly lineItemId: string;
  readonly deleted: boolean;
  readonly deletedAt: string | null;
  readonly historicalRelevance: boolean;
  readonly dealMembership: {
    readonly currentMembership: MembershipState;
    readonly membershipAtDeletion: Exclude<
      MembershipState,
      "NOT_APPLICABLE"
    > | null;
  };
  readonly latest: Readonly<Record<MonitoredProperty, ObservedValue>>;
  readonly historyCoverage: {
    readonly mode: HistoryCoverageMode;
    readonly observedFrom: string;
    readonly hasUnknownState: boolean;
  };
}

export interface AuditEvent {
  readonly eventId: string;
  readonly lineItemId: string;
  readonly type: AuditEventType;
  readonly occurredAt: string;
  readonly field: MonitoredProperty | null;
  readonly before: ObservedValue;
  readonly after: ObservedValue;
}

export interface Page {
  readonly limit: number;
  readonly hasMore: boolean;
  readonly nextCursor: string | null;
}

export interface DealAuditResponse {
  readonly dealId: string;
  readonly lineItems: {
    readonly items: readonly LineItemSummary[];
    readonly page: Page;
  };
  readonly events: {
    readonly items: readonly AuditEvent[];
    readonly page: Page;
  };
}

export interface PublicErrorResponse {
  readonly error: {
    readonly code: PublicErrorCode;
    readonly correlationId: string;
  };
}

export class ContractValidationError extends Error {
  public constructor() {
    super("The response did not match the Deal audit contract.");
    this.name = "ContractValidationError";
  }
}

const positiveProviderId = /^[1-9]\d*$/;
const isoInstant =
  /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d{1,9})?Z$/;
const uuid =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const publicEventId = /^evt_[A-Za-z0-9_-]{43}$/;
const opaqueCursor = /^[A-Za-z0-9_-]{1,1024}$/;
const maxProviderIdLength = 255;

function invalid(): never {
  throw new ContractValidationError();
}

function record(value: unknown): Record<string, unknown> {
  if (typeof value !== "object" || value === null || Array.isArray(value))
    invalid();
  return value as Record<string, unknown>;
}

function string(value: unknown): string {
  if (typeof value !== "string") invalid();
  return value;
}

function boolean(value: unknown): boolean {
  if (typeof value !== "boolean") invalid();
  return value;
}

function nullableString(value: unknown): string | null {
  return value === null ? null : string(value);
}

function oneOf<T extends string>(value: unknown, allowed: readonly T[]): T {
  if (typeof value !== "string" || !allowed.includes(value as T)) invalid();
  return value as T;
}

export function parsePositiveProviderId(value: unknown): string {
  const parsed = string(value);
  const numeric = Number(parsed);
  if (!positiveProviderId.test(parsed) || !Number.isSafeInteger(numeric))
    invalid();
  return parsed;
}

function parseLineItemId(value: unknown): string {
  const parsed = string(value);
  if (parsed.length > maxProviderIdLength || !positiveProviderId.test(parsed))
    invalid();
  return parsed;
}

function timestamp(value: unknown): string {
  const parsed = string(value);
  const match = isoInstant.exec(parsed);
  const date = new Date(parsed);
  if (
    !match ||
    !Number.isFinite(date.getTime()) ||
    date.getUTCFullYear() !== Number(match[1]) ||
    date.getUTCMonth() + 1 !== Number(match[2]) ||
    date.getUTCDate() !== Number(match[3]) ||
    date.getUTCHours() !== Number(match[4]) ||
    date.getUTCMinutes() !== Number(match[5]) ||
    date.getUTCSeconds() !== Number(match[6])
  )
    invalid();
  return parsed;
}

function observedValue(
  value: unknown,
  propertyValueOnly: boolean,
): ObservedValue {
  const source = record(value);
  const allowed = propertyValueOnly
    ? (["UNKNOWN", "ABSENT", "VALUE"] as const)
    : (["UNKNOWN", "ABSENT", "PRESENT", "VALUE"] as const);
  const state = oneOf(source.state, allowed);
  const parsedValue = nullableString(source.value);
  const truncated = boolean(source.truncated);
  if (
    (state === "VALUE") !== (parsedValue !== null) ||
    (state !== "VALUE" && truncated) ||
    (parsedValue !== null && [...parsedValue].length > 512)
  )
    invalid();
  return { state, value: parsedValue, truncated };
}

function page(value: unknown, itemCount: number): Page {
  const source = record(value);
  const limit = source.limit;
  if (
    !Number.isInteger(limit) ||
    (limit as number) < 0 ||
    (limit as number) > 20
  )
    invalid();
  const hasMore = boolean(source.hasMore);
  const nextCursor = nullableString(source.nextCursor);
  if (
    itemCount > (limit as number) ||
    (hasMore && (itemCount === 0 || !nextCursor))
  )
    invalid();
  if (!hasMore && nextCursor !== null) invalid();
  if (nextCursor !== null && !opaqueCursor.test(nextCursor)) invalid();
  if ((limit as number) === 0 && (itemCount !== 0 || hasMore || nextCursor))
    invalid();
  return { limit: limit as number, hasMore, nextCursor };
}

function lineItem(value: unknown): LineItemSummary {
  const source = record(value);
  const deleted = boolean(source.deleted);
  const deletedAt =
    source.deletedAt === null ? null : timestamp(source.deletedAt);
  const membershipSource = record(source.dealMembership);
  const currentMembership = oneOf(
    membershipSource.currentMembership,
    MEMBERSHIP_STATES,
  );
  const membershipAtDeletion =
    membershipSource.membershipAtDeletion === null
      ? null
      : oneOf(membershipSource.membershipAtDeletion, [
          "PRESENT",
          "ABSENT",
          "UNKNOWN",
        ] as const);

  if (deleted !== (deletedAt !== null)) invalid();
  if (deleted) {
    if (currentMembership !== "NOT_APPLICABLE" || membershipAtDeletion === null)
      invalid();
  } else if (
    currentMembership === "NOT_APPLICABLE" ||
    membershipAtDeletion !== null
  ) {
    invalid();
  }

  const latestSource = record(source.latest);
  const latest = Object.fromEntries(
    MONITORED_PROPERTIES.map((property) => [
      property,
      observedValue(latestSource[property], true),
    ]),
  ) as Record<MonitoredProperty, ObservedValue>;
  const coverageSource = record(source.historyCoverage);
  const coverageMode = oneOf(coverageSource.mode, HISTORY_COVERAGE_MODES);
  const observedFrom = timestamp(coverageSource.observedFrom);
  const hasUnknownState = boolean(coverageSource.hasUnknownState);
  const knownMembership = deleted ? membershipAtDeletion : currentMembership;
  const calculatedUnknownState =
    Object.values(latest).some((value) => value.state === "UNKNOWN") ||
    knownMembership === "UNKNOWN";
  if (hasUnknownState !== calculatedUnknownState) invalid();

  return {
    lineItemId: parseLineItemId(source.lineItemId),
    deleted,
    deletedAt,
    historicalRelevance: boolean(source.historicalRelevance),
    dealMembership: { currentMembership, membershipAtDeletion },
    latest,
    historyCoverage: {
      mode: coverageMode,
      observedFrom,
      hasUnknownState,
    },
  };
}

function auditEvent(value: unknown): AuditEvent {
  const source = record(value);
  const type = oneOf(source.type, AUDIT_EVENT_TYPES);
  const field =
    source.field === null ? null : oneOf(source.field, MONITORED_PROPERTIES);
  if ((type === "PROPERTY_CHANGED") !== (field !== null)) invalid();
  const before = observedValue(source.before, type === "PROPERTY_CHANGED");
  const after = observedValue(source.after, type === "PROPERTY_CHANGED");
  const validTransition =
    type === "PROPERTY_CHANGED" ||
    (type === "CREATED" &&
      before.state === "ABSENT" &&
      after.state === "PRESENT") ||
    (type === "DEAL_ASSOCIATED" &&
      (before.state === "UNKNOWN" || before.state === "ABSENT") &&
      after.state === "PRESENT") ||
    (type === "DEAL_DISASSOCIATED" &&
      (before.state === "UNKNOWN" || before.state === "PRESENT") &&
      after.state === "ABSENT") ||
    (type === "DELETED" &&
      (before.state === "UNKNOWN" || before.state === "PRESENT") &&
      after.state === "ABSENT");
  if (!validTransition) invalid();
  return {
    eventId: publicEventId.test(string(source.eventId))
      ? string(source.eventId)
      : invalid(),
    lineItemId: parseLineItemId(source.lineItemId),
    type,
    occurredAt: timestamp(source.occurredAt),
    field,
    before,
    after,
  };
}

export function parseDealAuditResponse(
  value: unknown,
  expectedDealId: string,
): DealAuditResponse {
  const source = record(value);
  const dealId = parsePositiveProviderId(source.dealId);
  if (dealId !== expectedDealId) invalid();

  const lineItemsSource = record(source.lineItems);
  const eventsSource = record(source.events);
  if (
    !Array.isArray(lineItemsSource.items) ||
    !Array.isArray(eventsSource.items)
  )
    invalid();
  const lineItems = lineItemsSource.items.map(lineItem);
  const events = eventsSource.items.map(auditEvent);

  return {
    dealId,
    lineItems: {
      items: lineItems,
      page: page(lineItemsSource.page, lineItems.length),
    },
    events: { items: events, page: page(eventsSource.page, events.length) },
  };
}

export function parsePublicErrorResponse(value: unknown): PublicErrorResponse {
  const source = record(value);
  const errorSource = record(source.error);
  const correlationId = string(errorSource.correlationId);
  if (!uuid.test(correlationId)) invalid();
  return {
    error: { code: oneOf(errorSource.code, PUBLIC_ERROR_CODES), correlationId },
  };
}
