# P.8 reliability and data lifecycle plan

## Status and phase boundary

Implemented — automated verification and live acceptance passed, from `main`
at `e188edc3a95d5c3494b64faff58b128d93dde34f`.
P.8 is one `LINE_ITEM_WATCH` phase covering data correctness, recoverability,
reconciliation, replay, terminal-signal recovery, lifecycle coverage metadata,
and policy-driven retention mechanics. P.9 retains production deployment, HA,
ingress, managed-secret operations, dashboards and alerts, backup/restore, DR,
security hardening, and production runbooks.

P.8 preserves provider-aware integrations, the provider-free Deal audit read
path, immutable evidence, deterministic reconstruction, MODEL B deletion,
Tenant isolation, PostgreSQL worker claims, bounded public errors, and Platform
activity audit.

## Baseline at plan approval

- `BASELINE` is the immutable first complete observation, `OBSERVED` is the
  replaceable complete observation, and `LATEST` plus semantic audit history
  are derived by the shared Line Item-locked reconstructor.
- Webhook ingress stores normalized immutable signal facts, not raw requests or
  complete provider payloads. Capture and `PENDING` processing state commit
  together.
- The worker uses `SKIP LOCKED`, claim tokens, bounded leases, eight attempts,
  exponential backoff, and atomic projection/completion writes. A terminal
  `FAILED` signal blocks only its Line Item, but normal recovery requires SQL.
- Rebuild is provider-free and deterministic while its checkpoints/signals are
  retained. Semantic history is not re-derivable after required raw evidence is
  removed unless a trusted replay boundary exists.
- Disconnect/reauthentication and entitlement loss stop new capture. Existing
  module records remain and captured work can still be processed provider-free.
- There is no reconciliation scheduler, supported operator recovery interface,
  replay job, replay anchor, or raw-signal retention job.

## Approved behavior

### Observation and read lifecycle

Reliability state distinguishes `OBSERVING` from `PAUSED` ingestion and
`NO_KNOWN_GAP` from `POSSIBLE_GAP` coverage. It records possible-gap start,
last observed signal, last successful processing, last reconciliation, and a
closed reconciliation outcome. Observation periods close on authorization or
entitlement loss and reopen on valid reactivation; a later reconciliation can
prove current state but never erase a historical gap.

The signed Deal audit read remains provider-free. `ACTIVE` and
`REAUTH_REQUIRED` connections may read retained data when entitlement remains
enabled; `REAUTH_REQUIRED` must expose degraded coverage. `DISCONNECTED` and
disabled entitlement remain blocked. Existing `BASELINE_ANCHORED`,
`SIGNAL_FIRST`, `observedFrom`, and `hasUnknownState` remain, with an additive
`retainedFrom` and `retentionLimited` boundary.

### Reconciliation

Reconciliation is scheduled or manually triggered for retained Deals and Line
Items only; it never enumerates the whole HubSpot account. It uses the existing
read scopes and date-versioned object/association APIs. Provider calls happen
outside database transactions and carry a credential-generation fence into a
short commit transaction that revalidates connection, entitlement, and object
ownership.

A complete observation updates `OBSERVED` and invokes the shared reconstructor;
it never writes `LATEST` directly. Typed findings record property drift,
association drift, provider absence/inaccessibility, a locally unknown provider
object, or provider timestamp/state conflict without copying customer values.
Reconciliation emits no synthetic semantic event. A provider `404` does not
invent a deletion time.

### Replay and terminal-signal recovery

Replay is distinct from reconciliation: it performs no provider call and uses
only retained checkpoints, trusted immutable replay anchors, and processed
signals. Work is Tenant-scoped, restartable, keyset-driven, and bounded to one
Line Item transaction under the existing object lock. It is deterministic and
idempotent and does not alter evidence or processing state. Insufficient
evidence fails before replacing the trusted projection.

Terminal processing records `PERMANENT` or `RETRY_EXHAUSTED`. Compare-and-set
requeue requires terminal state, preserves the signal, resets bounded attempts,
does not skip ordering, and appends Platform activity audit.

### Replay anchors and retention

An immutable replay anchor contains canonical state, lifecycle and association
state, the original evidence boundary, and an evidence-through watermark. It is
derived and persisted under the Line Item lock and must be verified and trusted
before cleanup can depend on it. A known-degraded projection cannot silently
become a trusted anchor.

Retention has independent lifecycle classes for processed normalized signals,
semantic audit history, application activity audit, and terminal reliability
operation metadata. Cleanup is disabled without explicit policy cutoffs. It
uses preview, explicit confirmation, Tenant predicates, keyset scans, and
bounded transactions. It cannot remove pending/claimed/failed signals,
evidence beyond a verified anchor, unresolved findings, active operations, or
semantic history without its own policy.

## Implementation sequence

1. Migration `009`, reliability/period persistence, lifecycle hooks, activity
   actions, read semantics, constraints, and clean-install/upgrade tests.
2. Tracked-scope reconciliation, provider adapter, leases, generation guards,
   findings, retries, and metrics.
3. Replay/rebuild, terminal-signal requeue, operator services/CLI, and crash/
   concurrency coverage.
4. Replay anchors, retention preview/execution, and destructive isolated tests.
5. Additive Deal audit reliability contract, compact EN/HU warning, full
   acceptance, documentation, and final security/tenant review.

## Acceptance

Automated PostgreSQL/Testcontainers coverage owns destructive, race, migration,
large-history, crash, replay, anchor, retention, and Tenant-isolation cases.
Frontend tests own additive-contract compatibility, truthful degraded wording,
localization, and P.7.B regression coverage.

Live acceptance is separately authorized and read-only against approved
existing developer-test data. It may verify a no-drift reconciliation and local
provider-free replay. It must not manufacture CRM history, force lifecycle
transitions, deploy assets, or execute destructive retention.

Automated verification passed, including migration clean-install/upgrade,
PostgreSQL concurrency and Tenant isolation, deterministic replay/recovery,
anchor-gated retention, API compatibility, metrics/privacy, and localized UI
coverage. Controlled live acceptance also passed against retained developer-test
data: migration `009` upgraded the existing database, tracked-scope no-drift
reconciliation preserved historical truth, repeated provider-free replay was
idempotent, a trusted verified replay anchor was created, and the signed
reliability API plus bounded metrics/privacy surfaces were verified. Retention
remained preview-only by design; no CRM or HubSpot mutation was performed.
