import { describe, expect, it } from "vitest";
import {
  parseDealAuditResponse,
  parsePositiveProviderId,
  parsePublicErrorResponse,
} from "./contracts";
import { event, page, publicEventId, response } from "../test/fixtures";

type MutableResponse = {
  lineItems: {
    items: Array<{ latest: Record<string, unknown>; deleted: boolean }>;
  };
  events: { items: Array<{ type: string; field: string | null }> };
};

function mutableResponse(): MutableResponse {
  return structuredClone(response()) as unknown as MutableResponse;
}

function first<T>(items: T[]): T {
  const item = items[0];
  if (!item) throw new Error("fixture must contain an item");
  return item;
}

describe("Deal audit contract validation", () => {
  it("accepts a valid response and ignores forward-compatible properties", () => {
    const source = {
      ...response(),
      futureTopLevel: true,
      lineItems: {
        ...response().lineItems,
        futureSectionField: "allowed",
        items: [{ ...response().lineItems.items[0], futureItemField: 42 }],
      },
    };

    const parsed = parseDealAuditResponse(source, "1001");

    expect(parsed.dealId).toBe("1001");
    expect(parsed.lineItems.items).toHaveLength(1);
    expect(parsed).not.toHaveProperty("futureTopLevel");
  });

  it.each([
    [
      "missing monitored property",
      () => {
        const source = mutableResponse();
        delete first(source.lineItems.items).latest.quantity;
        return source;
      },
    ],
    [
      "unknown event enum",
      () => {
        const source = mutableResponse();
        first(source.events.items).type = "FUTURE_EVENT";
        return source;
      },
    ],
    [
      "property event without field",
      () => {
        const source = mutableResponse();
        first(source.events.items).field = null;
        return source;
      },
    ],
    [
      "deleted invariant mismatch",
      () => {
        const source = mutableResponse();
        first(source.lineItems.items).deleted = true;
        return source;
      },
    ],
    [
      "oversized displayed value",
      () => {
        const source = mutableResponse();
        first(source.lineItems.items).latest.name = {
          state: "VALUE",
          value: "x".repeat(513),
          truncated: true,
        };
        return source;
      },
    ],
    [
      "invalid calendar timestamp",
      () => {
        const source = structuredClone(response()) as unknown as {
          events: { items: Array<{ occurredAt: string }> };
        };
        first(source.events.items).occurredAt = "2026-02-31T10:05:00Z";
        return source;
      },
    ],
    ["mismatched Deal", () => ({ ...response(), dealId: "1002" })],
    ["unsafe ID", () => ({ ...response(), dealId: "9007199254740992" })],
    [
      "hasMore without cursor",
      () => ({
        ...response(),
        events: {
          ...response().events,
          page: { limit: 20, hasMore: true, nextCursor: null },
        },
      }),
    ],
    [
      "hasMore without an item",
      () => ({
        ...response(),
        events: { items: [], page: page(20, true, "cursor") },
      }),
    ],
    [
      "unsafe cursor characters",
      () => ({
        ...response(),
        events: { ...response().events, page: page(20, true, "bad cursor") },
      }),
    ],
    [
      "inconsistent unknown-state coverage",
      () => ({
        ...response(),
        lineItems: {
          ...response().lineItems,
          items: [
            {
              ...response().lineItems.items[0],
              historyCoverage: {
                ...response().lineItems.items[0]?.historyCoverage,
                hasUnknownState: true,
              },
            },
          ],
        },
      }),
    ],
    [
      "gap state without a start timestamp",
      () => ({
        ...response(),
        reliability: {
          ...response().reliability,
          coverageState: "POSSIBLE_GAP",
          possibleGapSince: null,
        },
      }),
    ],
    [
      "retained boundary before the observation boundary",
      () => ({
        ...response(),
        lineItems: {
          ...response().lineItems,
          items: [
            {
              ...response().lineItems.items[0],
              historyCoverage: {
                ...response().lineItems.items[0]?.historyCoverage,
                retainedFrom: "2026-09-27T10:00:00Z",
              },
            },
          ],
        },
      }),
    ],
    [
      "invalid lifecycle transition",
      () => ({
        ...response(),
        events: {
          ...response().events,
          items: [
            {
              ...event(),
              type: "DELETED",
              field: null,
              after: { state: "PRESENT", value: null, truncated: false },
            },
          ],
        },
      }),
    ],
    [
      "oversized public event ID",
      () => ({
        ...response(),
        events: {
          ...response().events,
          items: [{ ...event(), eventId: `${publicEventId("x")}x` }],
        },
      }),
    ],
  ])("rejects %s", (_label, build) => {
    expect(() => parseDealAuditResponse(build(), "1001")).toThrow();
  });

  it.each([1, 42, 9_007_199_254_740_991])(
    "canonicalizes a safe positive numeric ID %s",
    (id) => {
      expect(parsePositiveProviderId(String(id))).toBe(String(id));
    },
  );

  it.each(["0", "-1", "01", "1.5", "9007199254740992", "not-an-id"])(
    "rejects invalid provider ID %s",
    (id) => expect(() => parsePositiveProviderId(id)).toThrow(),
  );

  it("accepts a bounded canonical Line Item ID without numeric coercion", () => {
    const largeLineItemId = "9".repeat(255);
    const source = response([], [event("large", largeLineItemId)]);

    expect(
      parseDealAuditResponse(source, "1001").events.items[0]?.lineItemId,
    ).toBe(largeLineItemId);
  });

  it("accepts only public error codes with UUID correlation IDs", () => {
    expect(
      parsePublicErrorResponse({
        error: {
          code: "SERVICE_UNAVAILABLE",
          correlationId: "8bd0d958-d3db-4214-b235-99fbcf70a812",
        },
        ignored: "future",
      }),
    ).toEqual({
      error: {
        code: "SERVICE_UNAVAILABLE",
        correlationId: "8bd0d958-d3db-4214-b235-99fbcf70a812",
      },
    });
    expect(() =>
      parsePublicErrorResponse({
        error: { code: "STACK_TRACE", correlationId: "not-a-uuid" },
      }),
    ).toThrow();
  });
});
