const API_BASE = 'https://api.hubapi.com';
const API_VERSION = '2026-09';
const LINE_ITEM_OBJECT_TYPE = 'line_items';
const PROPERTY_NAMES = [
  'name',
  'quantity',
  'price',
  'hs_discount_percentage',
];

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
  if (!body || typeof body !== 'object' || Array.isArray(body)) {
    return undefined;
  }

  return [body.message, body.category, body.correlationId]
    .filter((value) => typeof value === 'string' && value.length > 0)
    .join(' | ');
};

const requestJson = async (accessToken, endpoint, description) => {
  let response;

  try {
    response = await fetch(`${API_BASE}${endpoint}`, {
      headers: {
        Accept: 'application/json',
        Authorization: `Bearer ${accessToken}`,
      },
      method: 'GET',
    });
  } catch (error) {
    throw new ProbeError(
      `${description} failed before HubSpot responded: ${error.message}`,
    );
  }

  const responseText = await response.text();
  let responseBody = null;

  if (responseText) {
    try {
      responseBody = JSON.parse(responseText);
    } catch {
      throw new ProbeError(
        `${description} returned HTTP ${response.status} with a non-JSON response.`,
      );
    }
  }

  const detail = describeHubSpotError(responseBody);
  const suffix = detail ? ` HubSpot response: ${detail}` : '';

  if (response.status === 401) {
    throw new ProbeError(
      `${description} failed authentication (HTTP 401). ` +
        `Check HUBSPOT_ACCESS_TOKEN.${suffix}`,
      3,
    );
  }

  if (response.status === 403) {
    throw new ProbeError(
      `${description} was forbidden (HTTP 403). ` +
        `Check the app's line-item read scope and account permissions.${suffix}`,
      4,
    );
  }

  if (!response.ok && response.status !== 404) {
    throw new ProbeError(
      `${description} failed with HTTP ${response.status}.${suffix}`,
      5,
    );
  }

  return {
    ok: response.ok,
    status: response.status,
    body: responseBody,
    error: response.ok
      ? null
      : {
          message: responseBody?.message ?? null,
          category: responseBody?.category ?? null,
          correlationId: responseBody?.correlationId ?? null,
        },
  };
};

const isRecord = (value) =>
  value !== null && typeof value === 'object' && !Array.isArray(value);

const requestedProperties = (record) => {
  const properties = isRecord(record?.properties) ? record.properties : {};

  return Object.fromEntries(
    PROPERTY_NAMES.map((name) => [
      name,
      Object.hasOwn(properties, name) ? properties[name] : null,
    ]),
  );
};

const requestedPropertyPresence = (record) => {
  const properties = isRecord(record?.properties) ? record.properties : {};

  return Object.fromEntries(
    PROPERTY_NAMES.map((name) => [name, Object.hasOwn(properties, name)]),
  );
};

const requestedHistory = (record) => {
  const histories = isRecord(record?.propertiesWithHistory)
    ? record.propertiesWithHistory
    : {};

  return Object.fromEntries(
    PROPERTY_NAMES.map((name) => [
      name,
      Array.isArray(histories[name]) ? histories[name] : [],
    ]),
  );
};

const associationIds = (record, objectType) => {
  if (!isRecord(record?.associations)) return [];

  const normalizedObjectType = objectType.toLowerCase().replaceAll('_', '');
  const association = Object.entries(record.associations).find(([key]) =>
    key.toLowerCase().replaceAll('_', '').startsWith(normalizedObjectType),
  )?.[1];
  const results = association?.results;

  if (!Array.isArray(results)) return [];

  return results
    .map((entry) => entry?.id)
    .filter((id) => typeof id === 'string');
};

const analyzeArchivedResponse = (response) => {
  if (!response.ok || !isRecord(response.body)) {
    return {
      recordRetrievable: false,
      propertiesRetrievable: false,
      propertyHistoryRetrievable: false,
      formerDealAssociationAvailable: false,
      archivedStateClearlyIdentified: false,
      requestedProperties: Object.fromEntries(
        PROPERTY_NAMES.map((name) => [name, null]),
      ),
      requestedPropertyPresence: Object.fromEntries(
        PROPERTY_NAMES.map((name) => [name, false]),
      ),
      requestedHistory: Object.fromEntries(
        PROPERTY_NAMES.map((name) => [name, []]),
      ),
      formerDealIds: [],
    };
  }

  const properties = requestedProperties(response.body);
  const propertyPresence = requestedPropertyPresence(response.body);
  const history = requestedHistory(response.body);
  const formerDealIds = associationIds(response.body, 'deals');

  return {
    recordRetrievable: true,
    propertiesRetrievable: Object.values(propertyPresence).some(Boolean),
    propertyHistoryRetrievable: Object.values(history).some(
      (versions) => versions.length > 0,
    ),
    formerDealAssociationAvailable: formerDealIds.length > 0,
    archivedStateClearlyIdentified:
      response.body.archived === true &&
      typeof response.body.archivedAt === 'string' &&
      response.body.archivedAt.length > 0,
    requestedProperties: properties,
    requestedPropertyPresence: propertyPresence,
    requestedHistory: history,
    formerDealIds,
  };
};

const chooseModel = (analysis) => {
  if (
    analysis.recordRetrievable &&
    analysis.propertiesRetrievable &&
    analysis.propertyHistoryRetrievable &&
    analysis.archivedStateClearlyIdentified
  ) {
    return {
      model: 'MODEL A',
      reason:
        'The archived line item, requested properties, property history, and deletion metadata are retrievable.',
    };
  }

  return {
    model: 'MODEL B',
    reason:
      'The archived object response is not sufficient to recover the deleted line item and its property history deterministically.',
  };
};

const yesNo = (value) => (value ? 'yes' : 'no');

const printHumanSummary = (result) => {
  const { analysis } = result;

  console.log('Line Item Watch — deletion-recovery probe');
  console.log(`API version: ${result.apiVersion}`);
  console.log(`Line item: ${result.lineItemId}`);
  console.log(
    `Normal GET: HTTP ${result.requests.normal.responseStatus}; retrievable: ${yesNo(result.requests.normal.retrievable)}`,
  );
  console.log(
    `Archived GET: HTTP ${result.requests.archived.responseStatus}; retrievable: ${yesNo(analysis.recordRetrievable)}`,
  );
  console.log('\nA. Deleted record retrievable');
  console.log(`  ${yesNo(analysis.recordRetrievable)}`);
  console.log('\nB. Requested properties retrievable');
  console.log(`  ${yesNo(analysis.propertiesRetrievable)}`);
  for (const propertyName of PROPERTY_NAMES) {
    console.log(
      `  ${propertyName}: ${
        analysis.requestedPropertyPresence[propertyName]
          ? JSON.stringify(analysis.requestedProperties[propertyName])
          : '(not returned)'
      }`,
    );
  }
  console.log('\nC. propertiesWithHistory retrievable');
  console.log(`  ${yesNo(analysis.propertyHistoryRetrievable)}`);
  for (const propertyName of PROPERTY_NAMES) {
    console.log(
      `  ${propertyName}: ${analysis.requestedHistory[propertyName].length} version(s)`,
    );
  }
  console.log('\nD. Former Deal association available');
  console.log(`  ${yesNo(analysis.formerDealAssociationAvailable)}`);
  console.log(
    `  Deal IDs: ${analysis.formerDealIds.join(', ') || '(none returned)'}`,
  );
  console.log('\nE. Archived state clearly identified');
  console.log(`  ${yesNo(analysis.archivedStateClearlyIdentified)}`);
  console.log(
    `  archived: ${String(result.archivedRecordMetadata.archived)}`,
  );
  console.log(
    `  archivedAt: ${result.archivedRecordMetadata.archivedAt ?? '(not returned)'}`,
  );
  console.log(`\nArchitectural result: ${result.architecturalResult.model}`);
  console.log(`  ${result.architecturalResult.reason}`);
  console.log('\nMachine-readable JSON:');
  console.log(JSON.stringify(result, null, 2));
};

const main = async () => {
  const accessToken = requireEnvironmentVariable('HUBSPOT_ACCESS_TOKEN');
  const lineItemId = requireEnvironmentVariable('HUBSPOT_LINE_ITEM_ID');
  const objectPath =
    `/crm/objects/${API_VERSION}/${LINE_ITEM_OBJECT_TYPE}/` +
    encodeURIComponent(lineItemId);
  const archivedQuery = new URLSearchParams({
    archived: 'true',
    properties: PROPERTY_NAMES.join(','),
    propertiesWithHistory: PROPERTY_NAMES.join(','),
    associations: 'deals',
  });
  const endpoints = {
    normal: objectPath,
    archived: `${objectPath}?${archivedQuery.toString()}`,
  };

  const normalResponse = await requestJson(
    accessToken,
    endpoints.normal,
    'Normal line-item read',
  );
  const archivedResponse = await requestJson(
    accessToken,
    endpoints.archived,
    'Archived line-item read',
  );

  for (const [description, response] of [
    ['Normal line-item read', normalResponse],
    ['Archived line-item read', archivedResponse],
  ]) {
    if (response.ok && String(response.body?.id) !== lineItemId) {
      throw new ProbeError(
        `${description} returned unexpected ID ${String(response.body?.id)} ` +
          `instead of ${lineItemId}.`,
      );
    }
  }

  const analysis = analyzeArchivedResponse(archivedResponse);
  const archivedRecord = isRecord(archivedResponse.body)
    ? archivedResponse.body
    : {};
  const result = {
    apiBase: API_BASE,
    apiVersion: API_VERSION,
    readOnly: true,
    lineItemId,
    requests: {
      normal: {
        method: 'GET',
        endpoint: endpoints.normal,
        archivedParameterIncluded: false,
        responseStatus: normalResponse.status,
        retrievable: normalResponse.ok,
        error: normalResponse.error,
      },
      archived: {
        method: 'GET',
        endpoint: endpoints.archived,
        archived: true,
        properties: PROPERTY_NAMES,
        propertiesWithHistory: PROPERTY_NAMES,
        associations: ['deals'],
        responseStatus: archivedResponse.status,
        error: archivedResponse.error,
      },
    },
    archivedRecordMetadata: {
      id: archivedRecord.id ?? null,
      createdAt: archivedRecord.createdAt ?? null,
      updatedAt: archivedRecord.updatedAt ?? null,
      archived: archivedRecord.archived ?? null,
      archivedAt: archivedRecord.archivedAt ?? null,
      objectWriteTraceId: archivedRecord.objectWriteTraceId ?? null,
      url: archivedRecord.url ?? null,
    },
    analysis,
    architecturalResult: chooseModel(analysis),
    rawArchivedRecord: archivedResponse.ok ? archivedResponse.body : null,
  };

  printHumanSummary(result);
};

main().catch((error) => {
  if (error instanceof ProbeError) {
    console.error(`Deletion-recovery probe failed: ${error.message}`);
    process.exitCode = error.exitCode;
    return;
  }

  console.error(
    `Deletion-recovery probe failed with an unexpected error: ${error?.message ?? String(error)}`,
  );
  process.exitCode = 1;
});
