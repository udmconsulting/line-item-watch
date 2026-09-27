# ADR 0006: Authenticate HubSpot push webhooks and capture durable change signals before acknowledgement

- Status: Accepted
- Date: 2026-09-27

## Context

The accepted feasibility evidence established HubSpot project webhooks as the primary Line Item change detector and MODEL B for deletion: a deleted Line Item cannot reliably be read after the event. P.3 retained baseline/latest state, but no production receiver or durable indication of later change existed.

Webhook delivery is external, duplicated, delayed, and potentially out of order. Payload fields are untrusted, `eventId` is not assumed globally unique, and one batch can contain events for multiple HubSpot accounts and internal Tenants. A public acknowledgement must not claim success before required durable state exists. At the same time, ingress must not perform slow provider reads or reconstruct business audit history inside the request.

HubSpot v3 authentication signs the HTTP method, exact public request URI, raw request bytes, and unchanged request timestamp with the application client secret. No permanent public HTTPS target has yet been selected, so deployable HubSpot project webhook metadata and genuine delivery acceptance cannot be completed with an honest `targetUrl`.

## Decision

- Provide one conditional `POST /integrations/hubspot/webhooks` endpoint. When enabled, runtime configuration requires the exact absolute canonical HTTPS public URI, with strict path and URI-component validation. The signed URI is never derived from `Host`, `Forwarded`, or `X-Forwarded-*`.
- Read the body once and enforce media, content-encoding, 8 MiB body, 1–100 event batch, identifier, property, and association bounds before persistence. Authenticate before JSON parsing.
- Verify only HubSpot v3 using HMAC-SHA256 with the existing current OAuth client secret, strict Base64/32-byte digest checks, constant-time equality, and a five-minute absolute timestamp window. Raw body, signature, and secret are never logged or persisted.
- Fully normalize the authenticated batch before writes. Capture only Line Item creation/deletion, the ten baseline properties, and Line Item↔Deal association add/remove in either orientation. Preserve exact property text, including empty strings. Other authenticated semantics are deliberately ignored.
- Resolve `portalId` through `PlatformConnection(provider=HUBSPOT, externalAccountId)` to the internal Tenant. Unknown, disconnected, reauthentication-required, and unentitled routes are ignored and never provision state.
- Let `LINE_ITEM_WATCH` own an immutable `LineItemChangeSignal`, independent of HubSpot DTOs and existing tracked Line Item identity. It stores explicit Tenant/connection provenance, provider event/subscription identity, external Line Item identity, occurrence/receipt time, and signal-specific property or Deal-association data.
- Compute a versioned SHA-256 deduplication key with length-prefixed UTF-8 normalized immutable fields. Include exact semantic property/association values; exclude receipt time and delivery attempt. PostgreSQL uniqueness within Tenant/connection is authoritative, and duplicate inserts do not update existing rows.
- Group normalized supported events by Platform Connection. Persist each group in a separate short transaction that locks and revalidates the Tenant-scoped HubSpot connection and `LINE_ITEM_WATCH` entitlement before inserts. No provider HTTP occurs in that transaction or elsewhere in ingress.
- Return an empty `204` only after every required group commits. A later group database/infrastructure failure returns `503`; previously committed groups safely deduplicate on provider retry. Fixed empty responses separate authentication, input, size/media, transient infrastructure, and internal failures.
- Capture only durable signals in P.4. Do not mutate baseline/latest snapshots, create audit records, infer old/new values, or run asynchronous processing/reconciliation from ingress.
- Defer the real HubSpot project webhook component, deployment, genuine delivery, and live acceptance until a concrete public HTTPS target is selected. Runtime `HUBSPOT_WEBHOOK_PUBLIC_URI` and the future component `targetUrl` must be identical, metadata validation must precede separately authorized upload/deployment, and no fake, localhost, placeholder, or temporary tunnel URL may be committed.

## Consequences

- Provider acknowledgement means the required normalized signals are durable or already present, while unsupported/ineligible authenticated events are intentionally acknowledged without persistence.
- A creation signal can precede tracked identity, and a deletion signal remains capturable after provider `404`. Exact event-time property values are retained to support later deletion-safe reconstruction without retaining whole payloads.
- One failing Tenant/connection transaction does not roll back another Tenant's committed rows. Redelivery is safe but may repeat routing and commit-guard work.
- Ingress requires no OAuth token and has no provider network dependency. Signal processing latency and provider rate limits are moved out of the public request path, but the worker/job model and reconciliation policy remain future decisions.
- Sharing the current OAuth client secret avoids duplicate secret storage. Rotation can invalidate verification of in-flight requests signed by the former secret because no dual-secret grace behavior is assumed or implemented; operational recovery/reconciliation is later work.
- Stored external IDs and exact property values are customer data. Tenant/connection cascades exist, while duration, disconnect retention, export, and production backup/log policies remain TBD before external Beta use.
- Backend implementation is locally testable with a synthetic canonical HTTPS URI. P.4 live acceptance remains **DEFERRED — PUBLIC HTTPS TARGET REQUIRED** without blocking this code slice.

## Alternatives rejected

- **Acknowledge before persistence or keep only an in-memory queue:** loses deletion-relevant signals on failure.
- **Use `eventId` alone or application pre-checks for deduplication:** assumes unsupported global uniqueness and is race-prone.
- **Persist raw webhook requests:** increases sensitive/customer data retention without a P.4 requirement.
- **Read HubSpot synchronously or mutate snapshots/audit records in ingress:** couples acknowledgement to provider availability and prematurely defines processing semantics.
- **One transaction for the entire multi-Tenant batch:** creates unnecessary cross-Tenant coupling and lock scope.
- **Trust request forwarding headers for the signed URI:** permits deployment/proxy ambiguity at an authentication boundary.
- **Commit placeholder or temporary project metadata:** creates misleading or unsafe deployable state before a real target exists.
