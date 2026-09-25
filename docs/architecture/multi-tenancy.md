# Multi-tenancy

## Identity model

The product is a multi-tenant SaaS. An internal **Tenant** owns customer data and lifecycle. A **Platform Connection** belongs to a Tenant and records an internal connection identity, provider, external provider account identity, and Tenant ownership. Provider identities do not define tenants.

A Tenant uses an application-generated UUID independent of provider identities. `platform_connection` uses its own UUID, references `tenant(id)` with `ON DELETE CASCADE`, and globally constrains `(provider, external_account_id)`. One Tenant may have multiple Platform Connections. Connections have `ACTIVE`, `REAUTH_REQUIRED`, or `DISCONNECTED` state; pre-OAuth rows migrate to `DISCONNECTED`.

## Isolation and provenance

All customer-owned data is tenant-scoped. Provider-backed data also retains enough connection provenance to disambiguate identical external IDs and establish its source.

Conceptually, reads and writes use:

```text
tenant_id + connection_id + external_object_id
```

They must not use `external_object_id` alone. Event idempotency and deduplication follow the same provider/connection scope. The schema enforces Tenant foreign keys, provider/account uniqueness, tenant/module entitlement uniqueness, and `(tenant_id, id)` uniqueness on Platform Connection. The `LINE_ITEM_WATCH` identity, snapshot, and association tables carry both Tenant and connection IDs through composite foreign keys, so PostgreSQL rejects cross-Tenant connection provenance. External Line Item identity is unique only within `(tenant_id, connection_id)`.

Tenant context must be explicit before business processing, including background jobs. It must not be accepted from an untrusted external identifier without resolving the Platform Connection. Authorization, caching, logging, metrics, exports, administration, and tests must preserve the same isolation boundary.

## Lifecycle boundary

Tenant ownership must make it possible to identify and delete all relevant customer data, including module state, connection data, credentials, jobs/events, and applicable audit data. Backup and log handling must follow documented lifecycle policies. Exact retention, deletion workflow, and backup expiry are TBD.

## Security expectations

- apply least privilege to users, services, operators, and database access;
- prevent cross-tenant reads and writes through application design and storage constraints where appropriate;
- test negative cross-tenant cases, not only successful access;
- include non-sensitive tenant and connection identifiers in operational context while excluding secrets and unnecessary business payloads; and
- audit privileged access where appropriate.

Tenant persistence and negative isolation tests exist. OAuth callback identity comes only from the validated provider exchange, while internal uninstall and baseline synchronization require both Tenant and connection identity. After provider I/O, baseline synchronization starts one short transaction, obtains PostgreSQL shared row locks through JPA on the Tenant-owned Platform Connection and row-presence entitlement, revalidates HubSpot/`ACTIVE`/`LINE_ITEM_WATCH`, and persists only while those locks prevent a concurrent lifecycle update or entitlement delete from committing. Database tests force the lifecycle and entitlement races and use PostgreSQL blocker-PID evidence to prove that baseline persistence is waiting on the winning mutation before it commits; they also cover cross-Tenant provenance rejection and identical external Line Item IDs under different connections. Customer-user authorization, tenant-aware administration, provider webhooks, jobs, caches, exports, and a public disconnect surface do not exist yet and remain Private Beta requirements.
