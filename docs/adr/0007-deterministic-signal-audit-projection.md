# ADR 0007: Deterministic signal audit projection

- Status: Accepted
- Date: 2026-09-27

## Context

P.3 stored a first complete Line Item observation and a replaceable latest observation. P.4 added authenticated immutable signals, including creation, deletion, property changes, and both directional forms of HubSpot Line Item/Deal association events. Delivery can be duplicated, delayed, or out of order. A Line Item may be deleted before another provider read is possible, and a signal-first item may have no complete baseline.

Allowing observation and signal code to write latest state independently would create competing projections. Treating each provider association event as a business event would duplicate one relationship change. Ordering equal-time facts by a provider deduplication key would invent business history. Coupling semantic audit rows to raw evidence with destructive foreign keys would also make product history disappear under a future evidence-retention policy.

## Decision

### Checkpoints and single projection owner

- `BASELINE` is the immutable first complete provider observation.
- `OBSERVED` is the replaceable latest complete provider observation/checkpoint.
- `LATEST` is only a sparse derived projection.
- One module-owned repository locks the Tenant/connection/Line Item and reconstructs/writes LATEST and audit state. Both P.3 checkpoint persistence and P.5 signal processing invoke it; no other path writes LATEST.
- Reconstruction is a pure domain operation over checkpoints and immutable signals. P.5 processing has no provider client and makes no HubSpot call.

Each projected property is `UNKNOWN`, known `ABSENT`, or a known value. A checkpoint makes every monitored property known, including known absence. Signal-first history leaves untouched properties unknown, and LATEST persists only known properties. This distinguishes missing evidence from an observed empty/null value.

### Time and lifecycle semantics

Reconstruction sorts timestamps rather than delivery/processing order. Within one timestamp:

1. `CREATED` establishes the PRESENT lifecycle boundary.
2. Property and association facts apply.
3. `DELETED` emits the deletion transition and freezes the resulting final known state.

The stable provider deduplication key is only an iteration tie-breaker where order has no semantic effect. If different values for the same property share a timestamp and no reliable provider order exists, the property becomes `UNKNOWN`; no arbitrary winner is selected. The three mutually exclusive billing-start properties are resolved as one semantic group. Simultaneous value-bearing facts that do not identify a unique mode make the whole group `UNKNOWN` instead of selecting a winner by enum or iteration order. Property/lifecycle audit events use the union of pre-state, post-state, and explicit association targets for equal-time Deal context, so method order does not decide Deal discoverability. Later-arriving older evidence causes a full deterministic rebuild.

After deletion, later provider cleanup remains evidence but cannot change frozen properties or Deal context. A complete checkpoint is applied at its observation timestamp and anchors later signal reconstruction without rewriting earlier audit facts.

### Association transitions

Provider adapters map provider type IDs/direction to a provider-neutral Line Item/Deal pair plus `ADDED` or `REMOVED`. Business reconstruction does not know HubSpot association type IDs.

- `UNKNOWN` or `ABSENT` to `PRESENT` emits one `DEAL_ASSOCIATED` event.
- `PRESENT` to `PRESENT` emits no additional semantic event.
- `UNKNOWN` or `PRESENT` to `ABSENT` emits one `DEAL_DISASSOCIATED` event unless deletion freeze makes it evidence-only.
- `ABSENT` to `ABSENT` emits no additional semantic event.

A second directional event for an already-applied transition attaches source provenance to that transition even when its timestamp differs. A later opposite action is a new real transition. Contradictory equal-time actions are ambiguous and do not invent a transition.

### Processing, retries, and transactions

Signal capture inserts `PENDING` processing state transactionally. The opt-in scheduled worker is disabled by default. It claims due work with PostgreSQL `FOR UPDATE ... SKIP LOCKED`, a random claim token, and a bounded lease. Projection writes serialize on the Line Item identity. Projection/audit writes and `PROCESSED` completion share one transaction, so a crash cannot expose partial work.

Retryable failures use bounded exponential backoff. Deterministic failures or retry exhaustion become `FAILED` with a fixed sanitized code. A failed signal prevents later signals for that same Line Item from being claimed, avoiding a history that silently skips evidence; unrelated Line Items remain available. P.5 adds no public recovery API. Operational recovery requires diagnosis, correction, and an explicit controlled database action to return the failed row to retryable state. Replay remains idempotent through deterministic semantic keys and upserts.

### Audit retention

Semantic audit events, source links, and Deal context are module-owned product data. They cascade with their owning Line Item/connection/Tenant. A source link stores the source signal UUID as durable provenance but deliberately has no foreign key to the raw signal table. Consequently, deleting raw evidence may remove its processing state but cannot accidentally delete semantic audit history. P.5 adds no raw retention job; exact retention and customer-facing export/deletion periods remain TBD.

## Consequences

- Baseline-first and signal-first histories use the same deterministic model.
- Out-of-order arrival repairs history without provider access.
- P.3 and P.5 cannot race separate LATEST writers; their writes use the same row lock and transaction strategy.
- Association evidence remains complete without duplicating user-visible transitions.
- Terminal failures are visible and locally isolated, but recovery is an operator procedure until a later administration slice.
- Rebuilding one Line Item reads all retained processed evidence for that item; future compaction requires a separately designed checkpoint/retention policy.
- Micrometer exposes worker outcomes, duration, backlog, claims, failures, and oldest due age. Production dashboards, alerts, and monitoring vendors remain TBD.

## Alternatives considered

- **Let P.3 and P.5 update LATEST independently:** rejected because concurrency and replay could produce divergent state.
- **Treat every raw association signal as an audit event:** rejected because HubSpot can deliver two directional facts for one relationship transition.
- **Order equal-time conflicts by UUID or deduplication key:** rejected because deterministic storage order is not evidence of business order.
- **Skip terminal evidence and continue the same Line Item:** rejected because the resulting audit would silently omit a known failed fact.
- **Cascade audit sources from raw signals:** rejected because evidence retention and product audit history have different lifecycles.
- **Read HubSpot during signal processing:** rejected because it couples durable processing to credentials/provider availability and cannot solve post-deletion reads.
