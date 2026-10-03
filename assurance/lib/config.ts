import type { BrowserContextOptions } from "@playwright/test";

export type LiveTarget = "staging" | "production";

export interface LiveFixture {
  readonly fixtureId: string;
  readonly primaryLineItem: string;
  readonly searchTerm: string;
  readonly expectedEventText: string;
}

type StorageState = Exclude<
  BrowserContextOptions["storageState"],
  string | undefined
>;

export interface LiveConfig {
  readonly target: LiveTarget;
  readonly recordUrl: string;
  readonly apiOrigin: string;
  readonly storageState: StorageState;
  readonly fixture: LiveFixture;
  readonly releaseDigest: string;
}

function required(env: NodeJS.ProcessEnv, name: string): string {
  const value = env[name];
  if (!value || value.trim() !== value) {
    throw new Error(`SYNTHETIC_HARNESS_FAILURE: missing or invalid ${name}`);
  }
  return value;
}

function parseObject(value: string, name: string): object {
  try {
    const parsed: unknown = JSON.parse(value);
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
      throw new Error("not an object");
    }
    return parsed;
  } catch {
    throw new Error(`SYNTHETIC_HARNESS_FAILURE: ${name} must be JSON object`);
  }
}

export function loadLiveConfig(
  expectedTarget: LiveTarget,
  env: NodeJS.ProcessEnv = process.env,
): LiveConfig {
  const target = required(env, "LIW_ASSURANCE_TARGET");
  if (target !== expectedTarget) {
    throw new Error(
      `SYNTHETIC_HARNESS_FAILURE: target must be ${expectedTarget}`,
    );
  }
  let recordUrl: URL;
  let apiOrigin: URL;
  try {
    recordUrl = new URL(required(env, "LIW_HUBSPOT_RECORD_URL"));
    apiOrigin = new URL(required(env, "LIW_API_ORIGIN"));
  } catch {
    throw new Error("SYNTHETIC_HARNESS_FAILURE: live URLs must be absolute");
  }
  if (
    recordUrl.protocol !== "https:" ||
    recordUrl.hostname !== "app.hubspot.com" ||
    recordUrl.username ||
    recordUrl.password ||
    recordUrl.hash
  ) {
    throw new Error(
      "SYNTHETIC_HARNESS_FAILURE: record URL must be an HTTPS app.hubspot.com URL without credentials or fragment",
    );
  }
  if (
    apiOrigin.protocol !== "https:" ||
    apiOrigin.origin !== apiOrigin.toString().replace(/\/$/, "")
  ) {
    throw new Error(
      "SYNTHETIC_HARNESS_FAILURE: LIW_API_ORIGIN must be an HTTPS origin",
    );
  }
  const storageStateSource = parseObject(
    required(env, "LIW_HUBSPOT_STORAGE_STATE_JSON"),
    "LIW_HUBSPOT_STORAGE_STATE_JSON",
  ) as Partial<StorageState>;
  if (
    !Array.isArray(storageStateSource.cookies) ||
    !Array.isArray(storageStateSource.origins)
  ) {
    throw new Error(
      "SYNTHETIC_HARNESS_FAILURE: storage state requires cookies and origins arrays",
    );
  }
  const storageState = storageStateSource as StorageState;
  const fixtureSource = parseObject(
    required(env, "LIW_ASSURANCE_FIXTURE_JSON"),
    "LIW_ASSURANCE_FIXTURE_JSON",
  ) as Partial<LiveFixture>;
  const fixture: LiveFixture = {
    fixtureId: String(fixtureSource.fixtureId ?? ""),
    primaryLineItem: String(fixtureSource.primaryLineItem ?? ""),
    searchTerm: String(fixtureSource.searchTerm ?? ""),
    expectedEventText: String(fixtureSource.expectedEventText ?? ""),
  };
  if (Object.values(fixture).some((value) => !value || value.length > 100)) {
    throw new Error(
      "SYNTHETIC_HARNESS_FAILURE: fixture fields must be 1-100 characters",
    );
  }
  const releaseDigest = required(env, "LIW_RELEASE_DIGEST");
  if (!/^sha256:[0-9a-f]{64}$/.test(releaseDigest)) {
    throw new Error(
      "SYNTHETIC_HARNESS_FAILURE: LIW_RELEASE_DIGEST must be a sha256 digest",
    );
  }
  return {
    target: expectedTarget,
    recordUrl: recordUrl.toString(),
    apiOrigin: apiOrigin.origin,
    storageState,
    fixture,
    releaseDigest,
  };
}
