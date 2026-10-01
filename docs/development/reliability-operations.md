# Reliability operator operations

## Safety boundary

The P.8 operator is an opt-in, one-shot application runner over Tenant-scoped
application services. Enable it only for a single invocation with
`LINE_ITEM_WATCH_OPERATOR_ENABLED=true`. It emits only counts, closed status or
error codes, and opaque UUID references. It does not print provider object IDs,
customer values, payloads, credentials, or exception messages.

All commands require `--tenant`, `--connection`, and an opaque
`--operator-ref`. Relevant commands also require the named UUID/provider
selector. Do not use the CLI to change live CRM data: replay and recovery are
local; reconciliation is the only command family that reads HubSpot.

## Commands

| Command | Additional options | Result |
|---|---|---|
| `reconcile-tenant` | none | Queues tracked-Deal keyset expansion |
| `reconcile-deal` | `--deal` | Queues one tracked Deal comparison |
| `reconcile-line-item` | `--line-item` | Queues current-state verification scoped through a tracked Deal |
| `replay-line-item` | `--line-item` | Queues provider-free retained-evidence replay |
| `rebuild-tenant` | none | Queues bounded per-item replay operations |
| `list-exhausted` | none | Returns a count of terminal signals |
| `requeue-signal` | `--signal` | Compare-and-set requeues one terminal signal |
| `create-anchor` | `--line-item` | Creates and verifies a trusted replay anchor |
| `inspect` | none | Returns operation, finding, gap, and exhausted counts |
| `acknowledge-finding` | `--finding` | Acknowledges one owned open finding |
| `acknowledge-gap` | none | Acknowledges a reconciled connection gap without erasing its historical boundary |
| `retention-preview` | one or more cutoff options | Returns eligible counts without deletion |
| `retention-execute` | cutoffs, `--confirm=EXECUTE`, `--batch-size` | Deletes one bounded eligible batch |

Cutoff options are RFC 3339 instants:
`--processed-signals-before`, `--semantic-events-before`,
`--activity-before`, and `--terminal-operations-before`. There are no default
cutoffs. Preview first, use independent approved policies, and repeat bounded
execution until counts reach zero if a policy calls for a full sweep. Never run
destructive retention against a shared development database.

Gap acknowledgement is accepted only after reconciliation reports
`SUCCEEDED` or `DRIFT_REPAIRED`. It records operator acknowledgement and leaves
`POSSIBLE_GAP` plus `possibleGapSince` intact; a later gap invalidates the prior
acknowledgement.

## Failure handling

Closed failures include `RECONCILIATION_UNAVAILABLE`,
`RECONCILIATION_CONFLICT`, `REPLAY_FAILED`,
`INSUFFICIENT_RETAINED_EVIDENCE`, `RETENTION_FAILED`, and
`INVALID_RECOVERY_OPERATION`. Inspect counts and application logs by opaque
correlation/operation reference; do not add customer values to logs. A failed
replay leaves the prior projection intact. A failed or interrupted retention
batch is safe to preview and retry because eligibility is re-evaluated from
persisted state.
