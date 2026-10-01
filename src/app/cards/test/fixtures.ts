import {
  MONITORED_PROPERTIES,
  type AuditEvent,
  type DealAuditResponse,
  type LineItemSummary,
  type ObservedValue,
  type Page,
} from "../lib/contracts";
import type { ExtensionPointApiContext } from "@hubspot/ui-extensions";

export const absent: ObservedValue = {
  state: "ABSENT",
  value: null,
  truncated: false,
};
export const unknown: ObservedValue = {
  state: "UNKNOWN",
  value: null,
  truncated: false,
};
export const value = (text: string, truncated = false): ObservedValue => ({
  state: "VALUE",
  value: text,
  truncated,
});

export function publicEventId(seed: string): string {
  const normalized = seed.replace(/[^A-Za-z0-9_-]/g, "_");
  return `evt_${`${normalized}${"_".repeat(43)}`.slice(0, 43)}`;
}

export function lineItem(
  lineItemId = "2002",
  name = "Support",
  overrides: Partial<LineItemSummary> = {},
): LineItemSummary {
  const latest = Object.fromEntries(
    MONITORED_PROPERTIES.map((property) => [property, absent]),
  ) as LineItemSummary["latest"];
  return {
    lineItemId,
    deleted: false,
    deletedAt: null,
    historicalRelevance: true,
    dealMembership: {
      currentMembership: "PRESENT",
      membershipAtDeletion: null,
    },
    latest: {
      ...latest,
      name: value(name),
      quantity: value("2"),
      unitPrice: value("1234.50"),
      billingStartDate: value("2026-09-28"),
      billingStartDelayDays: value("1"),
    },
    historyCoverage: {
      mode: "BASELINE_ANCHORED",
      observedFrom: "2026-09-28T10:00:00Z",
      hasUnknownState: false,
    },
    ...overrides,
  };
}

export function event(eventId = "first", lineItemId = "2002"): AuditEvent {
  return {
    eventId: publicEventId(eventId),
    lineItemId,
    latestRetainedLineItemName: value(`Item ${lineItemId}`),
    type: "PROPERTY_CHANGED",
    occurredAt: "2026-09-28T10:05:00Z",
    field: "quantity",
    before: unknown,
    after: value("2"),
  };
}

export function page(
  limit: number,
  hasMore = false,
  nextCursor: string | null = null,
): Page {
  return { limit, hasMore, nextCursor };
}

export function response(
  lineItems: readonly LineItemSummary[] = [lineItem()],
  events: readonly AuditEvent[] = [event()],
  lineItemsPage: Page = page(10),
  eventsPage: Page = page(20),
  dealId = "1001",
): DealAuditResponse {
  return {
    dealId,
    lineItems: { items: lineItems, page: lineItemsPage },
    events: { items: events, page: eventsPage },
  };
}

export const context: ExtensionPointApiContext<"crm.record.tab"> = {
  location: "crm.record.tab",
  crm: { objectId: 1001, objectTypeId: "0-3" },
  user: {
    id: 1,
    emails: [],
    email: "",
    firstName: "",
    lastName: "",
    roles: [],
    teams: [],
    permissions: [],
    language: "en",
    locale: "en-US",
  },
  portal: { id: 1, timezone: "Europe/Budapest" },
  variables: { LINE_ITEM_WATCH_API_ORIGIN: "https://audit.example.com" },
};

export function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}
