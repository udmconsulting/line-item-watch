const API_BASE = 'https://api.hubapi.com';
const API_VERSION = '2026-09';
const LINE_ITEM_OBJECT_TYPE = 'line_items';

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
      `Missing required environment variable ${name}. See README.md for the manual run instructions.`,
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

const requestJson = async (
  accessToken,
  path,
  description,
  notFoundMessage,
) => {
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
      `${description} failed before HubSpot responded: ${error.message}`,
    );
  }

  const responseText = await response.text();
  let responseBody;

  if (responseText) {
    try {
      responseBody = JSON.parse(responseText);
    } catch {
      throw new ProbeError(
        `${description} returned HTTP ${response.status} with a non-JSON response.`,
      );
    }
  }

  if (!response.ok) {
    const detail = describeHubSpotError(responseBody);
    const suffix = detail ? ` HubSpot response: ${detail}` : '';

    if (response.status === 401) {
      throw new ProbeError(
        `${description} failed authentication (HTTP 401). Check HUBSPOT_ACCESS_TOKEN.${suffix}`,
        3,
      );
    }

    if (response.status === 403) {
      throw new ProbeError(
        `${description} was forbidden (HTTP 403). The app token is missing a required read scope or account permission.${suffix}`,
        4,
      );
    }

    if (response.status === 404 && notFoundMessage) {
      throw new ProbeError(`${notFoundMessage}${suffix}`, 5);
    }

    throw new ProbeError(
      `${description} failed with HTTP ${response.status}.${suffix}`,
    );
  }

  if (!responseBody || typeof responseBody !== 'object') {
    throw new ProbeError(`${description} returned an empty or invalid JSON body.`);
  }

  return responseBody;
};

const normalizeText = (value) =>
  String(value ?? '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, ' ')
    .trim();

const searchablePropertyText = (definition) =>
  normalizeText(`${definition.name ?? ''} ${definition.label ?? ''}`);

const summarizeDefinition = (definition) => ({
  name: definition.name,
  label: definition.label,
  type: definition.type,
  fieldType: definition.fieldType,
  groupName: definition.groupName,
  description: definition.description,
  calculated: definition.calculated,
});

const sortDefinitions = (definitions) =>
  [...definitions].sort((left, right) =>
    String(left.name).localeCompare(String(right.name)),
  );

const findMatches = (definitions, predicate) =>
  sortDefinitions(definitions.filter(predicate)).map(summarizeDefinition);

const findBestMatch = (definitions, scorer) => {
  const ranked = definitions
    .map((definition) => ({ definition, score: scorer(definition) }))
    .filter(({ score }) => score > 0)
    .sort(
      (left, right) =>
        right.score - left.score ||
        String(left.definition.name).localeCompare(
          String(right.definition.name),
        ),
    );

  return ranked.length > 0 ? summarizeDefinition(ranked[0].definition) : null;
};

const scoreExactProperty = (definition, internalName, label) => {
  const normalizedName = normalizeText(definition.name);
  const normalizedLabel = normalizeText(definition.label);

  if (normalizedName === normalizeText(internalName)) return 100;
  if (normalizedLabel === normalizeText(label)) return 90;
  return 0;
};

const discoverCommercialProperties = (definitions) => {
  const discountMatches = findMatches(definitions, (definition) =>
    searchablePropertyText(definition).includes('discount'),
  );
  const recurringPaymentMatches = findMatches(definitions, (definition) =>
    /\b(recurring|billing|payment|renewal)\b/.test(
      searchablePropertyText(definition),
    ),
  );
  const commercialMatches = findMatches(definitions, (definition) =>
    /\b(name|quantity|price|amount|discount|billing|recurring|payment|renewal|term)\b/.test(
      searchablePropertyText(definition),
    ),
  );

  const bestMatches = {
    name: findBestMatch(definitions, (definition) =>
      scoreExactProperty(definition, 'name', 'Name'),
    ),
    quantity: findBestMatch(definitions, (definition) =>
      scoreExactProperty(definition, 'quantity', 'Quantity'),
    ),
    unitPrice: findBestMatch(definitions, (definition) => {
      const text = searchablePropertyText(definition);
      const exactScore = scoreExactProperty(definition, 'price', 'Unit price');
      if (exactScore > 0) return exactScore;
      if (normalizeText(definition.label) === 'price') return 85;
      if (text.includes('unit price')) return 80;
      return 0;
    }),
    billingFrequency: findBestMatch(definitions, (definition) => {
      const text = searchablePropertyText(definition);
      if (normalizeText(definition.label) === 'billing frequency') return 100;
      if (text.includes('billing frequency')) return 90;
      if (text.includes('recurring billing period')) return 80;
      return 0;
    }),
    billingStartDate: findBestMatch(definitions, (definition) => {
      const text = searchablePropertyText(definition);
      if (normalizeText(definition.label) === 'billing start date') return 100;
      if (text.includes('billing start date')) return 90;
      if (text.includes('recurring billing start')) return 80;
      return 0;
    }),
    term: findBestMatch(definitions, (definition) => {
      const text = searchablePropertyText(definition);
      if (normalizeText(definition.label) === 'term') return 100;
      if (normalizeText(definition.name) === 'term') return 95;
      if (/\bterm\b/.test(text)) return 70;
      return 0;
    }),
  };

  const selectedPropertyNames = [
    ...Object.values(bestMatches)
      .filter(Boolean)
      .map(({ name }) => name),
    ...discountMatches.map(({ name }) => name),
    ...recurringPaymentMatches.map(({ name }) => name),
    ...commercialMatches.map(({ name }) => name),
  ].filter((name, index, names) => name && names.indexOf(name) === index);

  return {
    bestMatches,
    discountMatches,
    recurringPaymentMatches,
    commercialMatches,
    selectedPropertyNames,
  };
};

const validatePropertyDefinitions = (body) => {
  if (!Array.isArray(body.results)) {
    throw new ProbeError(
      'Line-item property discovery returned an unexpected response: results is not an array.',
    );
  }

  const invalidDefinition = body.results.find(
    (definition) =>
      !definition ||
      typeof definition !== 'object' ||
      typeof definition.name !== 'string',
  );

  if (invalidDefinition) {
    throw new ProbeError(
      'Line-item property discovery returned a property without an internal name.',
    );
  }

  return body.results;
};

const associationIds = (record, expectedObjectType) => {
  if (!record.associations || typeof record.associations !== 'object') {
    return [];
  }

  const expectedKey = normalizeText(expectedObjectType).replaceAll(' ', '');
  const entry = Object.entries(record.associations).find(([key]) => {
    const normalizedKey = normalizeText(key).replaceAll(' ', '');
    return normalizedKey === expectedKey;
  });

  if (!entry) return [];

  const results = entry[1]?.results;
  if (!Array.isArray(results)) {
    throw new ProbeError(
      `Association data for ${expectedObjectType} has an unexpected response shape.`,
    );
  }

  return results.map((association) => {
    if (!association || typeof association.id !== 'string') {
      throw new ProbeError(
        `Association data for ${expectedObjectType} contains an entry without an ID.`,
      );
    }
    return association.id;
  });
};

const propertyValue = (properties, definition) =>
  definition ? properties[definition.name] ?? null : null;

const printHumanSummary = (result) => {
  console.log('Line Item Watch — Gate 1 read-only probe');
  console.log(`API version: ${result.apiVersion}`);
  console.log(
    `Line-item property definitions discovered: ${result.propertyDiscovery.definitionCount}`,
  );

  for (const [target, definition] of Object.entries(
    result.propertyDiscovery.bestMatches,
  )) {
    console.log(
      `  ${target}: ${definition ? `${definition.name} (${definition.label})` : 'not found'}`,
    );
  }

  console.log(
    `  discount fields: ${result.propertyDiscovery.discountMatches.map(({ name }) => name).join(', ') || 'none found'}`,
  );
  console.log(
    `  recurring/payment fields: ${result.propertyDiscovery.recurringPaymentMatches.map(({ name }) => name).join(', ') || 'none found'}`,
  );
  console.log(
    `Deal ${result.deal.id}: ${result.deal.associatedLineItemIds.length} associated line item(s)`,
  );

  for (const lineItem of result.lineItems) {
    console.log(`Line item ${lineItem.id}`);
    console.log(`  name: ${lineItem.summary.name ?? '(no value)'}`);
    console.log(`  quantity: ${lineItem.summary.quantity ?? '(no value)'}`);
    console.log(`  unit price: ${lineItem.summary.unitPrice ?? '(no value)'}`);
    console.log(
      `  discounts: ${JSON.stringify(lineItem.summary.discounts)}`,
    );
    console.log(
      `  associated Deal IDs: ${lineItem.associatedDealIds.join(', ') || 'none returned'}`,
    );
  }

  console.log(
    `Gate 1 evidence: ${result.passCandidate ? 'COMPLETE for manual value review' : 'INCOMPLETE — inspect the JSON findings'}`,
  );
  console.log('\nMachine-readable JSON:');
  console.log(JSON.stringify(result, null, 2));
};

const main = async () => {
  const accessToken = requireEnvironmentVariable('HUBSPOT_ACCESS_TOKEN');
  const dealId = requireEnvironmentVariable('HUBSPOT_DEAL_ID');
  const endpoints = {
    propertyDefinitions: `/crm/${API_VERSION}/properties/${LINE_ITEM_OBJECT_TYPE}`,
    deal: `/crm/objects/${API_VERSION}/deals/${encodeURIComponent(dealId)}?associations=${LINE_ITEM_OBJECT_TYPE}`,
  };

  const propertyDefinitionsBody = await requestJson(
    accessToken,
    endpoints.propertyDefinitions,
    'Line-item property discovery',
    `The ${API_VERSION} line-item property-definition endpoint was not found. Legacy endpoint fallback is intentionally disabled.`,
  );
  const definitions = validatePropertyDefinitions(propertyDefinitionsBody);
  const discovery = discoverCommercialProperties(definitions);

  if (discovery.selectedPropertyNames.length === 0) {
    throw new ProbeError(
      'No commercial line-item properties could be selected from the returned definitions.',
    );
  }

  const deal = await requestJson(
    accessToken,
    endpoints.deal,
    `Deal ${dealId} read`,
    `Deal ${dealId} was not found with the ${API_VERSION} CRM Objects API.`,
  );

  if (String(deal.id) !== dealId) {
    throw new ProbeError(
      `Deal read returned unexpected ID ${String(deal.id)} instead of ${dealId}.`,
    );
  }

  const associatedLineItemIds = associationIds(deal, LINE_ITEM_OBJECT_TYPE);
  if (associatedLineItemIds.length === 0) {
    throw new ProbeError(`Deal ${dealId} has no associated line items.`, 6);
  }

  const propertiesQuery = encodeURIComponent(
    discovery.selectedPropertyNames.join(','),
  );
  const lineItems = [];

  for (const lineItemId of associatedLineItemIds) {
    const path = `/crm/objects/${API_VERSION}/${LINE_ITEM_OBJECT_TYPE}/${encodeURIComponent(lineItemId)}?properties=${propertiesQuery}&associations=deals`;
    const lineItem = await requestJson(
      accessToken,
      path,
      `Line item ${lineItemId} read`,
      `Associated line item ${lineItemId} was not found with the ${API_VERSION} CRM Objects API.`,
    );

    if (!lineItem.properties || typeof lineItem.properties !== 'object') {
      throw new ProbeError(
        `Line item ${lineItemId} returned an unexpected response without properties.`,
      );
    }

    const associatedDealIds = associationIds(lineItem, 'deals');
    const discounts = Object.fromEntries(
      discovery.discountMatches.map(({ name }) => [
        name,
        lineItem.properties[name] ?? null,
      ]),
    );

    lineItems.push({
      id: String(lineItem.id),
      createdAt: lineItem.createdAt,
      updatedAt: lineItem.updatedAt,
      archived: lineItem.archived,
      summary: {
        name: propertyValue(
          lineItem.properties,
          discovery.bestMatches.name,
        ),
        quantity: propertyValue(
          lineItem.properties,
          discovery.bestMatches.quantity,
        ),
        unitPrice: propertyValue(
          lineItem.properties,
          discovery.bestMatches.unitPrice,
        ),
        discounts,
      },
      properties: lineItem.properties,
      associatedDealIds,
      associations: lineItem.associations ?? {},
    });
  }

  const requiredDefinitionsFound = [
    discovery.bestMatches.name,
    discovery.bestMatches.quantity,
    discovery.bestMatches.unitPrice,
  ].every(Boolean);
  const discountDefinitionFound = discovery.discountMatches.length > 0;
  const everyLineItemLinksToDeal = lineItems.every((lineItem) =>
    lineItem.associatedDealIds.includes(dealId),
  );

  const result = {
    apiBase: API_BASE,
    apiVersion: API_VERSION,
    readOnly: true,
    endpoints: {
      propertyDefinitions: endpoints.propertyDefinitions,
      deal: endpoints.deal,
      lineItemTemplate: `/crm/objects/${API_VERSION}/${LINE_ITEM_OBJECT_TYPE}/{lineItemId}?properties={discoveredPropertyNames}&associations=deals`,
    },
    propertyDiscovery: {
      definitionCount: definitions.length,
      bestMatches: discovery.bestMatches,
      discountMatches: discovery.discountMatches,
      recurringPaymentMatches: discovery.recurringPaymentMatches,
      commercialMatches: discovery.commercialMatches,
      selectedPropertyNames: discovery.selectedPropertyNames,
    },
    deal: {
      id: String(deal.id),
      associatedLineItemIds,
      associations: deal.associations ?? {},
    },
    lineItems,
    passCandidate:
      requiredDefinitionsFound &&
      discountDefinitionFound &&
      everyLineItemLinksToDeal,
  };

  printHumanSummary(result);
};

main().catch((error) => {
  if (error instanceof ProbeError) {
    console.error(`Gate 1 probe failed: ${error.message}`);
    process.exitCode = error.exitCode;
    return;
  }

  console.error(
    `Gate 1 probe failed with an unexpected error: ${error?.message ?? String(error)}`,
  );
  process.exitCode = 1;
});
