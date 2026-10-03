import assert from "node:assert/strict";
import test from "node:test";
import { classifyFailure, safeCorrelationIds, sanitizeDiagnostic } from "../lib/evidence";

test("classifies auth, harness, and product failures distinctly", () => {
  assert.equal(classifyFailure("SYNTHETIC_AUTH_FAILURE: expired"), "SYNTHETIC_AUTH_FAILURE");
  assert.equal(classifyFailure("SYNTHETIC_HARNESS_FAILURE: missing config"), "SYNTHETIC_HARNESS_FAILURE");
  assert.equal(classifyFailure("expected card"), "PRODUCT_FAILURE");
});

test("redacts credentials, URLs, and request paths while retaining safe correlation IDs", () => {
  const raw = "authorization=Bearer-secret cookie=session-secret https://example.test/path/123456?token=no https://app.hubspot.com/contacts/123456/record/0-3/987654 /api/deals/456789/audit corr_SAFE12345678";
  const sanitized = sanitizeDiagnostic(raw);
  assert.doesNotMatch(sanitized, /Bearer-secret|session-secret|token=no/);
  assert.match(sanitized, /https:\/\/example\.test\/path\/\[id\]/);
  assert.match(sanitized, /https:\/\/app\.hubspot\.com\/\[REDACTED_RECORD\]/);
  assert.doesNotMatch(sanitized, /contacts\/123456|\/987654|\/456789/);
  assert.deepEqual(safeCorrelationIds(raw), ["corr_SAFE12345678"]);
});
