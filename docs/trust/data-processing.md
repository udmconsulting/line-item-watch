# Data processing inventory

## Purpose and maintenance

This inventory records what customer or operational data the product processes and why. Update it in the same task whenever a data category, purpose, persistence behavior, storage location, retention/deletion behavior, or subprocessor changes. Record actual implementation; target categories below do not assert current production storage.

## Inventory format

| Data category | Source | Purpose | Persisted? | Storage location | Retention | Deletion behavior | Relevant subprocessors | Status |
|---|---|---|---|---|---|---|---|---|
| Tenant/account identity | Customer/product onboarding | Internal ownership and isolation | Target Beta: expected | TBD | TBD | Remove/anonymize as applicable on tenant deletion | TBD | Required for Beta |
| External provider account identity | Provider/installation | Resolve Platform Connection and route provider traffic | Target Beta: expected | TBD | TBD | Remove with connection/tenant subject to required records | TBD | Required for Beta |
| OAuth credentials | Provider OAuth | Authorized provider access and refresh | Target Beta: expected | Encrypted credential store TBD | Active connection lifecycle; exact retention TBD | Revoke/remove on uninstall, disconnect, or tenant deletion | Hosting/secret vendors TBD | Required for Beta |
| Deal, Line Item, and external business identifiers | HubSpot | Correlation, provenance, audit reconstruction, UI context | Target Beta: expected as needed | TBD | TBD | Tenant-scoped deletion policy TBD | TBD | Required for Beta |
| Required commercial item state | HubSpot API | Establish baseline and reconstruct changes | Target Beta: expected, minimized | TBD | TBD | Delete according to tenant/uninstall policy | TBD | Required for Beta |
| Latest item snapshots | HubSpot API/event processing | MODEL B deletion audit and current reconstruction state | Target Beta: expected | TBD | TBD | Delete according to tenant/uninstall policy | TBD | Required for Beta |
| Change/audit metadata | HubSpot API/events and product processing | Explain what changed, when, and actor where available | Target Beta: expected | TBD | TBD | Audit deletion/export behavior TBD | TBD | Required for Beta |
| Provider user/change identifiers | HubSpot history, where available | Attribute changes when required for audit value | Target Beta: only when necessary | TBD | TBD | Delete according to policy and applicable commitments | TBD | Conditional |
| Operational metadata | Product services | Idempotency, correlation, job state, health, diagnosis | Target Beta: expected, minimized | TBD | Logs/job stores TBD | Delete/expire under operational retention policy | Monitoring/hosting vendors TBD | Required for Beta |

## Processing principles

- Collect and persist only what actual product behavior requires.
- Do not retain full provider payloads indefinitely by default.
- Avoid duplicating provider metadata when a Platform Connection reference already establishes provenance.
- Never include OAuth tokens or secrets in this inventory as values, logs, examples, or diagnostic payloads.
- Every customer-owned persisted record must have tenant ownership. Provider-backed records must also retain necessary connection provenance.
- Validate the register against implemented schemas, logs, backups, exports, and external vendors before Private Beta.

Exact fields, stores, regions, retention periods, deletion mechanics, and vendors remain TBD pending implementation and infrastructure selection.
