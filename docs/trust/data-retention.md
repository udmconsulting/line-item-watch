# Data retention and deletion

## Principles

Retention must be purpose-limited, tenant-scoped, documented, and technically enforceable. Exact durations are **TBD** and must be approved before external Private Beta processing. A deleted or disconnected relationship must not leave usable credentials behind.

## Lifecycle requirements

### Active tenant data

Retain only the tenant, connection, commercial-item, snapshot, audit, and operational data required for active functionality and support. Periodically review whether each category remains necessary.

### Uninstall or provider disconnect

The implemented internal HubSpot uninstall service calls the provider uninstall API, then removes the local refresh credential and marks the connection `DISCONNECTED` only when the durable credential generation used by the operation is still current. Confirmed invalid/revoked current credentials are removed and marked `REAUTH_REQUIRED`. Every lifecycle mutation advances a generation that survives deletion, so a concurrent reinstall is preserved. Existing `LINE_ITEM_WATCH` checkpoints, latest projection, durable signals/processing state, and semantic audits remain attached to the retained Platform Connection after disconnect; webhook routing immediately ignores inactive connections. Already captured work can still be processed without provider access. Their eventual disconnect retention/deletion policy is TBD. A customer-facing disconnect workflow remains TBD.

### Tenant deletion

Deleting a Tenant row cascades to Platform Connections, encrypted credentials, entitlements, and all current `LINE_ITEM_WATCH` identities, snapshots, Deal-association rows, signals, processing state, semantic audits, source provenance, and Deal context; direct Platform Connection deletion follows the same ownership chain. Integration tests verify the constraints. No customer-facing deletion operation exists yet. The complete workflow must also handle logs where feasible and backups.

### Credentials

Refresh credentials are retained only while locally authorized. Replacement, invalidation, uninstall, and reinstall advance a durable monotonic generation; superseded provider refresh tokens are revoked with the authenticated HubSpot revocation form best-effort after commit. Successful uninstall, confirmed current-generation revocation, or Tenant deletion removes the credential. Access tokens are transient for one operation. Token values must not survive in logs, error systems, or object rendering.

### OAuth state

Install state expires after 10 minutes. Only its SHA-256 digest and correlation/timing metadata are persisted. Consumed or expired rows become eligible for deterministic bounded-batch deletion after the 24-hour replay-detection period when later state is issued.

### Audit history and snapshots

The implemented MODEL B projection retains immutable first-complete `BASELINE`, replaceable complete `OBSERVED`, sparse derived `LATEST`, and semantic audit history with source UUID provenance and Deal context. Deletion is auditable and freezes the final known state. These records are customer data. Audit history has an explicit product lifecycle: it is deleted with its owning Line Item/connection/Tenant, not as an incidental consequence of raw-signal deletion. Duration, export, and customer-directed deletion behavior are TBD.

### Application activity audit

Committed Platform Connection and entitlement transitions append a strict activity row in the same PostgreSQL transaction as the state change. Normal application code exposes insert only; no-op or losing concurrent transitions create no success row. This application-level append-only contract is not cryptographic tamper resistance or WORM storage. Rows cascade with their Tenant/connection, and future legal deletion or approved lifecycle policy may remove them. Exact retention, privileged access, export, and any sealing/archive requirements remain TBD. Opaque verified actor references may be pseudonymous personal data and require the same purpose limitation and access controls.

### Webhook change signals

Authenticated ingress retains normalized immutable signals, including exact monitored property text up to 65,535 characters. Keeping the event-time value is necessary because a later deletion can make provider reconstruction impossible. Raw payloads, headers, signatures, secrets/tokens, complete events, and provider delivery-attempt metadata are not retained. Deduplicated retries never update an existing row. Processing state follows a signal and is removed if that raw signal is later removed. Audit source rows deliberately store the signal UUID without a signal foreign key; therefore raw retention cannot destructively cascade into semantic history. No raw-signal retention job exists in P.5. Any future policy must process/reconcile eligible evidence first, preserve required audit provenance, and test its product/legal consequences. Exact signal duration, disconnect handling, and export policy remain TBD.

### Logs and operational records

Use the shortest useful retention consistent with diagnosis, security, and reliability. Correlation/operation IDs and internal references are operational metadata, not identity proof. Avoid customer payloads; control access and apply expiry. Exact durations and treatment of tenant identifiers during deletion are TBD.

### Backups

Backups need defined retention, access controls, encryption, restore testing, and expiry. Deleted data may persist only until backup rotation under the published policy; restoration procedures must prevent deleted data from silently returning to active use. RPO, RTO, and retention are TBD.

Document and test each lifecycle before making corresponding customer promises.
