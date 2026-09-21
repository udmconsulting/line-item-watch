const API_BASE = 'https://api.hubapi.com';
const API_VERSION = '2026-09';
const LINE_ITEM_OBJECT_TYPE = 'line_items';

const PROPERTY_REQUESTS = [
  { name: 'name', category: 'direct' },
  { name: 'quantity', category: 'direct' },
  { name: 'price', category: 'direct' },
  { name: 'hs_discount_percentage', category: 'direct' },
  { name: 'amount', category: 'calculated-comparison' },
  { name: 'hs_total_discount', category: 'calculated-comparison' },
];

const PROPERTY_NAMES = PROPERTY_REQUESTS.map(({ name }) => name);

class ProbeError extends Error {
  constructor(message, exitCode = 1) {
    super(message);
    this.name = 'ProbeError';
    this.exitCode = exitCode;
  }
}

const requireEnvironmentVariable = (name) => {
  const value = process.env[name]?.trim();

  if (!value) {
    throw new ProbeError(
      `Missing required environment variable ${name}. ` +
        'See README.md for the manual run instructions.',
      2,
    );
  }

  return value;
};

const describeHubSpotError = (body) => {
  if (!body || typeof body !== 'object') {
    return undefined;
  }

  return [body.message, body.category, body.correlationId]
    .filter((value) => typeof value === 'string' && value.length > 0)
    .join(' | ');
};

const requestJson = async (accessToken, path) => {
  let response;

  try {
    response = await fetch(`${API_BASE}${path}`, {
      headers: {
        Accept: 'application/json',
        Authorization: `Bearer ${accessToken}`,
      },
      method: 'GET',
    });
  } catch (error) {
    throw new ProbeError(
      `Line-item history read failed before HubSpot responded: ${error.message}`,
    );
  }

  const responseText = await response.text();
  let responseBody;

  if (responseText) {
    try {
      responseBody = JSON.parse(responseText);
    } catch {
      throw new ProbeError(
        `Line-item history read returned HTTP ${response.status} with a non-JSON response.`,
      );
    }
  }

  if (!response.ok) {
    const detail = describeHubSpotError(responseBody);
    const suffix = detail ? ` HubSpot response: ${detail}` : '';

    if (response.status === 401) {
      throw new ProbeError(
        'Line-item history read failed authentication (HTTP 401). ' +
          `Check HUBSPOT_ACCESS_TOKEN.${suffix}`,
        3,
      );
    }

    if (response.status === 403) {
      throw new ProbeError(
        'Line-item history read was forbidden (HTTP 403). ' +
          `Check the app's line-item read scope and account permissions.${suffix}`,
        4,
      );
    }

    if (response.status === 404) {
      throw new ProbeError(
        `The line item or the ${API_VERSION} line-item history endpoint was ` +
          `not found. Legacy endpoint fallback is intentionally disabled.${suffix}`,
        5,
      );
    }

    throw new ProbeError(
      `Line-item history read failed with HTTP ${response.status}.${suffix}`,
    );
  }

  if (!responseBody || typeof responseBody !== 'object') {
    throw new ProbeError(
      'Line-item history read returned an empty or invalid JSON body.',
    );
  }

  return responseBody;
};

const normalizeValue = (value) => {
  if (value === null || value === undefined) return null;
  return String(value);
};

const normalizeVersion = (propertyName, version, originalIndex) => {
  if (!version || typeof version !== 'object' || Array.isArray(version)) {
    throw new ProbeError(
      `History for ${propertyName} contains a non-object version.`,
    );
  }

  const timestamp =
    typeof version.timestamp === 'string' ? version.timestamp : null;
  const timestampEpochMs = timestamp === null ? null : Date.parse(timestamp);

  return {
    propertyName,
    originalIndex,
    value: normalizeValue(version.value),
    timestamp,
    timestampEpochMs:
      timestampEpochMs === null || Number.isNaN(timestampEpochMs)
        ? null
        : timestampEpochMs,
    source: version.source ?? null,
    sourceType: version.sourceType ?? null,
    sourceId: version.sourceId ?? null,
    sourceLabel: version.sourceLabel ?? null,
    updatedByUserId: version.updatedByUserId ?? null,
    raw: version,
  };
};

const groupVersionsByTimestamp = (versions) => {
  const groups = [];

  for (const version of versions) {
    const previousGroup = groups.at(-1);

    if (previousGroup?.timestampEpochMs === version.timestampEpochMs) {
      previousGroup.versions.push(version);
      if (!previousGroup.values.includes(version.value)) {
        previousGroup.values.push(version.value);
      }
      continue;
    }

    groups.push({
      timestamp: version.timestamp,
      timestampEpochMs: version.timestampEpochMs,
      values: [version.value],
      versions: [version],
    });
  }

  return groups;
};

const historyMetadataKeys = (rawHistory) =>
  [...new Set(rawHistory.flatMap((version) => Object.keys(version)))].sort();

const analyzePropertyHistory = (
  propertyName,
  category,
  currentValue,
  rawHistory,
) => {
  if (!Array.isArray(rawHistory)) {
    throw new ProbeError(
      `propertiesWithHistory.${propertyName} is not an array.`,
    );
  }

  const normalizedVersions = rawHistory.map((version, index) =>
    normalizeVersion(propertyName, version, index),
  );
  const versionsWithoutAuthoritativeTimestamp = normalizedVersions.filter(
    ({ timestampEpochMs }) => timestampEpochMs === null,
  );
  const timestampedVersions = normalizedVersions
    .filter(({ timestampEpochMs }) => timestampEpochMs !== null)
    .sort((left, right) => left.timestampEpochMs - right.timestampEpochMs);
  const timestampGroups = groupVersionsByTimestamp(timestampedVersions);
  const ambiguousTimestampGroups = timestampGroups.filter(
    ({ values }) => values.length > 1,
  );
  const deterministicOrdering =
    versionsWithoutAuthoritativeTimestamp.length === 0 &&
    ambiguousTimestampGroups.length === 0;

  const orderedTimeline = deterministicOrdering
    ? timestampGroups.map((group) => ({
        timestamp: group.timestamp,
        timestampEpochMs: group.timestampEpochMs,
        value: group.values[0],
        versions: group.versions,
      }))
    : [];

  const adjacentTransitions = [];

  for (let index = 1; index < orderedTimeline.length; index += 1) {
    const from = orderedTimeline[index - 1];
    const to = orderedTimeline[index];

    adjacentTransitions.push({
      from: {
        value: from.value,
        timestamp: from.timestamp,
      },
      to: {
        value: to.value,
        timestamp: to.timestamp,
      },
      changed: from.value !== to.value,
      toVersionMetadata: to.versions.map(
        ({ source, sourceType, sourceId, sourceLabel, updatedByUserId, raw }) => ({
          source,
          sourceType,
          sourceId,
          sourceLabel,
          updatedByUserId,
          additionalFields: Object.fromEntries(
            Object.entries(raw).filter(
              ([key]) =>
                ![
                  'value',
                  'timestamp',
                  'source',
                  'sourceType',
                  'sourceId',
                  'sourceLabel',
                  'updatedByUserId',
                ].includes(key),
            ),
          ),
        }),
      ),
    });
  }

  const latestVersion = orderedTimeline.at(-1) ?? null;
  const normalizedCurrentValue = normalizeValue(currentValue);

  return {
    category,
    currentValue: normalizedCurrentValue,
    historyVersionCount: rawHistory.length,
    metadataKeys: historyMetadataKeys(rawHistory),
    deterministicOrdering,
    orderingProblems: {
      versionsWithoutAuthoritativeTimestamp,
      ambiguousTimestampGroups,
    },
    orderedVersions: orderedTimeline,
    adjacentTransitions,
    valueChanges: adjacentTransitions.filter(({ changed }) => changed),
    currentValueIncludedInHistory:
      latestVersion === null
        ? false
        : latestVersion.value === normalizedCurrentValue,
    rawHistory,
  };
};

const findExplicitCorrelationFields = (propertyHistories) => {
  const correlationKeyPattern =
    /(?:correlation|transaction|trace|request)[A-Za-z_]*id/i;
  const findings = [];

  for (const [propertyName, history] of Object.entries(propertyHistories)) {
    history.rawHistory.forEach((version, versionIndex) => {
      for (const [key, value] of Object.entries(version)) {
        if (correlationKeyPattern.test(key)) {
          findings.push({ propertyName, versionIndex, key, value });
        }
      }
    });
  }

  return findings;
};

const findCrossPropertyTimestampGroups = (propertyHistories) => {
  const groups = new Map();

  for (const [propertyName, history] of Object.entries(propertyHistories)) {
    for (const version of history.orderedVersions) {
      const entries = groups.get(version.timestamp) ?? [];
      entries.push({ propertyName, value: version.value });
      groups.set(version.timestamp, entries);
    }
  }

  return [...groups.entries()]
    .map(([timestamp, entries]) => ({ timestamp, entries }))
    .filter(
      ({ entries }) =>
        new Set(entries.map(({ propertyName }) => propertyName)).size > 1,
    )
    .sort(
      (left, right) => Date.parse(left.timestamp) - Date.parse(right.timestamp),
    );
};

const formatMetadata = (transition) => {
  const metadata = transition.toVersionMetadata[0] ?? {};
  const fields = [
    ['source', metadata.source],
    ['sourceType', metadata.sourceType],
    ['sourceId', metadata.sourceId],
    ['sourceLabel', metadata.sourceLabel],
    ['updatedByUserId', metadata.updatedByUserId],
  ]
    .filter(([, value]) => value !== null && value !== undefined)
    .map(([key, value]) => `${key}=${String(value)}`);

  return fields.length > 0 ? `; ${fields.join(', ')}` : '';
};

const displayValue = (value) =>
  value === null ? '(null)' : JSON.stringify(value);

const printHumanSummary = (result) => {
  console.log('Line Item Watch — Gate 2 property-history probe');
  console.log(`API version: ${result.apiVersion}`);
  console.log(
    `Line item ${result.lineItem.id}: ${result.lineItem.properties.name ?? '(unnamed)'}`,
  );
  console.log(`  createdAt: ${result.lineItem.createdAt ?? '(not returned)'}`);
  console.log(`  updatedAt: ${result.lineItem.updatedAt ?? '(not returned)'}`);
  console.log(`  archived: ${String(result.lineItem.archived)}`);

  for (const propertyName of PROPERTY_NAMES) {
    const history = result.propertyHistories[propertyName];
    console.log(`\n${propertyName} (${history.category})`);
    console.log(`  current: ${displayValue(history.currentValue)}`);
    console.log(`  history versions: ${history.historyVersionCount}`);
    console.log(
      `  deterministic timestamp ordering: ${history.deterministicOrdering ? 'yes' : 'no'}`,
    );
    console.log(
      `  current value included in history: ${
        history.currentValueIncludedInHistory ? 'yes' : 'no'
      }`,
    );
    console.log(
      `  metadata keys: ${history.metadataKeys.join(', ') || 'none returned'}`,
    );

    if (!history.deterministicOrdering) {
      console.log('  transitions: unavailable without guessing');
      continue;
    }

    if (history.valueChanges.length === 0) {
      console.log('  transitions: no value change returned');
      continue;
    }

    console.log('  transitions:');
    for (const transition of history.valueChanges) {
      console.log(
        `    ${displayValue(transition.from.value)} -> ` +
          `${displayValue(transition.to.value)} at ${transition.to.timestamp}` +
          formatMetadata(transition),
      );
    }
  }

  console.log('\nCross-property evidence');
  console.log(
    `  exact shared timestamps: ${result.crossPropertyEvidence.exactTimestampGroups.length}`,
  );
  console.log(
    '  explicit correlation-like fields: ' +
      result.crossPropertyEvidence.explicitCorrelationFields.length,
  );
  console.log(
    '  Note: matching timestamps or source metadata are evidence only, ' +
      'not a stable transaction identifier.',
  );

  console.log('\nMachine-readable JSON:');
  console.log(JSON.stringify(result, null, 2));
};

const main = async () => {
  const accessToken = requireEnvironmentVariable('HUBSPOT_ACCESS_TOKEN');
  const lineItemId = requireEnvironmentVariable('HUBSPOT_LINE_ITEM_ID');
  const query = new URLSearchParams({
    properties: PROPERTY_NAMES.join(','),
    propertiesWithHistory: PROPERTY_NAMES.join(','),
    archived: 'false',
  });
  const endpoint =
    `/crm/objects/${API_VERSION}/${LINE_ITEM_OBJECT_TYPE}/` +
    `${encodeURIComponent(lineItemId)}?${query.toString()}`;
  const lineItem = await requestJson(accessToken, endpoint);

  if (String(lineItem.id) !== lineItemId) {
    throw new ProbeError(
      `Line-item read returned unexpected ID ${String(lineItem.id)} instead of ${lineItemId}.`,
    );
  }

  if (!lineItem.properties || typeof lineItem.properties !== 'object') {
    throw new ProbeError(
      'Line-item history read returned an unexpected response without properties.',
    );
  }

  const rawPropertiesWithHistory =
    lineItem.propertiesWithHistory &&
    typeof lineItem.propertiesWithHistory === 'object' &&
    !Array.isArray(lineItem.propertiesWithHistory)
      ? lineItem.propertiesWithHistory
      : {};

  const propertyHistories = Object.fromEntries(
    PROPERTY_REQUESTS.map(({ name, category }) => [
      name,
      analyzePropertyHistory(
        name,
        category,
        lineItem.properties[name] ?? null,
        rawPropertiesWithHistory[name] ?? [],
      ),
    ]),
  );

  const result = {
    apiBase: API_BASE,
    apiVersion: API_VERSION,
    readOnly: true,
    request: {
      method: 'GET',
      endpoint,
      properties: PROPERTY_NAMES,
      propertiesWithHistory: PROPERTY_NAMES,
      archived: false,
    },
    lineItem: {
      id: String(lineItem.id),
      createdAt: lineItem.createdAt ?? null,
      updatedAt: lineItem.updatedAt ?? null,
      archived: lineItem.archived ?? null,
      archivedAt: lineItem.archivedAt ?? null,
      objectWriteTraceId: lineItem.objectWriteTraceId ?? null,
      url: lineItem.url ?? null,
      properties: Object.fromEntries(
        PROPERTY_NAMES.map((name) => [name, lineItem.properties[name] ?? null]),
      ),
    },
    propertyHistories,
    crossPropertyEvidence: {
      exactTimestampGroups: findCrossPropertyTimestampGroups(propertyHistories),
      explicitCorrelationFields:
        findExplicitCorrelationFields(propertyHistories),
    },
    rawRelevantHistory: rawPropertiesWithHistory,
  };

  printHumanSummary(result);
};

main().catch((error) => {
  if (error instanceof ProbeError) {
    console.error(`Gate 2 probe failed: ${error.message}`);
    process.exitCode = error.exitCode;
    return;
  }

  console.error(
    `Gate 2 probe failed with an unexpected error: ${error?.message ?? String(error)}`,
  );
  process.exitCode = 1;
});
