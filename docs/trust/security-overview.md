# Security overview

This is an engineering posture document, not marketing or a compliance claim.

## Current / proven

- The backend persists application-generated Tenant/connection identity, provider account identity, connection lifecycle, Product Module entitlement, OAuth state metadata, and encrypted HubSpot refresh credentials in PostgreSQL.
- Refresh credentials use AES-256-GCM with random nonces and authenticated provider/connection context. The 256-bit key and key ID are external mandatory configuration; plaintext refresh credentials exist only transiently during provider operations.
- OAuth state stores a SHA-256 digest rather than the browser value, expires after 10 minutes, is consumed atomically, and becomes eligible for bounded opportunistic deletion after a 24-hour replay-detection period.
- Access tokens, authorization codes, OAuth client secrets, encryption keys, raw token responses, and decrypted credentials are not persisted. Access tokens are neither cached nor shared between operations.
- Every issued access token is introspected before installation activation or provider use. The introspection response, rather than optional token-issuance metadata, supplies the authoritative HubSpot account identity and granted scopes; it is validated for active bearer access-token semantics and the configured OAuth client and is not persisted.
- A durable, monotonically increasing Platform Connection credential generation prevents stale invalid-grant, replacement, reinstall, scope-loss, or uninstall results from matching newer credentials, including across deletion/reinstall.
- Replacement refresh credentials are encrypted and committed through the generation guard before access-token introspection, so a transient introspection failure cannot discard the provider's latest refresh credential. Authoritative unusable-token transitions remain generation-conditional.
- Public install/callback endpoints return fixed non-reflective HTML with `no-store`, `no-cache`, `no-referrer`, `nosniff`, and restrictive CSP controls. Provider endpoints and non-local callbacks require HTTPS; local development uses an explicit localhost exception.
- Tenant/connection constraints, encrypted persistence, lifecycle transitions, concurrent installation, optimistic credential races, HTTP contracts, and negative isolation are covered by automated tests.
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
- production managed key/secret service and rotation procedure;
- logging, monitoring, and error-tracking vendors;
- production IAM and privileged-access mechanism;
- backup retention, restore cadence, RPO, and RTO;
- log and customer-data retention periods;
- incident contacts, severity model, runbooks, and notification workflow details; and
- independent security review or certification strategy, if any.

These choices are due before the affected production system or external Private Beta processing begins, with earlier decisions where implementation depends on them.
