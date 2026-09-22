# Security overview

This is an engineering posture document, not marketing or a compliance claim.

## Current / proven

- The backend foundation persists only application-generated Tenant and Platform Connection IDs, provider/external account identity, Product Module entitlement, and creation/enabled timestamps in PostgreSQL.
- Tenant and entitlement foreign keys, provider/account uniqueness, tenant/module uniqueness, tenant-scoped connection lookup, and negative isolation cases are covered by PostgreSQL integration tests.
- No OAuth credentials, HubSpot business objects, webhook payloads, user data, billing data, public endpoints, or provider adapters are implemented.
- The feasibility probes require credentials through environment variables rather than committed literals.
- The accepted spike demonstrated read/history and webhook feasibility; it did not establish production security controls.
- Architectural boundaries are checked with ArchUnit, while broader Private Beta security and operational controls remain requirements rather than implemented claims.

No certification or regulatory compliance is claimed.

## Required for Private Beta

### Architecture and tenant isolation

Provider traffic must be authenticated, resolved through Platform Connection to internal Tenant, and processed with explicit tenant context. Customer records, jobs, and operational actions must be tenant-scoped, with storage constraints where appropriate and negative isolation tests.

### Credentials and encryption

OAuth access/refresh tokens and secrets must never be logged, committed, documented as values, or exposed to clients. Use least-privilege scopes, encryption at rest, managed key/secret mechanisms, lifecycle controls, revocation, and removal. Customer data and backups require appropriate encryption at rest.

### Transport and external input

All public production endpoints require HTTPS/TLS. Webhook signature/authenticity validation is mandatory. Payloads and provider values are untrusted and must be validated before processing.

### Logging, monitoring, and incidents

Structured logs and error monitoring should carry non-sensitive tenant, connection, event/job, correlation, operation, and outcome context. They must exclude secrets and unnecessary business payloads. Health monitoring and alerts must cover significant failures. Operational design must support detection, investigation, containment, recovery, and a customer-notification workflow where legally or contractually required.

### Operational access and recovery

Use least privilege, individual rather than shared production credentials, controlled administrative access, and auditable privileged access where appropriate. Backups require defined retention and tested restore procedures. Data minimization, deletion, log lifecycle, and vendor review apply across the system.

## TBD

- hosting/database provider and processing region;
- key and secret management implementation;
- logging, monitoring, and error-tracking vendors;
- production IAM and privileged-access mechanism;
- backup retention, restore cadence, RPO, and RTO;
- log and customer-data retention periods;
- incident contacts, severity model, runbooks, and notification workflow details; and
- independent security review or certification strategy, if any.

These choices are due before the affected production system or external Private Beta processing begins, with earlier decisions where implementation depends on them.
