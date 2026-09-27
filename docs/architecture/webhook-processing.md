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

## Deferred processing and deployment

Signal consumption, authoritative history/object reads, snapshot/audit mutation, retry workers, reconciliation, terminal job state, and UI are later work. The backend does not force provider processing into the request transaction.

No permanent public HTTPS target has been selected. Therefore no deployable HubSpot webhook `*-hsmeta.json`, project deployment, genuine delivery, or live P.4 acceptance exists. Once a target is selected, runtime `HUBSPOT_WEBHOOK_PUBLIC_URI` and the project component `targetUrl` must be identical. The intended component subscribes to Line Item creation/deletion, the ten monitored Line Item properties, and Line Item↔Deal association changes; exact project metadata must be validated before a separately authorized upload/deployment.

## Deletion: MODEL B

The feasibility spike established that a deleted Line Item cannot be reliably read after deletion, including with `archived=true`. The durable deletion signal identifies the event, while the already retained immutable `BASELINE` and replaceable `LATEST` snapshot preserve the last known business state. Future processing will combine these; ingress itself does not reconstruct an audit event.

## Reconciliation

Reconciliation remains a future safety net for missed signals or processing drift, not the primary detector. Its schedule, scope, rate-limit strategy, and recovery behavior are TBD.
