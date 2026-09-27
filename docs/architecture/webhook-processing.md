# Webhook and event processing

## Implemented P.4 ingress

```text
HubSpot POST /integrations/hubspot/webhooks
  -> 8 MiB/media/encoding boundary
  -> v3 HMAC verification of exact raw bytes and configured canonical URI
  -> complete batch validation and provider-specific normalization
  -> portalId -> HubSpot Platform Connection -> Tenant
  -> active connection + LINE_ITEM_WATCH entitlement eligibility
  -> connection-group commit guard and immutable signal inserts
  -> database-authoritative deduplication
  -> empty 204 only after required durable commits
```

The receiver is conditional on `hubspot.webhook.enabled`. When enabled, `hubspot.webhook.public-uri` must be an absolute canonical HTTPS URI ending exactly in `/integrations/hubspot/webhooks`. Forwarding headers do not affect authentication. The body is read once with an 8 MiB limit that also applies to chunked requests. Only JSON (charset parameters allowed) and identity/no content encoding are accepted.

Authentication precedes parsing. HubSpot v3 verification uses the existing OAuth client secret, exact method/URI/body/timestamp input, strict Base64 and 32-byte digest validation, constant-time equality, and an absolute five-minute timestamp window. Responses have empty bodies: `204` for durable, duplicate, or deliberate ignore; `400` malformed authenticated input; `401` authentication failure; `405` other methods; `413` oversized input; `415` media/encoding rejection; `503` database/infrastructure failure; and `500` an internal invariant failure.

The adapter accepts arrays of 1–100 and normalizes only Line Item creation/deletion, exact monitored property changes, and Line Item↔Deal association changes. It preserves exact property text and ordering timestamps. It neither assumes delivery order nor invokes HubSpot. Unsupported but well-formed authenticated semantics are ignored.

## Durable change signals

`LINE_ITEM_WATCH` owns immutable `LineItemChangeSignal` rows with Tenant/connection provenance, provider event/subscription identity, external Line Item identity, occurrence/receipt timestamps, and signal-specific property or Deal-association details. No foreign key to tracked Line Item state exists, so creation can precede baseline identity and deletion can survive provider `404`.

Deduplication is a versioned SHA-256 over length-prefixed normalized immutable fields. It excludes `receivedAt` and `attemptNumber`; the unique `(tenant_id, connection_id, provider_deduplication_key)` constraint is authoritative. Raw requests, headers, signatures, complete provider events, OAuth material, and delivery-attempt metadata are not persisted.

All events are parsed before writes begin. Eligible events are grouped by connection, and each group gets its own short transaction. That transaction locks and revalidates the Tenant-scoped HubSpot connection and row-presence entitlement before inserts. Unknown, disconnected, reauthentication-required, or unentitled routes create no Tenant or business state. If a later connection group fails, ingress returns `503`; earlier commits remain safe because the retry deduplicates.

## Implemented P.5 processing

Each inserted signal receives a durable `PENDING` processing row in the capture transaction. The opt-in worker claims due rows through PostgreSQL `FOR UPDATE ... SKIP LOCKED`, records a random claim token and bounded lease, and allows unrelated Line Items to proceed concurrently. Projection writes serialize on the module's Tenant/connection/Line Item identity row.

The pure reconstructor consumes only complete `BASELINE`/`OBSERVED` checkpoints and immutable signals. It does not call HubSpot. The projection repository writes sparse `LATEST`, semantic audit events, all source-evidence links, Deal context, and `PROCESSED` state in one transaction. A rollback leaves no partial projection and an expired claim can be reclaimed.

Retryable failures use bounded exponential backoff. Exhaustion or deterministic invalid evidence becomes `FAILED` with a sanitized error code. A failed signal blocks only later work for that same Line Item; unrelated items remain claimable. P.5 intentionally exposes no customer/admin recovery API. Recovery requires an operator to diagnose and correct the underlying condition, then deliberately restore the durable processing row to retryable state using controlled database tooling; silently skipping the failed fact is forbidden.

Controlled P.4 genuine-delivery acceptance was completed, and its local PostgreSQL evidence is retained for provider-free P.5 acceptance. Production public target/deployment choices are environment-specific and remain outside committed repository configuration.

## Deletion: MODEL B

The feasibility spike established that a deleted Line Item cannot be reliably read after deletion, including with `archived=true`. The durable deletion signal produces an auditable deletion and freezes the reconstructed final known sparse state. At the same timestamp, `CREATED` establishes presence first, property/association facts apply second, and `DELETED` freezes last. Later cleanup signals remain source evidence and cannot erase the frozen property or Deal state.

`BASELINE` is immutable first complete provider evidence, `OBSERVED` is the replaceable complete provider checkpoint, and `LATEST` is derived only by the shared reconstruction writer used by both observation and signal processing. Equal-time conflicting values for one property have no invented provider order and project as explicitly `UNKNOWN`. Association audits are state transitions: repeated directional `ADDED` or `REMOVED` evidence attaches provenance without a duplicate user-visible transition, while a later opposite action creates a new transition. Provider association type IDs never enter this business rule.

## Reconciliation

Reconciliation remains a future safety net for missed signals or processing drift, not the primary detector. A future complete provider observation must update `OBSERVED` and invoke the same reconstruction path; it must not write `LATEST` directly. Its schedule, scope, rate-limit strategy, and recovery behavior are TBD.
