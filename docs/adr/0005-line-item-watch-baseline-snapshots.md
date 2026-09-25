# ADR 0005: Retain immutable baseline and replaceable latest Line Item snapshots

- Status: Accepted
- Date: 2026-09-25

## Context

The accepted HubSpot feasibility evidence established MODEL B for Line Item deletion: webhooks will later signal change, provider object/history APIs are authoritative where available, and a deleted Line Item cannot reliably be read again. The first `LINE_ITEM_WATCH` persistence slice therefore has to retain enough state before deletion without introducing event sourcing or speculative audit history.

Provider object IDs are scoped to a Platform Connection. A Line Item may be associated with more than one Deal, and future association changes must not be constrained to a single Deal foreign key. Provider HTTP calls must also remain outside database transactions.

## Decision

- An explicit application invocation synchronizes exactly one caller-supplied Deal. It validates the Tenant-scoped active HubSpot Platform Connection and `LINE_ITEM_WATCH` entitlement before the provider read. After normalization, the short persistence transaction locks and revalidates both rows before writing, preventing lifecycle updates or entitlement deletion from committing between authorization and snapshot commit.
- `LINE_ITEM_WATCH` owns a compact Line Item identity row keyed by `(tenant_id, connection_id, external_line_item_id)` and two snapshot kinds in one snapshot table: `BASELINE` and `LATEST`.
- `BASELINE` is the first fully normalized observation and is immutable. `LATEST` is inserted at the same time and may be replaced by an observation ordered after the stored provider update/observation tuple. No intermediate history is retained in P.3.
- Each snapshot owns a complete known set of Deal association IDs in a child table. There is no tracked-Deal table in this slice because only connection-scoped external Deal identity is required.
- The migration adds `(tenant_id, id)` uniqueness to `platform_connection`. Composite foreign keys carry Tenant and Platform Connection provenance through module identity, snapshots, and associations, preventing a record from pairing one Tenant with another Tenant's connection.
- The HubSpot adapter uses the date-versioned `2026-09` CRM object and association APIs and the existing `HubSpotAccessTokenProvider`. It requests only selected direct Line Item properties and maps provider DTOs into module-owned values before persistence.
- Billing start is normalized from exactly one of `hs_recurring_billing_start_date`, `hs_billing_start_delay_days`, or `hs_billing_start_delay_months`. The provider-calculated `hs_billing_start_delay_type` is neither authoritative nor persisted.
- Currency is omitted. The feasibility evidence and current public specification available during implementation did not establish an exact Line Item property name with the required semantics; `hs_line_item_currency_code` is not assumed or silently replaced.
- A provider read either normalizes completely or persists nothing. Association pages must be complete and error-free; HTTP 207 or partial/error-bearing batch envelopes fail rather than being salvaged. All Line Items observed for the Deal are then guarded and stored in one short database transaction. Absence from a Deal read does not infer deletion or remove earlier state.

## Consequences

- If a future deletion signal identifies a Line Item that HubSpot then returns as `404`, the module still has its Tenant, Platform Connection, provider ID, immutable baseline, latest descriptive/commercial values, and Deal association sets.
- Repeated and concurrent baseline invocations cannot create duplicate identities or snapshot kinds. A later observation cannot be overwritten by an older one.
- Explicit null/absent selected properties are represented as nullable normalized fields; the complete provider payload is not retained.
- The model supports later association and deletion handling, but P.3 does not detect deletions, interpret absence as removal, create audit events, or retain every version.
- Currency-aware semantics require new verified provider evidence and an explicit model/migration change.

## Alternatives rejected for P.3

- **Event sourcing or append-only snapshot history:** unnecessary before audit reconstruction is implemented.
- **One mutable snapshot:** insufficient because deletion recovery must preserve the initial baseline independently of later state.
- **One Deal ID column on Line Item:** would encode an unsupported one-Deal-only invariant.
- **Persist raw HubSpot JSON or calculated billing-start type:** unnecessary, less stable, and contrary to data minimization and the verified authority boundary.
- **Whole-account scan, polling, scheduler, or webhook ingestion:** outside the explicitly bounded one-Deal baseline use case.
