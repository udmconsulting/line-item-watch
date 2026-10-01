# Deal-scoped audit read API

## Endpoint and authentication

```text
GET /api/v1/line-item-watch/deals/{dealId}/audit
```

The endpoint exists only when `hubspot.ui-extension.enabled=true`. It accepts a HubSpot v3 signed fetch with exactly one signed `portalId`, `userId`, `userEmail`, and `appId`. Only the validated account/app boundary is trusted. The account resolves the internal Tenant and one `ACTIVE` HubSpot Platform Connection with an enabled `LINE_ITEM_WATCH` entitlement. `{dealId}` is a positive decimal provider ID and remains an untrusted selector within that resolved scope; it never resolves ownership and the API does not claim per-user or per-record HubSpot permission parity.

Serving this API makes zero HubSpot/provider calls. A valid, entitled account receives `200` with empty sections when no local history exists for the selected Deal.

## Query and pagination

| Parameter | Default | Range | Meaning |
|---|---:|---:|---|
| `lineItemsLimit` | 10 | 0–20 | Maximum Line Item summaries; `0` skips this query |
| `lineItemsCursor` | none | opaque | Position in Line Item ID ascending order |
| `eventsLimit` | 20 | 0–20 | Maximum audit events; `0` skips this query |
| `eventsCursor` | none | opaque | Position in event chronology |
| `lineItemSearch` | none | 2–100 Unicode code points | Literal, case-insensitive substring of the latest retained Line Item name |
| `eventType` | none | owned enum | Exact semantic event type |
| `field` | none | monitored API field | Exact changed field; implies `PROPERTY_CHANGED` |
| `lineItemId` | none | positive decimal ID | Exact Line Item event scope |
| `from` | none | RFC 3339 instant | Inclusive event boundary |
| `to` | none | RFC 3339 instant | Exclusive event boundary |

At least one limit must be greater than zero. Search trims only outer whitespace and treats `%`, `_`, and `\` literally; matching is accent-sensitive under the configured PostgreSQL collation. Event filters are allowlisted, conjunctive, and applied after the retained history boundary. A valid filter with no match returns an empty page.

Cursors are opaque, versioned, integrity-protected with a dedicated HMAC-SHA-256 key, section/Deal-specific, and bound to the canonical effective filters. New cursors are v2; v1 is accepted only for an unfiltered request during the compatibility window. A changed filter, Deal, section, malformed payload, or failed integrity check returns `INVALID_REQUEST`. Line Items use external Line Item ID ascending order. Events use `(occurredAt DESC, semanticKey DESC)`, so equal timestamps remain deterministic. No query uses `OFFSET`.

Both requested page domains run in one repeatable-read transaction. The endpoint is application-level read-only, but its PostgreSQL transaction is intentionally not marked read-only because authorization revalidation acquires shared row locks on the Platform Connection and entitlement. A later request using either cursor starts a new transaction; the API does not promise snapshot consistency across pagination requests while new history is arriving.

Each property/event value is truncated to at most 512 Unicode code points and reports `truncated=true` when shortened. Both sections are capped at 20 items, keeping the response bounded for a HubSpot UI-extension fetch.

## Representative response

```json
{
  "dealId": "1001",
  "lineItems": {
    "items": [
      {
        "lineItemId": "2002",
        "deleted": false,
        "deletedAt": null,
        "historicalRelevance": true,
        "dealMembership": {
          "currentMembership": "PRESENT",
          "membershipAtDeletion": null
        },
        "latest": {
          "name": {"state": "VALUE", "value": "Support", "truncated": false},
          "quantity": {"state": "VALUE", "value": "2", "truncated": false},
          "unitPrice": {"state": "UNKNOWN", "value": null, "truncated": false},
          "unitDiscount": {"state": "ABSENT", "value": null, "truncated": false},
          "discountPercentage": {"state": "ABSENT", "value": null, "truncated": false},
          "billingFrequency": {"state": "ABSENT", "value": null, "truncated": false},
          "billingStartDate": {"state": "ABSENT", "value": null, "truncated": false},
          "billingStartDelayDays": {"state": "ABSENT", "value": null, "truncated": false},
          "billingStartDelayMonths": {"state": "ABSENT", "value": null, "truncated": false},
          "billingPeriod": {"state": "ABSENT", "value": null, "truncated": false}
        },
        "historyCoverage": {
          "mode": "SIGNAL_FIRST",
          "observedFrom": "2026-09-28T10:00:00Z",
          "hasUnknownState": true
        }
      }
    ],
    "page": {"limit": 10, "hasMore": false, "nextCursor": null}
  },
  "events": {
    "items": [
      {
        "eventId": "evt_<opaque-semantic-identity>",
        "lineItemId": "2002",
        "latestRetainedLineItemName": {"state": "VALUE", "value": "Support", "truncated": false},
        "type": "PROPERTY_CHANGED",
        "occurredAt": "2026-09-28T10:05:00Z",
        "field": "quantity",
        "before": {"state": "UNKNOWN", "value": null, "truncated": false},
        "after": {"state": "VALUE", "value": "2", "truncated": false}
      }
    ],
    "page": {"limit": 20, "hasMore": false, "nextCursor": null}
  }
}
```

Every monitored latest property is present. `UNKNOWN` means the retained projection cannot determine the value. `ABSENT` means the property is known to have no value. `VALUE` carries normalized product text. No currency is inferred or returned.

`latestRetainedLineItemName` is additive identity context from the current retained projection, not the name at event time. A `name` property-change event's before/after values remain the authoritative historical name transition. The name is selected in the event query without a provider call or per-row lookup.

## Membership and history coverage

An existing Line Item has `currentMembership` of `PRESENT`, `ABSENT`, or `UNKNOWN`, and `membershipAtDeletion=null`. A deleted Line Item has `currentMembership=NOT_APPLICABLE` and preserves `membershipAtDeletion` as `PRESENT`, `ABSENT`, or `UNKNOWN`. `UNKNOWN` and `NOT_APPLICABLE` are never interchangeable. `historicalRelevance=true` means local evidence connects the Line Item to the requested Deal at or after its observation boundary, even if it was later disassociated or deleted.

`historyCoverage.mode=BASELINE_ANCHORED` means a complete provider baseline is the initial known state; `observedFrom` is that baseline observation time. `SIGNAL_FIRST` means reconstruction began from retained processed signals/semantic evidence without a matching complete baseline; `observedFrom` is the earliest retained evidence actually used. `hasUnknownState` is true when any latest monitored property or the relevant Deal membership is unresolved.

No event earlier than `observedFrom` is returned. The timestamp means “Line Item Watch has evidence from this observation boundary.” It does not mean every provider change since that timestamp was captured, it is not a reconciliation guarantee, and neither mode represents complete history.

## Errors

Errors are localization-neutral:

```json
{
  "error": {
    "code": "INVALID_REQUEST",
    "correlationId": "8bd0d958-d3db-4214-b235-99fbcf70a812"
  }
}
```

| HTTP status | Code | Boundary |
|---:|---|---|
| 400 | `INVALID_REQUEST` | Invalid Deal ID, limit, filter, duplicate/blank parameter, cursor integrity, or cursor/query mismatch |
| 401 | `AUTHENTICATION_FAILED` | Invalid signed request, metadata, app ID, or timestamp |
| 403 | `ACCOUNT_UNAVAILABLE` | Unknown/inactive/reauthentication-required/unentitled account, without Deal enumeration detail |
| 500 | `INTERNAL_ERROR` | Internal invariant or unexpected failure |
| 503 | `SERVICE_UNAVAILABLE` | Database access failure |

No response contains exception/provider/database detail or customer-facing English copy. The Deal App Card owns localized title, description, retry guidance, and presentation. P.6.B supplies the shared production-supportability, localization-contract, and activity-audit foundations.

The code is a localization key input, not localized text. The backend localization contract maps every public code to an `errors.*` key while the card owns the visible English and Hungarian message bundles. Every response also has a server-owned `X-Correlation-ID`; on errors it exactly equals the JSON value, and caller-supplied values cannot override it.

P.7 locale resolution uses HubSpot `user.language` for message selection, `user.locale` for date/number formatting, and `portal.timezone` for time-zone display. Locale tags are trimmed, `_` is replaced with `-`, and BCP 47 canonicalization is followed by exact supported-locale, base-language, then `en` fallback. Missing/invalid time zones use UTC. Dates, numbers, decimal separators, and pluralization are locale-aware; currency formatting requires an explicit valid currency code. `UNKNOWN`, `ABSENT`, and `NOT_APPLICABLE` remain distinct semantic states.

Successful and error responses use `Cache-Control: no-store` and `X-Content-Type-Options: nosniff`. The DTO excludes Tenant/connection/database IDs, processing rows, signal/source IDs, deduplication keys, OAuth data, raw payloads, and raw provider cleanup evidence. P.6.B remains localization-neutral; visible translation and presentation are implemented only in the card.
