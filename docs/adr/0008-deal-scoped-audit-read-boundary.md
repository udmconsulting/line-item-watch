# ADR 0008: Deal-scoped audit read boundary

- Status: Accepted
- Date: 2026-09-28

## Context

The HubSpot Deal UI needs a bounded read model for locally retained Line Item Watch history. A Deal ID supplied by UI context is a useful selector, but it is not proof of Tenant ownership or equivalent to HubSpot user/record authorization. The read path must not require an OAuth token or a live HubSpot lookup.

The local projection can begin from a complete baseline or from sparse retained signals. Neither source proves that every provider change was captured, because reconciliation guarantees are not implemented.

## Decision

Expose one versioned, read-only endpoint:

```text
GET /api/v1/line-item-watch/deals/{dealId}/audit
```

The endpoint is disabled by default. When enabled, it validates HubSpot v3 signed-fetch requests against a configured canonical public HTTPS origin and numeric app ID. The signature input uses the configured origin, not `Host`, `Forwarded`, or `X-Forwarded-*`. The timestamp window is five minutes and the HMAC comparison is constant-time.

Only the signed HubSpot account and configured app boundary are trusted. The server resolves that account to one HubSpot Platform Connection and its Tenant, locks and rechecks that the connection is `ACTIVE`, and locks and rechecks the `LINE_ITEM_WATCH` entitlement. The Deal ID is then treated only as an untrusted selector inside that resolved `(tenant_id, connection_id)` scope. This is account/app authentication, not a claim of per-user or per-record HubSpot permission parity.

The response reads only local semantic audit and derived latest projection tables. It makes no provider call. Line Items remain relevant when they are currently associated, appeared in a complete historical checkpoint, or have retained post-boundary semantic Deal context. Disassociation and deletion therefore do not erase history.

Line Items and events are independent keyset-paged sections. Each has its own limit and opaque versioned cursor; a zero limit skips that section. Limits are capped at 20. Requested sections execute in one read-only `REPEATABLE_READ` transaction, but later cursor requests are new transactions and do not promise a cross-request snapshot.

Every Line Item includes explicit property states and:

```text
historyCoverage.mode = BASELINE_ANCHORED | SIGNAL_FIRST
historyCoverage.observedFrom = timestamp
historyCoverage.hasUnknownState = boolean
```

`BASELINE_ANCHORED` begins at the first complete baseline used by reconstruction. `SIGNAL_FIRST` begins at the earliest retained processed provider or semantic evidence used by reconstruction. Events earlier than `observedFrom` are not exposed. The boundary means only that Line Item Watch has evidence from that time; it does not mean the lifecycle is complete or that every provider change since then was captured. Neither mode is named or represented as `COMPLETE`.

API errors contain only a stable machine-readable code and correlation ID. The P.7 UI owns localized titles, descriptions, retry guidance, and presentation. P.6.B will generalize production supportability, localization foundations, and activity-audit concerns.

## Consequences

- Identical Deal IDs in different accounts cannot cross Tenant/connection scope.
- Unknown, inactive, reauthentication-required, and unentitled accounts do not reveal Deal existence.
- No OAuth credential, provider client, raw signal, processing row, database ID, deduplication key, or raw provider payload crosses the DTO boundary.
- Current membership and membership at deletion remain distinct; `UNKNOWN` is not `NOT_APPLICABLE`.
- `occurred_at` and `semantic_key` are copied into Deal context with a composite parent foreign key so chronological cursor positions cannot diverge from their semantic event.
- Edge rate limiting and generalized production activity auditing remain outside this slice.
