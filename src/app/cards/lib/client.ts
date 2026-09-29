import {
  ContractValidationError,
  parseDealAuditResponse,
  parsePositiveProviderId,
  parsePublicErrorResponse,
  type DealAuditResponse,
  type PublicErrorCode,
} from "./contracts";

export type Fetcher = (
  url: string,
  options?: { method?: "GET" },
) => Promise<Response>;
export type RequestSection = "initial" | "lineItems" | "events";
export type LocalErrorCode =
  | "NETWORK"
  | "TIMEOUT"
  | "RATE_LIMITED"
  | "MALFORMED_RESPONSE"
  | "LOCAL_CONFIGURATION"
  | "LOCAL_CONTEXT";

export class DealAuditClientError extends Error {
  public constructor(
    public readonly code: PublicErrorCode | LocalErrorCode,
    public readonly correlationId: string | null = null,
  ) {
    super("The Deal audit request could not be completed.");
    this.name = "DealAuditClientError";
  }
}

export interface DealAuditClient {
  read(section?: RequestSection, cursor?: string): Promise<DealAuditResponse>;
}

const sectionLimits = {
  initial: { lineItems: 10, events: 20 },
  lineItems: { lineItems: 10, events: 0 },
  events: { lineItems: 0, events: 20 },
} as const;

function validateApiOrigin(value: unknown): string {
  if (typeof value !== "string" || value.trim() !== value || value === "") {
    throw new DealAuditClientError("LOCAL_CONFIGURATION");
  }
  try {
    const parsed = new URL(value);
    const hostname = parsed.hostname.toLowerCase();
    if (
      parsed.protocol !== "https:" ||
      hostname === "localhost" ||
      hostname.endsWith(".localhost") ||
      hostname.startsWith("127.") ||
      hostname === "[::1]" ||
      parsed.username !== "" ||
      parsed.password !== "" ||
      parsed.pathname !== "/" ||
      parsed.search !== "" ||
      parsed.hash !== "" ||
      value !== parsed.origin
    ) {
      throw new DealAuditClientError("LOCAL_CONFIGURATION");
    }
    return parsed.origin;
  } catch (error) {
    if (error instanceof DealAuditClientError) throw error;
    throw new DealAuditClientError("LOCAL_CONFIGURATION");
  }
}

function validateDealId(value: unknown): string {
  try {
    if (typeof value === "number") {
      if (!Number.isSafeInteger(value) || value <= 0)
        throw new Error("invalid Deal ID");
      return String(value);
    }
    return parsePositiveProviderId(value);
  } catch {
    throw new DealAuditClientError("LOCAL_CONTEXT");
  }
}

async function parseJson(response: Response): Promise<unknown> {
  try {
    return await response.json();
  } catch {
    throw new DealAuditClientError("MALFORMED_RESPONSE");
  }
}

export function createDealAuditClient(
  apiOrigin: unknown,
  dealIdValue: unknown,
  fetcher: Fetcher,
): DealAuditClient {
  const origin = validateApiOrigin(apiOrigin);
  const dealId = validateDealId(dealIdValue);
  const endpoint = `${origin}/api/v1/line-item-watch/deals/${encodeURIComponent(dealId)}/audit`;

  return {
    async read(section = "initial", cursor): Promise<DealAuditResponse> {
      const parameters: string[] = [];
      if (section === "lineItems") {
        parameters.push("lineItemsLimit=10", "eventsLimit=0");
        if (cursor)
          parameters.push(`lineItemsCursor=${encodeURIComponent(cursor)}`);
      } else if (section === "events") {
        parameters.push("lineItemsLimit=0", "eventsLimit=20");
        if (cursor)
          parameters.push(`eventsCursor=${encodeURIComponent(cursor)}`);
      }
      const url =
        parameters.length === 0
          ? endpoint
          : `${endpoint}?${parameters.join("&")}`;

      let response: Response;
      try {
        response = await fetcher(url, { method: "GET" });
      } catch (error) {
        const name = error instanceof Error ? error.name : "";
        throw new DealAuditClientError(
          name === "AbortError" || name === "TimeoutError"
            ? "TIMEOUT"
            : "NETWORK",
        );
      }

      if (response.status === 429)
        throw new DealAuditClientError("RATE_LIMITED");
      const body = await parseJson(response);
      if (!response.ok) {
        try {
          const parsed = parsePublicErrorResponse(body);
          throw new DealAuditClientError(
            parsed.error.code,
            parsed.error.correlationId,
          );
        } catch (error) {
          if (error instanceof DealAuditClientError) throw error;
          throw new DealAuditClientError("MALFORMED_RESPONSE");
        }
      }

      try {
        const parsed = parseDealAuditResponse(body, dealId);
        const expected = sectionLimits[section];
        if (
          parsed.lineItems.page.limit !== expected.lineItems ||
          parsed.events.page.limit !== expected.events
        ) {
          throw new ContractValidationError();
        }
        return parsed;
      } catch (error) {
        if (error instanceof ContractValidationError) {
          throw new DealAuditClientError("MALFORMED_RESPONSE");
        }
        throw error;
      }
    },
  };
}
