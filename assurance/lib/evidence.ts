import type { TestCase, TestResult } from "@playwright/test/reporter";

export type FailureClass =
  | "PRODUCT_FAILURE"
  | "SYNTHETIC_AUTH_FAILURE"
  | "SYNTHETIC_HARNESS_FAILURE";

const secretPattern =
  /(?:authorization|cookie|token|signature|password|secret|storageState)\s*[:=]\s*[^\s,;]+/gi;
const queryPattern = /https:\/\/[^\s?#]+(?:\?[^\s#]*)?/g;
const correlationPattern =
  /\b(?:[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}|(?:corr|req)_[A-Za-z0-9_-]{8,80})\b/gi;

export function classifyFailure(message: string): FailureClass {
  if (message.includes("SYNTHETIC_AUTH_FAILURE")) {
    return "SYNTHETIC_AUTH_FAILURE";
  }
  if (message.includes("SYNTHETIC_HARNESS_FAILURE")) {
    return "SYNTHETIC_HARNESS_FAILURE";
  }
  return "PRODUCT_FAILURE";
}

export function sanitizeDiagnostic(value: string): string {
  return value
    .replace(secretPattern, "[REDACTED]")
    .replace(queryPattern, (url) => {
      try {
        const parsed = new URL(url);
        if (parsed.hostname === "app.hubspot.com") {
          return `${parsed.origin}/[REDACTED_RECORD]`;
        }
        const safePath = parsed.pathname.replace(/\/[0-9]{3,}(?=\/|$)/g, "/[id]");
        return `${parsed.origin}${safePath}`;
      } catch {
        return "[REDACTED_URL]";
      }
    })
    .replace(/\/[0-9]{3,}(?=\/|\s|[,'"\]\)]|$)/g, "/[id]")
    .slice(0, 2_000);
}

export function safeCorrelationIds(value: string): string[] {
  return [...new Set(value.match(correlationPattern) ?? [])].slice(0, 5);
}

export function createEvidence(
  test: TestCase,
  result: TestResult,
  environment: string,
  releaseDigest: string,
) {
  const message = result.errors.map((error) => error.message ?? "").join("\n");
  return {
    schema: "line-item-watch.synthetic-result.v1",
    environment,
    journey: test.title,
    releaseDigest,
    outcome: result.status === "passed" ? "PASS" : "FAIL",
    failureClass:
      result.status === "passed" ? null : classifyFailure(message),
    failingStep:
      result.status === "passed" ? null : sanitizeDiagnostic(message),
    correlationIds: safeCorrelationIds(message),
    durationMs: result.duration,
    attempt: result.retry,
  };
}
