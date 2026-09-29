import {
  Accordion,
  Alert,
  Button,
  LoadingButton,
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
    expect(text).toContain("Line Item: Support");
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
      expect(renderer.findAll(LoadingButton)).toHaveLength(2),
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
    renderer.find(LoadingButton).trigger("onClick");

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
});
