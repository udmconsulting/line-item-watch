import {
  Accordion,
  Alert,
  Button,
  DateInput,
  Input,
  LoadingButton,
  SearchInput,
  Select,
  StatusTag,
} from "@hubspot/ui-extensions";
import { createRenderer } from "@hubspot/ui-extensions/testing";
import { describe, expect, it, vi } from "vitest";
import { DealAuditCard, type DealCardContext } from "./DealAuditCard";
import type { Fetcher } from "../lib/client";
import type { AuditEvent } from "../lib/contracts";
import {
  context,
  event,
  jsonResponse,
  lineItem,
  page,
  response,
} from "../test/fixtures";

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

function renderCard(fetcher: Fetcher, cardContext: DealCardContext = context) {
  const renderer = createRenderer("crm.record.tab");
  const copyText = vi.fn();
  renderer.render(
    <DealAuditCard
      context={cardContext}
      fetcher={fetcher}
      copyText={copyText}
    />,
  );
  return { renderer, copyText };
}

describe("DealAuditCard", () => {
  it("renders all line-item details, membership, coverage, and chronological event semantics", async () => {
    const lifecycleTypes = [
      "CREATED",
      "DEAL_ASSOCIATED",
      "DEAL_DISASSOCIATED",
      "DELETED",
    ] as const;
    const lifecycleEvents: AuditEvent[] = lifecycleTypes.map((type, index) => {
      const transitions = {
        CREATED: ["ABSENT", "PRESENT"],
        DEAL_ASSOCIATED: ["UNKNOWN", "PRESENT"],
        DEAL_DISASSOCIATED: ["PRESENT", "ABSENT"],
        DELETED: ["PRESENT", "ABSENT"],
      } as const;
      const [before, after] = transitions[type];
      return {
        ...event(`lifecycle_${index}`),
        type,
        field: null,
        before: { state: before, value: null, truncated: false },
        after: { state: after, value: null, truncated: false },
      };
    });
    const item = lineItem("2002", "Support", {
      latest: {
        ...lineItem().latest,
        unitDiscount: { state: "UNKNOWN", value: null, truncated: false },
        billingPeriod: { state: "VALUE", value: "P1M", truncated: true },
      },
      historyCoverage: {
        mode: "SIGNAL_FIRST",
        observedFrom: "2026-09-28T10:00:00Z",
        hasUnknownState: true,
        retainedFrom: "2026-09-28T10:00:00Z",
        retentionLimited: false,
      },
    });
    const deletedItem = lineItem("2004", "Retired service", {
      deleted: true,
      deletedAt: "2026-09-28T11:00:00Z",
      dealMembership: {
        currentMembership: "NOT_APPLICABLE",
        membershipAtDeletion: "PRESENT",
      },
    });
    const fetcher = vi
      .fn<Fetcher>()
      .mockResolvedValue(
        jsonResponse(
          response([item, deletedItem], [event(), ...lifecycleEvents]),
        ),
      );
    const { renderer } = renderCard(fetcher);

    await renderer.waitFor(() =>
      expect(renderer.getRootNode().toString()).toContain("Support"),
    );
    const text = renderer.getRootNode().toString();
    expect(text).toContain("Quantity");
    expect(text).toContain("Unit price");
    expect(text).toContain("Billing period");
    expect(text).toContain("Currently associated");
    expect(text).toContain("earliest retained change evidence");
    expect(text).toContain("Some current values are unknown");
    expect(text).toContain("Property changed");
    expect(text).toContain("Changed field: Quantity");
    expect(text).toContain("Latest retained name: Item 2002 · Line Item 2002");
    expect(text).toContain("Line item created");
    expect(text).toContain("Associated with this Deal");
    expect(text).toContain("Disassociated from this Deal");
    expect(text).toContain("Line item deleted");
    expect(text).toContain("This value was shortened");
    expect(text).toContain("Retired service");
    expect(text).toContain("Deleted Sep 28, 2026");
    expect(text).toContain("Association at deletion: Currently associated");
    expect(renderer.find(StatusTag).text).toBe("Currently associated");
  });

  it("renders a distinct full empty state", async () => {
    const fetcher = vi
      .fn<Fetcher>()
      .mockResolvedValue(jsonResponse(response([], [])));
    const { renderer } = renderCard(fetcher);
    await renderer.waitFor(() =>
      expect(renderer.getRootNode().toString()).toContain(
        "No audit history yet",
      ),
    );
    expect(renderer.getRootNode().toString()).not.toContain(
      "No retained line items",
    );
  });

  it("uses Hungarian message copy and locale formatting", async () => {
    const fetcher = vi
      .fn<Fetcher>()
      .mockResolvedValue(jsonResponse(response()));
    const huContext: DealCardContext = {
      ...context,
      user: { ...context.user, language: "hu-HU", locale: "hu-HU" },
    };
    const { renderer } = renderCard(fetcher, huContext);
    await renderer.waitFor(() =>
      expect(renderer.getRootNode().toString()).toContain(
        "Legutóbbi változások",
      ),
    );
    expect(renderer.getRootNode().toString()).toContain("Mennyiség");
    expect(renderer.getRootNode().toString()).toContain("Jelenleg társítva");
  });

  it("shows truthful paused, gap, and degraded reconciliation warnings", async () => {
    const degraded = {
      ...response(),
      reliability: {
        ...response().reliability,
        ingestionState: "PAUSED" as const,
        coverageState: "POSSIBLE_GAP" as const,
        possibleGapSince: "2026-09-29T10:00:00Z",
        reconciliationOutcome: "UNAVAILABLE" as const,
      },
    };
    const fetcher = vi.fn<Fetcher>().mockResolvedValue(jsonResponse(degraded));
    const { renderer } = renderCard(fetcher);

    await renderer.waitFor(() =>
      expect(renderer.getRootNode().toString()).toContain(
        "Audit reliability is limited",
      ),
    );
    const text = renderer.getRootNode().toString();
    expect(text).toContain("Monitoring is paused");
    expect(text).toContain("historical gap");
    expect(text).toContain("cannot recreate missing changes");
    expect(text).toContain("verification is unavailable or conflicting");
  });

  it("localizes degraded reliability and the retained evidence boundary in Hungarian", async () => {
    const limitedItem = lineItem("2002", "Support", {
      historyCoverage: {
        ...lineItem().historyCoverage,
        retainedFrom: "2026-09-29T10:00:00Z",
        retentionLimited: true,
      },
    });
    const degraded = {
      ...response([limitedItem]),
      reliability: {
        ...response().reliability,
        ingestionState: "PAUSED" as const,
        coverageState: "POSSIBLE_GAP" as const,
        possibleGapSince: "2026-09-29T10:00:00Z",
      },
    };
    const fetcher = vi.fn<Fetcher>().mockResolvedValue(jsonResponse(degraded));
    const huContext: DealCardContext = {
      ...context,
      user: { ...context.user, language: "hu-HU", locale: "hu-HU" },
    };
    const { renderer } = renderCard(fetcher, huContext);

    await renderer.waitFor(() =>
      expect(renderer.getRootNode().toString()).toContain(
        "Az audit megbízhatósága korlátozott",
      ),
    );
    expect(renderer.getRootNode().toString()).toContain(
      "A megőrzési szabályok miatt",
    );
  });

  it("renders only safe localized error copy and a copyable body correlation ID", async () => {
    const correlationId = "8bd0d958-d3db-4214-b235-99fbcf70a812";
    const fetcher = vi.fn<Fetcher>().mockResolvedValue(
      jsonResponse(
        {
          error: {
            code: "INTERNAL_ERROR",
            correlationId,
            internalDiagnostic: "tenant=secret customer@example.com",
          },
          exception: "database password",
        },
        500,
      ),
    );
    const { renderer, copyText } = renderCard(fetcher);
    await renderer.waitFor(() =>
      expect(renderer.getRootNode().toString()).toContain(correlationId),
    );

    const text = renderer.getRootNode().toString();
    expect(text).toContain("Audit history could not be loaded");
    expect(text).not.toContain("tenant=secret");
    expect(text).not.toContain("customer@example.com");
    expect(text).not.toContain("database password");
    renderer
      .find(Button, (node) => node.text === "Copy reference")
      .trigger("onClick");
    expect(copyText).toHaveBeenCalledWith(correlationId);
  });

  it("blocks malformed local configuration before fetch", async () => {
    const fetcher = vi.fn<Fetcher>();
    const invalidContext: DealCardContext = { ...context, variables: {} };
    const { renderer } = renderCard(fetcher, invalidContext);
    await renderer.waitFor(() =>
      expect(renderer.getRootNode().toString()).toContain("not configured"),
    );
    expect(fetcher).not.toHaveBeenCalled();
  });

  it("retries an initial failure only after an explicit action", async () => {
    const fetcher = vi
      .fn<Fetcher>()
      .mockRejectedValueOnce(new Error("offline"))
      .mockResolvedValueOnce(jsonResponse(response()));
    const { renderer } = renderCard(fetcher);
    await renderer.waitFor(() =>
      expect(renderer.getRootNode().toString()).toContain("Could not connect"),
    );
    expect(fetcher).toHaveBeenCalledTimes(1);

    renderer
      .find(Button, (node) => node.text === "Try again")
      .trigger("onClick");

    await renderer.waitFor(() =>
      expect(renderer.getRootNode().toString()).toContain("Support"),
    );
    expect(renderer.getRootNode().toString()).toContain(
      "complete observed baseline",
    );
    expect(fetcher).toHaveBeenCalledTimes(2);
  });

  it("prevents duplicate line-item clicks and appends a cursor page once", async () => {
    const next = deferred<Response>();
    const fetcher = vi
      .fn<Fetcher>()
      .mockResolvedValueOnce(
        jsonResponse(
          response(
            [lineItem("2002", "First")],
            [],
            page(10, true, "next-line"),
            page(20),
          ),
        ),
      )
      .mockReturnValueOnce(next.promise);
    const { renderer } = renderCard(fetcher);
    await renderer.waitFor(() =>
      expect(renderer.getRootNode().toString()).toContain(
        "Load more line items",
      ),
    );

    const loadMore = renderer.find(
      LoadingButton,
      (node) => node.text === "Load more line items",
    );
    loadMore.trigger("onClick");
    loadMore.trigger("onClick");
    expect(fetcher).toHaveBeenCalledTimes(2);
    next.resolve(
      jsonResponse(
        response(
          [lineItem("2003", "Second"), lineItem("2003", "Second")],
          [],
          page(10),
          page(0),
        ),
      ),
    );

    await renderer.waitFor(() =>
      expect(renderer.getRootNode().toString()).toContain("Second"),
    );
    expect(fetcher.mock.calls[1]?.[0]).toContain(
      "eventsLimit=0&lineItemsCursor=next-line",
    );
    expect(renderer.findAll(Accordion, { title: "Second" })).toHaveLength(1);
  });

  it("merges independent pagination responses safely when they resolve out of order", async () => {
    const lines = deferred<Response>();
    const events = deferred<Response>();
    const fetcher = vi
      .fn<Fetcher>()
      .mockResolvedValueOnce(
        jsonResponse(
          response(
            [lineItem("2002", "First")],
            [event("evt_first", "2002")],
            page(10, true, "next-line"),
            page(20, true, "next-event"),
          ),
        ),
      )
      .mockReturnValueOnce(lines.promise)
      .mockReturnValueOnce(events.promise);
    const { renderer } = renderCard(fetcher);
    await renderer.waitFor(() =>
      expect(renderer.findAll(LoadingButton)).toHaveLength(3),
    );

    renderer
      .find(LoadingButton, (node) => node.text === "Load more line items")
      .trigger("onClick");
    renderer
      .find(LoadingButton, (node) => node.text === "Load more changes")
      .trigger("onClick");
    events.resolve(
      jsonResponse(
        response([], [event("evt_second", "2003")], page(0), page(20)),
      ),
    );
    lines.resolve(
      jsonResponse(
        response([lineItem("2003", "Second")], [], page(10), page(0)),
      ),
    );

    await renderer.waitFor(() => {
      expect(renderer.getRootNode().toString()).toContain("Second");
      expect(renderer.getRootNode().toString()).toContain("Line Item 2003");
    });
    expect(renderer.getRootNode().toString()).toContain("First");
  });

  it("ignores an older Deal response after the context changes", async () => {
    const dealA = deferred<Response>();
    const fetcher = vi
      .fn<Fetcher>()
      .mockReturnValueOnce(dealA.promise)
      .mockResolvedValueOnce(
        jsonResponse(
          response(
            [lineItem("3001", "Deal B item")],
            [],
            page(10),
            page(20),
            "1002",
          ),
        ),
      );
    const { renderer, copyText } = renderCard(fetcher);

    renderer.render(
      <DealAuditCard
        context={{ ...context, crm: { ...context.crm, objectId: 1002 } }}
        fetcher={fetcher}
        copyText={copyText}
      />,
    );
    await renderer.waitFor(() =>
      expect(renderer.getRootNode().toString()).toContain("Deal B item"),
    );

    dealA.resolve(
      jsonResponse(response([lineItem("2002", "Stale Deal A item")], [])),
    );
    await Promise.resolve();

    expect(renderer.getRootNode().toString()).toContain("Deal B item");
    expect(renderer.getRootNode().toString()).not.toContain(
      "Stale Deal A item",
    );
  });

  it("preserves loaded data and offers a section retry after pagination failure", async () => {
    const fetcher = vi
      .fn<Fetcher>()
      .mockResolvedValueOnce(
        jsonResponse(
          response(
            [lineItem("2002", "Already loaded")],
            [event("existing")],
            page(10, true, "next-line"),
            page(20),
          ),
        ),
      )
      .mockRejectedValueOnce(new Error("network customer data"))
      .mockResolvedValueOnce(
        jsonResponse(
          response(
            [lineItem("2003", "Loaded after retry")],
            [],
            page(10),
            page(0),
          ),
        ),
      );
    const { renderer } = renderCard(fetcher);
    await renderer.waitFor(() =>
      expect(renderer.getRootNode().toString()).toContain(
        "Load more line items",
      ),
    );
    renderer
      .find(LoadingButton, (node) => node.text === "Load more line items")
      .trigger("onClick");

    await renderer.waitFor(() => {
      expect(
        renderer.find(Alert, { title: "More results could not be loaded" }),
      ).toBeDefined();
    });
    expect(renderer.getRootNode().toString()).toContain("Already loaded");
    expect(renderer.getRootNode().toString()).toContain("Property changed");
    expect(renderer.getRootNode().toString()).not.toContain(
      "network customer data",
    );

    renderer
      .find(Button, (node) => node.text === "Try again")
      .trigger("onClick");
    await renderer.waitFor(() =>
      expect(renderer.getRootNode().toString()).toContain("Loaded after retry"),
    );
    expect(fetcher.mock.calls[1]?.[0]).toBe(fetcher.mock.calls[2]?.[0]);
  });

  it("keeps Line Items and loaded events after event pagination fails", async () => {
    const fetcher = vi
      .fn<Fetcher>()
      .mockResolvedValueOnce(
        jsonResponse(
          response(
            [lineItem("2002", "Preserved item")],
            [event("preserved")],
            page(10),
            page(20, true, "next-event"),
          ),
        ),
      )
      .mockRejectedValueOnce(new Error("private response detail"));
    const { renderer } = renderCard(fetcher);
    await renderer.waitFor(() =>
      expect(renderer.getRootNode().toString()).toContain("Load more changes"),
    );

    renderer
      .find(LoadingButton, (node) => node.text === "Load more changes")
      .trigger("onClick");
    await renderer.waitFor(() =>
      expect(
        renderer.find(Alert, { title: "More results could not be loaded" }),
      ).toBeDefined(),
    );

    const text = renderer.getRootNode().toString();
    expect(text).toContain("Preserved item");
    expect(text).toContain("Property changed");
    expect(text).not.toContain("private response detail");
  });

  it("submits server-side search explicitly and validates Unicode length", async () => {
    const fetcher = vi
      .fn<Fetcher>()
      .mockResolvedValueOnce(jsonResponse(response()))
      .mockResolvedValueOnce(
        jsonResponse(response([lineItem("2010", "50%_\\ support")], [])),
      );
    const { renderer } = renderCard(fetcher);
    await renderer.waitFor(() =>
      expect(renderer.getRootNode().toString()).toContain("Support"),
    );

    renderer
      .find(SearchInput, { name: "lineItemSearch" })
      .trigger("onChange", "x" as unknown as (value: string) => void);
    await renderer.waitFor(() =>
      expect(
        renderer.find(SearchInput, { name: "lineItemSearch" }).props.value,
      ).toBe("x"),
    );
    expect(fetcher).toHaveBeenCalledTimes(1);
    renderer.find(Button, (node) => node.text === "Apply").trigger("onClick");
    await renderer.waitFor(() =>
      expect(renderer.getRootNode().toString()).toContain(
        "between 2 and 100 characters",
      ),
    );
    expect(fetcher).toHaveBeenCalledTimes(1);

    renderer
      .find(SearchInput, { name: "lineItemSearch" })
      .trigger(
        "onChange",
        " 50%_\\ support " as unknown as (value: string) => void,
      );
    await renderer.waitFor(() =>
      expect(
        renderer.find(SearchInput, { name: "lineItemSearch" }).props.value,
      ).toBe(" 50%_\\ support "),
    );
    renderer.find(Button, (node) => node.text === "Apply").trigger("onClick");
    await renderer.waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
    await renderer.waitFor(() =>
      expect(renderer.find(StatusTag, { showRemoveIcon: true }).text).toContain(
        "Name: 50%_\\ support",
      ),
    );
    expect(fetcher.mock.calls[1]?.[0]).toContain(
      "lineItemSearch=50%25_%5C%20support",
    );
  });

  it("applies conjunctive event filters with portal-zone inclusive/exclusive dates", async () => {
    const fetcher = vi
      .fn<Fetcher>()
      .mockResolvedValueOnce(jsonResponse(response()))
      .mockResolvedValueOnce(jsonResponse(response([], [])));
    const { renderer } = renderCard(fetcher);
    await renderer.waitFor(() =>
      expect(renderer.getRootNode().toString()).toContain("Search and filters"),
    );

    renderer
      .find(Select, { name: "field" })
      .trigger(
        "onChange",
        "quantity" as unknown as (value: string | number | boolean) => void,
      );
    renderer
      .find(Input, { name: "lineItemId" })
      .trigger("onChange", "2002" as unknown as (value: string) => void);
    renderer.find(DateInput, { name: "fromDate" }).trigger("onChange", {
      year: 2026,
      month: 2,
      date: 29,
    } as unknown as (value: {
      year: number;
      month: number;
      date: number;
    }) => void);
    renderer.find(DateInput, { name: "throughDate" }).trigger("onChange", {
      year: 2026,
      month: 2,
      date: 29,
    } as unknown as (value: {
      year: number;
      month: number;
      date: number;
    }) => void);
    renderer.find(Button, (node) => node.text === "Apply").trigger("onClick");

    await renderer.waitFor(() =>
      expect(renderer.getRootNode().toString()).toContain(
        "No matching changes",
      ),
    );
    const url = fetcher.mock.calls[1]?.[0] ?? "";
    expect(url).toContain("eventType=PROPERTY_CHANGED");
    expect(url).toContain("field=quantity");
    expect(url).toContain("lineItemId=2002");
    expect(url).toContain("from=2026-03-28T23%3A00%3A00.000Z");
    expect(url).toContain("to=2026-03-29T22%3A00%3A00.000Z");
  });

  it("scopes Show history to the exact Line Item and exposes removable filters", async () => {
    const fetcher = vi
      .fn<Fetcher>()
      .mockResolvedValueOnce(jsonResponse(response()))
      .mockResolvedValueOnce(jsonResponse(response([], [event("scoped")])))
      .mockResolvedValueOnce(jsonResponse(response()));
    const { renderer } = renderCard(fetcher);
    await renderer.waitFor(() =>
      expect(renderer.getRootNode().toString()).toContain("Show history"),
    );
    renderer
      .find(SearchInput)
      .trigger("onChange", "not applied" as unknown as (value: string) => void);
    await renderer.waitFor(() =>
      expect(renderer.find(SearchInput).props.value).toBe("not applied"),
    );
    renderer
      .find(Button, (node) => node.text === "Show history")
      .trigger("onClick");
    await renderer.waitFor(() =>
      expect(renderer.getRootNode().toString()).toContain("Line Item: 2002"),
    );
    expect(fetcher.mock.calls[1]?.[0]).toContain("lineItemId=2002");
    expect(fetcher.mock.calls[1]?.[0]).not.toContain("lineItemSearch=");

    renderer
      .find(SearchInput)
      .trigger(
        "onChange",
        "still not applied" as unknown as (value: string) => void,
      );
    await renderer.waitFor(() =>
      expect(renderer.find(SearchInput).props.value).toBe("still not applied"),
    );

    const filterTag = renderer.find(
      StatusTag,
      (node) => node.text === "Line Item: 2002",
    );
    filterTag.trigger("onRemoveClick");
    await renderer.waitFor(() => expect(fetcher).toHaveBeenCalledTimes(3));
    expect(fetcher.mock.calls[2]?.[0]).not.toContain("lineItemId=");
    expect(fetcher.mock.calls[2]?.[0]).not.toContain("lineItemSearch=");
  });

  it("refreshes page one without polling and preserves visible data on failure", async () => {
    const failedRefresh = deferred<Response>();
    const fetcher = vi
      .fn<Fetcher>()
      .mockResolvedValueOnce(
        jsonResponse(
          response(
            [lineItem("2002", "Preserved")],
            [],
            page(10, true, "old-cursor"),
          ),
        ),
      )
      .mockReturnValueOnce(failedRefresh.promise);
    const { renderer } = renderCard(fetcher);
    await renderer.waitFor(() =>
      expect(renderer.getRootNode().toString()).toContain("Preserved"),
    );
    await Promise.resolve();
    expect(fetcher).toHaveBeenCalledTimes(1);

    renderer
      .find(LoadingButton, (node) => node.text === "Refresh")
      .trigger("onClick");
    expect(fetcher).toHaveBeenCalledTimes(2);
    expect(fetcher.mock.calls[1]?.[0]).not.toContain("Cursor=");
    expect(renderer.getRootNode().toString()).toContain("Preserved");
    failedRefresh.reject(new Error("private refresh failure"));
    await renderer.waitFor(() =>
      expect(renderer.getRootNode().toString()).toContain(
        "History could not be refreshed",
      ),
    );
    expect(renderer.getRootNode().toString()).toContain("Preserved");
    expect(renderer.getRootNode().toString()).not.toContain(
      "private refresh failure",
    );
  });

  it("keeps rapid refreshes generation-safe and blocks load-more during refresh", async () => {
    const olderRefresh = deferred<Response>();
    const newerRefresh = deferred<Response>();
    const fetcher = vi
      .fn<Fetcher>()
      .mockResolvedValueOnce(
        jsonResponse(
          response(
            [lineItem("2002", "Original")],
            [],
            page(10, true, "old-page"),
          ),
        ),
      )
      .mockReturnValueOnce(olderRefresh.promise)
      .mockReturnValueOnce(newerRefresh.promise);
    const { renderer } = renderCard(fetcher);
    await renderer.waitFor(() =>
      expect(renderer.getRootNode().toString()).toContain(
        "Load more line items",
      ),
    );

    renderer
      .find(LoadingButton, (node) => node.text === "Refresh")
      .trigger("onClick");
    await renderer.waitFor(() =>
      expect(renderer.getRootNode().toString()).toContain("Refreshing"),
    );
    renderer
      .find(LoadingButton, (node) => node.text === "Refreshing")
      .trigger("onClick");
    renderer
      .find(LoadingButton, (node) => node.text === "Load more line items")
      .trigger("onClick");
    expect(fetcher).toHaveBeenCalledTimes(3);

    newerRefresh.resolve(
      jsonResponse(response([lineItem("2003", "Newest refresh")], [])),
    );
    await renderer.waitFor(() =>
      expect(renderer.getRootNode().toString()).toContain("Newest refresh"),
    );
    olderRefresh.resolve(
      jsonResponse(response([lineItem("2004", "Stale refresh")], [])),
    );
    await Promise.resolve();
    expect(renderer.getRootNode().toString()).not.toContain("Stale refresh");
  });

  it("lets filter changes supersede refreshes and reset requests supersede filters", async () => {
    const staleRefresh = deferred<Response>();
    const staleFilter = deferred<Response>();
    const fetcher = vi
      .fn<Fetcher>()
      .mockResolvedValueOnce(jsonResponse(response()))
      .mockReturnValueOnce(staleRefresh.promise)
      .mockReturnValueOnce(staleFilter.promise)
      .mockResolvedValueOnce(
        jsonResponse(response([lineItem("2005", "Reset result")], [])),
      );
    const { renderer } = renderCard(fetcher);
    await renderer.waitFor(() =>
      expect(renderer.getRootNode().toString()).toContain("Search and filters"),
    );

    renderer
      .find(LoadingButton, (node) => node.text === "Refresh")
      .trigger("onClick");
    renderer
      .find(SearchInput)
      .trigger("onChange", "alpha" as unknown as (value: string) => void);
    await renderer.waitFor(() =>
      expect(renderer.find(SearchInput).props.value).toBe("alpha"),
    );
    renderer.find(Button, (node) => node.text === "Apply").trigger("onClick");
    await renderer.waitFor(() =>
      expect(renderer.getRootNode().toString()).toContain("Name: alpha"),
    );
    renderer
      .find(Button, (node) => node.text === "Clear all")
      .trigger("onClick");

    await renderer.waitFor(() =>
      expect(renderer.getRootNode().toString()).toContain("Reset result"),
    );
    expect(fetcher).toHaveBeenCalledTimes(4);
    staleFilter.resolve(
      jsonResponse(response([lineItem("2006", "Stale filter")], [])),
    );
    staleRefresh.resolve(
      jsonResponse(response([lineItem("2007", "Stale refresh")], [])),
    );
    await Promise.resolve();
    expect(renderer.getRootNode().toString()).not.toContain("Stale filter");
    expect(renderer.getRootNode().toString()).not.toContain("Stale refresh");
  });

  it("ignores a stale filter response after a newer query succeeds", async () => {
    const stale = deferred<Response>();
    const fetcher = vi
      .fn<Fetcher>()
      .mockResolvedValueOnce(jsonResponse(response()))
      .mockReturnValueOnce(stale.promise)
      .mockResolvedValueOnce(
        jsonResponse(response([lineItem("2004", "Newest result")], [])),
      );
    const { renderer } = renderCard(fetcher);
    await renderer.waitFor(() =>
      expect(renderer.getRootNode().toString()).toContain("Search and filters"),
    );
    renderer
      .find(SearchInput)
      .trigger("onChange", "old" as unknown as (value: string) => void);
    await renderer.waitFor(() =>
      expect(renderer.find(SearchInput).props.value).toBe("old"),
    );
    renderer.find(Button, (node) => node.text === "Apply").trigger("onClick");
    renderer
      .find(SearchInput)
      .trigger("onChange", "new" as unknown as (value: string) => void);
    await renderer.waitFor(() =>
      expect(renderer.find(SearchInput).props.value).toBe("new"),
    );
    renderer.find(Button, (node) => node.text === "Apply").trigger("onClick");
    await renderer.waitFor(() =>
      expect(renderer.getRootNode().toString()).toContain("Newest result"),
    );
    stale.resolve(
      jsonResponse(response([lineItem("2003", "Stale result")], [])),
    );
    await Promise.resolve();
    expect(renderer.getRootNode().toString()).not.toContain("Stale result");
  });

  it("offers a page-one restart when a cursor is rejected", async () => {
    const fetcher = vi
      .fn<Fetcher>()
      .mockResolvedValueOnce(
        jsonResponse(
          response(
            [lineItem()],
            [event("existing")],
            page(10),
            page(20, true, "rejected-cursor"),
          ),
        ),
      )
      .mockResolvedValueOnce(
        jsonResponse(
          {
            error: {
              code: "INVALID_REQUEST",
              correlationId: "8bd0d958-d3db-4214-b235-99fbcf70a812",
            },
          },
          400,
        ),
      )
      .mockResolvedValueOnce(
        jsonResponse(response([lineItem()], [event("restarted")])),
      );
    const { renderer } = renderCard(fetcher);
    await renderer.waitFor(() =>
      expect(renderer.getRootNode().toString()).toContain("Load more changes"),
    );
    renderer
      .find(LoadingButton, (node) => node.text === "Load more changes")
      .trigger("onClick");
    await renderer.waitFor(() =>
      expect(renderer.getRootNode().toString()).toContain("Restart results"),
    );
    renderer
      .find(Button, (node) => node.text === "Restart results")
      .trigger("onClick");
    await renderer.waitFor(() => expect(fetcher).toHaveBeenCalledTimes(3));
    expect(fetcher.mock.calls[2]?.[0]).not.toContain("eventsCursor=");
  });

  it("stops accumulating at 50 Line Items and 100 events with truthful notices", async () => {
    const lineItems = (start: number) =>
      Array.from({ length: 10 }, (_, offset) =>
        lineItem(String(start + offset), `Item ${start + offset}`),
      );
    const events = (start: number) =>
      Array.from({ length: 20 }, (_, offset) =>
        event(`event_${start + offset}`, String(2000 + start + offset)),
      );
    const fetcher = vi
      .fn<Fetcher>()
      .mockResolvedValueOnce(
        jsonResponse(
          response(
            lineItems(1000),
            events(0),
            page(10, true, "line-1"),
            page(20, true, "event-1"),
          ),
        ),
      );
    for (let index = 1; index <= 4; index += 1) {
      fetcher.mockResolvedValueOnce(
        jsonResponse(
          response(
            lineItems(1000 + index * 10),
            [],
            page(10, true, `line-${index + 1}`),
            page(0),
          ),
        ),
      );
    }
    for (let index = 1; index <= 4; index += 1) {
      fetcher.mockResolvedValueOnce(
        jsonResponse(
          response(
            [],
            events(index * 20),
            page(0),
            page(20, true, `event-${index + 1}`),
          ),
        ),
      );
    }
    const { renderer } = renderCard(fetcher);
    await renderer.waitFor(() =>
      expect(renderer.getRootNode().toString()).toContain(
        "Load more line items",
      ),
    );
    for (let index = 1; index <= 4; index += 1) {
      renderer
        .find(LoadingButton, (node) => node.text === "Load more line items")
        .trigger("onClick");
      await renderer.waitFor(() =>
        expect(renderer.getRootNode().toString()).toContain(
          `Item ${1000 + index * 10}`,
        ),
      );
    }
    expect(renderer.getRootNode().toString()).toContain(
      "More retained Line Items may exist",
    );
    expect(renderer.findAll(Accordion)).toHaveLength(50);

    for (let index = 1; index <= 4; index += 1) {
      renderer
        .find(LoadingButton, (node) => node.text === "Load more changes")
        .trigger("onClick");
      await renderer.waitFor(() =>
        expect(renderer.getRootNode().toString()).toContain(
          `Latest retained name: Item ${2000 + index * 20}`,
        ),
      );
    }
    await renderer.waitFor(() =>
      expect(renderer.getRootNode().toString()).toContain(
        "More retained changes may exist",
      ),
    );
    expect(renderer.getRootNode().toString()).not.toContain("all results");
  });
});
