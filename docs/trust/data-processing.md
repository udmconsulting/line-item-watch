# Data processing inventory

## Purpose and maintenance

This inventory records what customer or operational data the product processes and why. Update it in the same task whenever a data category, purpose, persistence behavior, storage location, retention/deletion behavior, or subprocessor changes. Record actual implementation; target categories below do not assert current production storage.

## Inventory format

| Data category | Source | Purpose | Persisted? | Storage location | Retention | Deletion behavior | Relevant subprocessors | Status |
|---|---|---|---|---|---|---|---|---|
| Tenant/account identity | Product foundation; future onboarding | Internal ownership and isolation | Yes: opaque Tenant UUID and creation timestamp only | PostgreSQL `tenant` | TBD | Foundation connection and entitlement rows cascade on Tenant-row deletion; complete workflow TBD | No production subprocessor selected | Implemented foundation |
| External provider account identity | HubSpot OAuth installation | Resolve Platform Connection and route provider traffic | Yes: connection UUID, Tenant UUID, provider, external account ID, lifecycle status/timestamps | PostgreSQL `platform_connection` | TBD | Cascades with Tenant deletion; uninstall retains identity but marks it disconnected | No production infrastructure subprocessor selected | Implemented |
| Product Module entitlement | Product activation | Determine whether a module is enabled for a Tenant | Yes: Tenant UUID, module value, enabled timestamp | PostgreSQL `tenant_entitlement` | While enabled; full lifecycle policy TBD | Disable removes the row; cascades with Tenant-row deletion | No production subprocessor selected | Implemented foundation |
| OAuth install state | Product-generated OAuth initiation | CSRF protection and replay detection | Yes: SHA-256 state digest, correlation UUID, created/expiry/consumed timestamps; raw state is not stored | PostgreSQL `oauth_install_state` | Active for 10 minutes; eligible for bounded opportunistic deletion after the 24-hour replay window | Deleted in bounded batches during later install starts; contains no token or provider payload | Hosting/database vendors TBD | Implemented |
| OAuth refresh credentials and scopes | HubSpot OAuth | Authorized on-demand provider access | Yes: AES-256-GCM ciphertext, nonce, cipher/key metadata, granted scopes, durable monotonic credential generation, timestamps; access tokens are not persisted | PostgreSQL `platform_connection` and `connection_credential` | While the connection remains authorized; exact inactive identity retention TBD | Credential row removed after successful uninstall, confirmed current-generation revocation, or Tenant cascade; superseded provider token revoked best-effort | Hosting/secret vendors TBD | Implemented for controlled acceptance; managed production key service TBD |
| Deal, Line Item, and external business identifiers | HubSpot | Correlation, provenance, audit reconstruction, UI context | Target Beta: expected as needed | TBD | TBD | Tenant-scoped deletion policy TBD | TBD | Required for Beta |
| Required commercial item state | HubSpot API | Establish baseline and reconstruct changes | Target Beta: expected, minimized | TBD | TBD | Delete according to tenant/uninstall policy | TBD | Required for Beta |
| Latest item snapshots | HubSpot API/event processing | MODEL B deletion audit and current reconstruction state | Target Beta: expected | TBD | TBD | Delete according to tenant/uninstall policy | TBD | Required for Beta |
| Change/audit metadata | HubSpot API/events and product processing | Explain what changed, when, and actor where available | Target Beta: expected | TBD | TBD | Audit deletion/export behavior TBD | TBD | Required for Beta |
| Provider user/change identifiers | HubSpot history, where available | Attribute changes when required for audit value | Target Beta: only when necessary | TBD | TBD | Delete according to policy and applicable commitments | TBD | Conditional |
| Operational metadata | Product services | OAuth correlation, lifecycle diagnosis, future job health | OAuth correlation/state timestamps and sanitized structured logs implemented; job metadata deferred | PostgreSQL and application logs | OAuth replay rows: 24-hour cleanup window; log retention TBD | State cleanup implemented opportunistically; log lifecycle TBD | Monitoring/hosting vendors TBD | Partially implemented |

## Processing principles

- Collect and persist only what actual product behavior requires.
- Do not retain full provider payloads indefinitely by default.
- Avoid duplicating provider metadata when a Platform Connection reference already establishes provenance.
- Never include OAuth tokens or secrets in this inventory as values, logs, examples, or diagnostic payloads.
- Every customer-owned persisted record must have tenant ownership. Provider-backed records must also retain necessary connection provenance.
- Validate the register against implemented schemas, logs, backups, exports, and external vendors before Private Beta.

Exact fields, stores, regions, retention periods, deletion mechanics, and vendors remain TBD pending implementation and infrastructure selection.
