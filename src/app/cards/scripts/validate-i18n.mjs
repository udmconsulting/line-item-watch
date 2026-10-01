import { readFile } from "node:fs/promises";

const root = new URL("../../../../", import.meta.url);
const readJson = async (relative) =>
  JSON.parse(await readFile(new URL(relative, root), "utf8"));
const readSource = async (relative) =>
  readFile(new URL(relative, root), "utf8");

const [
  contract,
  en,
  hu,
  propertiesSource,
  eventsSource,
  viewSource,
  coverageSource,
  valueSource,
] = await Promise.all([
  readJson(
    "backend/src/main/resources/supportability/localization-contract-v1.json",
  ),
  readJson("src/app/cards/i18n/en.json"),
  readJson("src/app/cards/i18n/hu.json"),
  readSource(
    "backend/src/main/java/com/udmconsulting/modules/lineitemwatch/domain/MonitoredLineItemProperty.java",
  ),
  readSource(
    "backend/src/main/java/com/udmconsulting/modules/lineitemwatch/domain/LineItemAuditType.java",
  ),
  readSource(
    "backend/src/main/java/com/udmconsulting/modules/lineitemwatch/application/DealAuditView.java",
  ),
  readSource(
    "backend/src/main/java/com/udmconsulting/modules/lineitemwatch/domain/LineItemHistoryCoverage.java",
  ),
  readSource(
    "backend/src/main/java/com/udmconsulting/modules/lineitemwatch/domain/ObservedValue.java",
  ),
]);

function fail(message) {
  throw new Error(`i18n validation failed: ${message}`);
}

function sorted(values) {
  return [...values].sort();
}

function assertSame(label, actual, expected) {
  if (JSON.stringify(sorted(actual)) !== JSON.stringify(sorted(expected))) {
    fail(
      `${label}: expected [${sorted(expected).join(", ")}], received [${sorted(actual).join(", ")}]`,
    );
  }
}

function enumValues(source, enumName) {
  const body = new RegExp(
    `enum\\s+${enumName}\\s*\\{([\\s\\S]*?)\\n\\s*\\}`,
    "m",
  ).exec(source)?.[1];
  if (!body) fail(`could not read backend enum ${enumName}`);
  return [...body.matchAll(/^\s*([A-Z][A-Z0-9_]*)(?:\s*[,;]|\s*$)/gm)].map(
    (match) => match[1],
  );
}

function flatten(value, prefix = "") {
  const entries = [];
  for (const [key, child] of Object.entries(value)) {
    const path = prefix ? `${prefix}.${key}` : key;
    if (typeof child === "string") entries.push(path);
    else if (child && typeof child === "object" && !Array.isArray(child))
      entries.push(...flatten(child, path));
    else fail(`${path} must be a string or translation object`);
  }
  return entries;
}

function placeholders(value) {
  return [...value.matchAll(/\{([a-zA-Z][a-zA-Z0-9]*)\}/g)].map(
    (match) => match[1],
  );
}

const namespaces = Object.keys(contract.keyNamespaces);
assertSame("English namespaces", Object.keys(en), namespaces);
assertSame("Hungarian namespaces", Object.keys(hu), namespaces);
assertSame("locale key parity", flatten(en), flatten(hu));

for (const key of flatten(en)) {
  const path = key.split(".");
  const enValue = path.reduce((value, segment) => value[segment], en);
  const huValue = path.reduce((value, segment) => value[segment], hu);
  assertSame(
    `${key} placeholders`,
    placeholders(huValue),
    placeholders(enValue),
  );
}

const apiProperties = [
  ...propertiesSource.matchAll(/^\s*[A-Z][A-Z0-9_]*\("[^"]+",\s*"([^"]+)"\)/gm),
].map((match) => match[1]);
const eventTypes = enumValues(eventsSource, "LineItemAuditType");
const membershipStates = enumValues(viewSource, "Membership");
const coverageModes = enumValues(coverageSource, "Mode");
const valueStates = enumValues(valueSource, "State");
const publicErrors = Object.keys(contract.publicErrors);
const localErrors = [
  "NETWORK",
  "TIMEOUT",
  "RATE_LIMITED",
  "MALFORMED_RESPONSE",
  "LOCAL_CONFIGURATION",
  "LOCAL_CONTEXT",
];

for (const [locale, bundle] of Object.entries({ en, hu })) {
  assertSame(`${locale} fields`, Object.keys(bundle.fields), apiProperties);
  assertSame(`${locale} events`, Object.keys(bundle.events), eventTypes);
  assertSame(
    `${locale} value states`,
    Object.keys(bundle.valueStates),
    valueStates,
  );
  assertSame(`${locale} membership`, Object.keys(bundle.membership), [
    ...membershipStates,
    "atDeletion",
  ]);
  assertSame(
    `${locale} history coverage`,
    Object.keys(bundle.historyCoverage),
    [...coverageModes, "unknownState", "disclaimer", "retentionLimited"],
  );
  assertSame(`${locale} error codes`, Object.keys(bundle.errors), [
    ...publicErrors,
    ...localErrors,
  ]);
  for (const code of [...publicErrors, ...localErrors]) {
    assertSame(`${locale} errors.${code}`, Object.keys(bundle.errors[code]), [
      "title",
      "description",
      "action",
    ]);
  }
}

for (const [code, prefix] of Object.entries(contract.publicErrors)) {
  if (prefix !== `errors.${code}`)
    fail(`backend public error mapping for ${code} is not canonical`);
}

for (const state of contract.formatting.distinctValueStates) {
  if (!(state in en.valueStates) && !(state in en.membership)) {
    fail(`backend distinct state ${state} has no translation`);
  }
}
