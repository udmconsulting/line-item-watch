# ADR 0004: Persist encrypted refresh credentials with optimistic lifecycle guards

- Status: Accepted
- Date: 2026-09-23

## Context

HubSpot installation must establish an internal Tenant and Platform Connection, retain authorization for later provider operations, and support reconnect and uninstall. OAuth codes and access tokens are short-lived, while refresh credentials are sensitive long-lived authorization material. Concurrent reconnects, refresh rotation, and uninstall can produce stale provider responses that must not invalidate newer authorization.

P.2 has no recurring business API traffic, so access-token caching, refresh locks, and single-flight coordination would add machinery without a current workload requirement.

## Decision

- Persist only AES-256-GCM-encrypted refresh credentials, granted scopes, encryption metadata, and the credential's generation. A durable monotonic generation on Platform Connection survives credential deletion and is authoritative for optimistic lifecycle changes. Keys remain external configuration; access tokens, codes, client secrets, keys, and raw responses are never persisted.
- Store only a SHA-256 digest of 256-bit OAuth state. State expires after 10 minutes, is consumed atomically, and becomes eligible for bounded opportunistic deletion after the 24-hour replay-detection period.
- Exchange/refresh/uninstall calls use HubSpot's date-versioned `2026-09` endpoints outside database transactions.
- Obtain an access token on demand for one operation and discard it afterward. Do not cache access tokens, coalesce refreshes, lock refresh operations, or retry automatically in this slice.
- Persist replacement refresh tokens by comparing the loaded generation, advancing the durable connection generation, and assigning that new generation to the credential in one short transaction.
- Delete credentials or change connection state after a provider response only when both the durable connection generation and credential generation used by that operation are still current. Every successful lifecycle mutation advances the durable generation, including invalidation and uninstall, so deletion/reinstall cannot reuse an earlier value.
- Serialize first install/reconnect finalization by HubSpot account with a PostgreSQL advisory transaction lock, while database uniqueness remains authoritative.
- Expose public install/callback endpoints. Keep uninstall as an internal Tenant-scoped application service until an authenticated administration surface exists.

## Consequences

- Reconnect, refresh rotation, invalid-grant handling, and uninstall cannot destroy a newer credential, including after an intervening credential deletion and reinstall.
- Provider calls do not hold database transactions open, and local transactions stay short.
- Each operation pays for a refresh until recurring provider reads justify caching/coalescing.
- Production still needs a managed key/secret service, key rotation procedure, HTTPS deployment, operational alerts, and a customer-facing disconnect experience.

## Alternatives rejected for P.2

- **Persist access tokens:** rejected because they are unnecessary durable secret material.
- **In-memory access-token cache or single-flight refresh:** deferred until recurring HubSpot reads create an evidenced need.
- **Refresh locks or automatic retry loops:** rejected for this slice; optimistic generation conflicts are surfaced explicitly.
- **Unconditional invalidation after `invalid_grant`:** rejected because a stale response could delete a credential created by reconnect or refresh rotation.
