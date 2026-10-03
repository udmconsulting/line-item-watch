import assert from "node:assert/strict";
import test from "node:test";
import { loadLiveConfig } from "../lib/config";

const valid = {
  LIW_ASSURANCE_TARGET: "staging",
  LIW_HUBSPOT_RECORD_URL: "https://app.hubspot.com/contacts/123/record/0-3/456",
  LIW_API_ORIGIN: "https://staging-api.example.com",
  LIW_HUBSPOT_STORAGE_STATE_JSON: '{"cookies":[],"origins":[]}',
  LIW_ASSURANCE_FIXTURE_JSON:
    '{"fixtureId":"staging-fixture","primaryLineItem":"Synthetic support","searchTerm":"Synthetic","expectedEventText":"Property changed"}',
  LIW_RELEASE_DIGEST: `sha256:${"a".repeat(64)}`,
};

test("live targets fail closed and never default to production", () => {
  assert.throws(() => loadLiveConfig("production", {}), /missing.*TARGET/);
  assert.throws(() => loadLiveConfig("production", valid), /target must be production/);
});

test("rejects non-HubSpot URLs and malformed secret state", () => {
  assert.throws(
    () => loadLiveConfig("staging", { ...valid, LIW_HUBSPOT_RECORD_URL: "https://example.com" }),
    /app\.hubspot\.com/,
  );
  assert.throws(
    () => loadLiveConfig("staging", { ...valid, LIW_HUBSPOT_STORAGE_STATE_JSON: "not-json" }),
    /JSON object/,
  );
});

test("accepts an explicit isolated staging contract", () => {
  const config = loadLiveConfig("staging", valid);
  assert.equal(config.target, "staging");
  assert.equal(config.fixture.fixtureId, "staging-fixture");
});
