# ADR 0010: Reliability reconciliation, replay, and lifecycle boundaries

- Status: Accepted, implemented, and live-accepted
- Date: 2026-10-01

## Context

Line Item Watch retains immutable normalized signals, complete provider
checkpoints, a derived latest projection, and semantic audit history. The
existing deterministic worker tolerates duplicate and out-of-order delivery,
but it cannot detect missed provider state, expose lifecycle observation gaps,
recover terminal signals without SQL, or bound evidence safely.

## Decision

- Reconciliation is a background reliability operation over already tracked
  Deals and Line Items. It may read HubSpot, but the Deal audit API and replay
  remain provider-free. Whole-account enumeration is excluded.
- Complete reconciliation observations update `OBSERVED` and invoke the one
  shared reconstruction writer. Drift never creates a synthetic historical
  semantic event; unknown transitions remain explicitly unknown.
- Provider I/O occurs outside transactions. A short commit revalidates active
  authorization, entitlement, Tenant ownership, and the credential generation
  used by the read.
- Observation periods record when eligible ingestion is active. Authorization
  or entitlement loss closes a period and marks a possible gap. Reactivation
  opens a new period; current-state reconciliation cannot erase that gap.
- Signed retained reads are allowed during `REAUTH_REQUIRED` when entitlement
  remains enabled and the response explicitly reports degraded coverage.
  `DISCONNECTED` and disabled-entitlement reads remain blocked.
- Replay uses only retained trusted evidence and immutable replay anchors. It is
  Tenant-scoped, restartable, Line Item-locked, deterministic, idempotent, and
  cannot mutate source evidence or processing status.
- Raw processed evidence may be removed only behind a verified immutable replay
  anchor and an explicit policy cutoff. Semantic audit history has an
  independent lifecycle and becomes retained product truth outside the
  reconstructable window.
- Terminal processing distinguishes permanent evidence failure from exhausted
  retry. A failed fact continues to block later facts for the same Line Item;
  operator requeue is compare-and-set and activity-audited.

## Consequences

Current provider state can be repaired without pretending to know a missed
transition. Old and new observation periods remain distinguishable. Raw signal
storage can be bounded without making current projection rebuild impossible,
while the API can state when retained history is policy-limited. The design adds
PostgreSQL reliability jobs/metadata and a minimal card warning, but no new
service, provider scope, public administration API, or production monitoring
platform.

P.9 remains responsible for HA, production scheduler topology/alert delivery,
backup/restore and DR, managed secret operations, production ingress and rate
controls, security hardening, and operational runbooks.

## Verification

Automated verification and controlled live acceptance passed. Live validation
covered migration `009` over retained data, tracked-scope no-drift
reconciliation without synthetic history, repeated provider-free idempotent
replay, a trusted verified replay anchor, the additive signed reliability read,
and bounded metrics/privacy behavior. Retention was previewed but intentionally
not executed, and no CRM or HubSpot mutation occurred.
