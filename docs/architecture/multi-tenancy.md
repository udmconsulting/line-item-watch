# Multi-tenancy

## Identity model

The product is a multi-tenant SaaS. An internal **Tenant** owns customer data and lifecycle. A **Platform Connection** belongs to a Tenant and records an internal connection identity, provider, external provider account identity, and lifecycle/status. Provider identities do not define tenants.

A tenant may eventually have multiple providers or multiple accounts for one provider. Exact persistence schema is TBD.

## Isolation and provenance

All customer-owned data is tenant-scoped. Provider-backed data also retains enough connection provenance to disambiguate identical external IDs and establish its source.

Conceptually, reads and writes use:

```text
tenant_id + connection_id + external_object_id
```

They must not use `external_object_id` alone. Event idempotency and deduplication follow the same provider/connection scope. Storage constraints and indexes should enforce important invariants when the schema is designed.

Tenant context must be explicit before business processing, including background jobs. It must not be accepted from an untrusted external identifier without resolving the Platform Connection. Authorization, caching, logging, metrics, exports, administration, and tests must preserve the same isolation boundary.

## Lifecycle boundary

Tenant ownership must make it possible to identify and delete all relevant customer data, including module state, connection data, credentials, jobs/events, and applicable audit data. Backup and log handling must follow documented lifecycle policies. Exact retention, deletion workflow, and backup expiry are TBD.

## Security expectations

- apply least privilege to users, services, operators, and database access;
- prevent cross-tenant reads and writes through application design and storage constraints where appropriate;
- test negative cross-tenant cases, not only successful access;
- include non-sensitive tenant and connection identifiers in operational context while excluding secrets and unnecessary business payloads; and
- audit privileged access where appropriate.

No tenant persistence or authorization mechanism exists yet; these requirements are for Private Beta implementation.
