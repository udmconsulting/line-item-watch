import { describe, expect, it, vi } from "vitest";
import {
  createDealAuditClient,
  DealAuditClientError,
  type Fetcher,
} from "./client";
import { PUBLIC_ERROR_CODES } from "./contracts";
import { jsonResponse, page, response } from "../test/fixtures";

describe("Deal audit client", () => {
  it("uses the configured HTTPS origin, P.6 route, GET, and no custom request data", async () => {
    const fetcher = vi
      .fn<Fetcher>()
      .mockImplementation(() => Promise.resolve(jsonResponse(response())));
    const client = createDealAuditClient(
      "https://audit.example.com",
      1001,
      fetcher,
    );

    await client.read();

    expect(fetcher).toHaveBeenCalledWith(
      "https://audit.example.com/api/v1/line-item-watch/deals/1001/audit",
      { method: "GET" },
    );
    expect(fetcher.mock.calls[0]?.[1]).not.toHaveProperty("headers");
    expect(fetcher.mock.calls[0]?.[1]).not.toHaveProperty("body");
  });

  it("builds independent opaque-cursor pagination requests", async () => {
    const fetcher = vi
      .fn<Fetcher>()
      .mockResolvedValueOnce(jsonResponse(response([], [], page(10), page(0))))
      .mockResolvedValueOnce(jsonResponse(response([], [], page(0), page(20))));
    const client = createDealAuditClient(
      "https://audit.example.com",
      "1001",
      fetcher,
    );

    await client.read("lineItems", "line+/= cursor");
    await client.read("events", "events-cursor");

    expect(fetcher.mock.calls[0]?.[0]).toContain(
      "lineItemsLimit=10&eventsLimit=0&lineItemsCursor=line%2B%2F%3D%20cursor",
    );
    expect(fetcher.mock.calls[1]?.[0]).toContain(
      "lineItemsLimit=0&eventsLimit=20&eventsCursor=events-cursor",
    );
  });

  it.each([
    [undefined, "LOCAL_CONFIGURATION"],
    ["http://audit.example.com", "LOCAL_CONFIGURATION"],
    ["https://audit.example.com/path", "LOCAL_CONFIGURATION"],
    ["https://audit.example.com/", "LOCAL_CONFIGURATION"],
    ["https://audit.example.com?raw=value", "LOCAL_CONFIGURATION"],
    ["https://audit.example.com#fragment", "LOCAL_CONFIGURATION"],
    ["https://user@audit.example.com", "LOCAL_CONFIGURATION"],
    ["https://localhost", "LOCAL_CONFIGURATION"],
    ["https://127.0.0.1", "LOCAL_CONFIGURATION"],
  ])("rejects invalid API origin %s", (origin, code) => {
    expect(() =>
      createDealAuditClient(origin, "1001", vi.fn<Fetcher>()),
    ).toThrowError(expect.objectContaining({ code }));
  });

  it("rejects invalid context before issuing a request", () => {
    const fetcher = vi.fn<Fetcher>();
    expect(() =>
      createDealAuditClient("https://audit.example.com", 0, fetcher),
    ).toThrowError(expect.objectContaining({ code: "LOCAL_CONTEXT" }));
    expect(fetcher).not.toHaveBeenCalled();
  });

  it.each(PUBLIC_ERROR_CODES)(
    "maps public error %s and keeps only its correlation ID",
    async (publicCode) => {
      const fetcher = vi.fn<Fetcher>().mockResolvedValue(
        jsonResponse(
          {
            error: {
              code: publicCode,
              correlationId: "8bd0d958-d3db-4214-b235-99fbcf70a812",
              diagnostic: "must not escape",
              message: "raw backend message",
            },
            stackTrace: "must not escape",
          },
          403,
        ),
      );

      await expect(
        createDealAuditClient(
          "https://audit.example.com",
          "1001",
          fetcher,
        ).read(),
      ).rejects.toMatchObject({
        code: publicCode,
        correlationId: "8bd0d958-d3db-4214-b235-99fbcf70a812",
      });
    },
  );

  it("rejects a response whose section limits do not match the request", async () => {
    const fetcher = vi
      .fn<Fetcher>()
      .mockResolvedValue(jsonResponse(response([], [], page(10), page(20))));

    await expect(
      createDealAuditClient("https://audit.example.com", "1001", fetcher).read(
        "events",
        "cursor",
      ),
    ).rejects.toMatchObject({ code: "MALFORMED_RESPONSE" });
  });

  it.each([
    [429, {}, "RATE_LIMITED"],
    [
      500,
      { error: { code: "UNKNOWN_CODE", correlationId: "bad" } },
      "MALFORMED_RESPONSE",
    ],
    [200, { dealId: "1001" }, "MALFORMED_RESPONSE"],
  ])("maps HTTP %s to %s", async (status, body, code) => {
    const fetcher = vi
      .fn<Fetcher>()
      .mockResolvedValue(jsonResponse(body, status));
    await expect(
      createDealAuditClient(
        "https://audit.example.com",
        "1001",
        fetcher,
      ).read(),
    ).rejects.toMatchObject({ code });
  });

  it.each([
    [new Error("offline customer@example.com"), "NETWORK"],
    [Object.assign(new Error("slow"), { name: "TimeoutError" }), "TIMEOUT"],
  ])(
    "normalizes request failures without retaining diagnostics",
    async (failure, code) => {
      const fetcher = vi.fn<Fetcher>().mockRejectedValue(failure);
      const promise = createDealAuditClient(
        "https://audit.example.com",
        "1001",
        fetcher,
      ).read();
      const error = await promise.catch((caught: unknown) => caught);
      expect(error).toBeInstanceOf(DealAuditClientError);
      expect(error).toMatchObject({ code, correlationId: null });
      expect(String(error)).not.toContain("customer@example.com");
    },
  );
});
