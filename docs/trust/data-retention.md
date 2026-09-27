# Data retention and deletion

## Principles

Retention must be purpose-limited, tenant-scoped, documented, and technically enforceable. Exact durations are **TBD** and must be approved before external Private Beta processing. A deleted or disconnected relationship must not leave usable credentials behind.

## Lifecycle requirements

### Active tenant data

Retain only the tenant, connection, commercial-item, snapshot, audit, and operational data required for active functionality and support. Periodically review whether each category remains necessary.

### Uninstall or provider disconnect

The implemented internal HubSpot uninstall service calls the provider uninstall API, then removes the local refresh credential and marks the connection `DISCONNECTED` only when the durable credential generation used by the operation is still current. Confirmed invalid/revoked current credentials are removed and marked `REAUTH_REQUIRED`. Every lifecycle mutation advances a generation that survives deletion, so a concurrent reinstall is preserved. Existing `LINE_ITEM_WATCH` baseline/latest records and durable change signals remain attached to the retained Platform Connection after disconnect; webhook routing immediately ignores inactive connections. Their eventual disconnect retention/deletion policy is TBD. A customer-facing disconnect workflow remains TBD.

### Tenant deletion

Deleting a Tenant row cascades to Platform Connections, encrypted credentials, entitlements, and all current `LINE_ITEM_WATCH` identities, snapshots, Deal-association rows, and change signals; direct Platform Connection deletion also cascades its signals. Integration tests verify the ownership constraints. No customer-facing deletion operation exists yet. The complete workflow must identify future audit data, events/jobs, logs where feasible, and backups.

### Credentials

Refresh credentials are retained only while locally authorized. Replacement, invalidation, uninstall, and reinstall advance a durable monotonic generation; superseded provider refresh tokens are revoked with the authenticated HubSpot revocation form best-effort after commit. Successful uninstall, confirmed current-generation revocation, or Tenant deletion removes the credential. Access tokens are transient for one operation. Token values must not survive in logs, error systems, or object rendering.

### OAuth state

Install state expires after 10 minutes. Only its SHA-256 digest and correlation/timing metadata are persisted. Consumed or expired rows become eligible for deterministic bounded-batch deletion after the 24-hour replay-detection period when later state is issued.

### Audit history and snapshots

The implemented MODEL B foundation retains one immutable `BASELINE` and one replaceable `LATEST` snapshot plus a complete known Deal-association set for each. It does not retain intermediate history, delete records because an object is absent from a scoped Deal read, or yet record provider deletion. These records are customer data. Their retention must balance stated product value, data minimization, customer deletion promises, and any valid contractual/legal requirement. Duration and export/deletion behavior are TBD.

### Webhook change signals

Authenticated ingress retains normalized immutable signals, including exact monitored property text up to 65,535 characters. Keeping the event-time value is necessary because a later deletion can make provider reconstruction impossible. Raw payloads, headers, signatures, secrets/tokens, complete events, and delivery-attempt metadata are not retained. Deduplicated retries never update an existing row. Signal duration, disconnect handling, export, asynchronous processing, and eventual compaction/deletion policy are TBD before external Beta processing.

### Logs and operational records

Use the shortest useful retention consistent with diagnosis, security, and reliability. Avoid customer payloads; control access and apply expiry. Exact durations and treatment of tenant identifiers during deletion are TBD.

### Backups

Backups need defined retention, access controls, encryption, restore testing, and expiry. Deleted data may persist only until backup rotation under the published policy; restoration procedures must prevent deleted data from silently returning to active use. RPO, RTO, and retention are TBD.

Document and test each lifecycle before making corresponding customer promises.
