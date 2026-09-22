# Data retention and deletion

## Principles

Retention must be purpose-limited, tenant-scoped, documented, and technically enforceable. Exact durations are **TBD** and must be approved before external Private Beta processing. A deleted or disconnected relationship must not leave usable credentials behind.

## Lifecycle requirements

### Active tenant data

Retain only the tenant, connection, commercial-item, snapshot, audit, and operational data required for active functionality and support. Periodically review whether each category remains necessary.

### Uninstall or provider disconnect

Stop provider processing, invalidate or revoke credentials where supported, remove locally held active credentials, and define whether customer data enters a short recovery window or is deleted immediately. Exact behavior and customer notice are TBD.

### Tenant deletion

The system must identify all tenant-owned data across modules, Platform Connections, credentials, events/jobs, logs where feasible, and backups. The deletion workflow, verification evidence, exceptions required by law/contract, and completion timeline are TBD.

### Credentials

Retain only during an active authorized connection or a narrowly defined recovery need. Revoke/remove on uninstall, disconnect, tenant deletion, or confirmed compromise. Token values must not survive in logs or error systems.

### Audit history and snapshots

Commercial audit history and latest snapshots are customer data. Their retention must balance stated product value, data minimization, customer deletion promises, and any valid contractual/legal requirement. Duration and export/deletion behavior are TBD.

### Logs and operational records

Use the shortest useful retention consistent with diagnosis, security, and reliability. Avoid customer payloads; control access and apply expiry. Exact durations and treatment of tenant identifiers during deletion are TBD.

### Backups

Backups need defined retention, access controls, encryption, restore testing, and expiry. Deleted data may persist only until backup rotation under the published policy; restoration procedures must prevent deleted data from silently returning to active use. RPO, RTO, and retention are TBD.

Document and test each lifecycle before making corresponding customer promises.
