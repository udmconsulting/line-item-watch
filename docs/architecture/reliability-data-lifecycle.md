# Reliability and data lifecycle

## Reliability state

Migration `009` adds one reliability state per Tenant/HubSpot connection,
observation periods, per-Line-Item reliability, leased reliability operations,
typed reconciliation findings, and replay anchors. Ingestion is either
`OBSERVING` or `PAUSED`; coverage is either `NO_KNOWN_GAP` or `POSSIBLE_GAP`.
Authorization or entitlement loss closes the active observation period and
starts or preserves a gap. Reactivation opens a period but never erases the
historical gap.

The provider-free Deal audit read is available for entitled `ACTIVE` and
`REAUTH_REQUIRED` connections. A reauthentication-required read reports the
paused/degraded state. `DISCONNECTED` and unentitled reads are rejected.

## Reconciliation

Scheduled reconciliation creates bounded Tenant operations only for active,
entitled connections with already-tracked Deal state. Tenant expansion uses a
Deal keyset and never discovers the account. Manual Tenant, Deal, and Line Item
requests use the same operation model. Claims use PostgreSQL `SKIP LOCKED`, a
claim token, an expiry lease, bounded attempts, and exponential backoff.
HubSpot `Retry-After` is honored for rate limits up to the configured maximum
backoff.

Provider reads occur before the transactional commit. The observation carries
the credential generation used by the read. Commit locks and revalidates the
operation claim, active connection and generation, enabled entitlement, and
Line Item identity. A complete observation updates `OBSERVED` and invokes the
shared deterministic reconstructor; it never writes `LATEST` directly and does
not invent semantic historical events.

Findings contain only a closed type and internal references: property drift,
association drift, provider absence/inaccessibility, locally unknown provider
object, or provider timestamp/state conflict. A Deal-scoped absence proves an
association difference, not Line Item deletion. A provider 404 never creates a
deletion timestamp. Current-state success updates reconciliation confidence but
does not clear a historical coverage gap.

## Replay and recovery

Replay is provider-free. Each Line Item transaction locks its identity, loads
the latest verified trusted anchor when present, applies only later processed
signals, and atomically replaces derived `LATEST` and reconstructable semantic
projection rows. It never modifies normalized signals or their processing
status. Repetition is deterministic and idempotent. If neither a checkpoint,
trusted anchor, nor retained processed signal can reconstruct the item, replay
fails with `INSUFFICIENT_RETAINED_EVIDENCE`; the transaction rolls back and the
previous derived projection remains intact.

Signal processing records terminal `PERMANENT` or `RETRY_EXHAUSTED`
dispositions. A terminal signal continues to block later facts for that Line
Item. Operator requeue is a Tenant-scoped compare-and-set from terminal
`FAILED` to `PENDING`, resets the bounded retry state, keeps immutable evidence,
and records one atomic activity-audit transition. Repeating the same action
while it remains pending is a no-op.

## Anchors and retention

A replay anchor captures canonical properties, lifecycle/deletion state,
complete association state, original coverage boundary, and the latest
processed evidence watermark under the Line Item lock. Creation rejects paused,
gapped, sparse, incomplete, or inaccessible state. Canonical values and Deal
membership are rechecked before the transaction exposes the anchor as trusted
and verified.

Retention has no implicit duration and cannot run with empty cutoffs. Preview
and execution are Tenant-scoped. Execution requires an explicit confirmation
and bounded batch size. Processed signals are eligible only at or before a
verified trusted anchor watermark; pending, claimed, failed, and newer evidence
are not eligible. Semantic events, activity audit, and terminal operation
metadata each require their own cutoff. Open findings protect their operation.
Deleting processed signals preserves semantic events and advances
`retainedFrom` only for affected Line Items.

Production scheduling topology, alert thresholds/delivery, backup/restore,
disaster recovery, and production retention periods remain P.9 work.
