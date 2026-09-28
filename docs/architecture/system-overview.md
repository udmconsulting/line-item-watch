# System architecture overview

## Status legend

- **Exists today:** HubSpot app project metadata, accepted feasibility probes, the Java/PostgreSQL Platform Core, HubSpot OAuth installation/credential lifecycle, the one-Deal `LINE_ITEM_WATCH` checkpoint slice, authenticated durable HubSpot Line Item webhook capture, provider-free signal/audit projection, and a disabled-by-default signed Deal audit read API under `backend/`.
- **Target Beta:** required architecture direction; not yet implemented.
- **TBD:** an implementation decision intentionally deferred until the relevant feature or infrastructure task.

## Target Beta logical architecture

```text
External providers
  HubSpot (first)                         [OAuth + focused reads + backend webhook receiver: implemented]
        |
        v
Provider adapters
  OAuth installation/refresh/uninstall   [implemented]
  Deal/Line Item baseline reads           [implemented]
  webhook authentication/mapping          [implemented; controlled live acceptance complete]
  history reads                            [Target Beta]
        |
        v
Platform / application boundaries
  Tenant | Platform Connection | Product Module identity | Entitlements
  explicit baseline use case              [implemented]
  authenticated durable signal ingress    [implemented]
  signal workers/durable processing jobs  [implemented; opt-in]
  security | configuration | observability
        |
        v
Product Modules
  LINE_ITEM_WATCH checkpoints/signals      [implemented]
  audit reconstruction/deletion handling  [implemented]
  Deal-scoped audit query/read model       [implemented]
        |
        v
Module-owned checkpoint/audit persistence  [implemented]
        |
        v
Infrastructure adapters
  PostgreSQL (foundation + checkpoints + signals + jobs + audits implemented) | monitoring vendors TBD
```

The arrows show dependency flow into application-owned contracts and business behavior. Domain/application code must not depend outward on HubSpot clients, webhook DTOs, HTTP, JPA/PostgreSQL implementations, hosting APIs, monitoring vendors, or billing-provider APIs.

## Runtime direction

The target is one codebase and a modular monolith with explicit internal ownership. The same artifact may support logically separate API and background-worker roles so asynchronous work is not forced into request threads and each role can scale independently later. Physical separation is not a Beta default.

PostgreSQL 18 is the implemented initial database, with Liquibase-managed schema, persistence adapters, and the durable processing queue. The application runs as an HTTP service and may also run its scheduled worker in the same process when explicitly enabled. Production endpoints require HTTPS. Managed infrastructure, backups, restore verification, managed secret/key storage, deployment topology, hosting provider, database provider, and region are TBD.

## Core request/event context

```text
provider request or event
  -> validate authenticity and input
  -> identify external provider account
  -> resolve Platform Connection
  -> resolve Tenant
  -> establish explicit tenant context
  -> persist/route/process business work
```

Customer ownership is never inferred from an external business-object ID. Provider-backed records retain tenant ownership, connection provenance, and external identity as appropriate.

## Current implementation boundary

The single Spring Boot application under `backend/` implements Tenant identity, Platform Connections with `ACTIVE`, `REAUTH_REQUIRED`, and `DISCONNECTED` lifecycle states, Product Module identity, entitlements, encrypted connection credentials, OAuth state, HubSpot install/callback, on-demand refresh, service-only uninstall, one explicitly invoked Deal observation synchronization, a conditional authenticated HubSpot webhook receiver, and an independently conditional signal worker. HubSpot code is isolated under `integrations.hubspot`; shared credential lifecycle remains in Platform Core. `modules.lineitemwatch` owns normalized complete checkpoints, immutable signals, pure reconstruction, semantic audits, and atomic PostgreSQL projection writes.

`BASELINE` is the immutable first complete provider observation. `OBSERVED` is the replaceable latest complete provider checkpoint. `LATEST` is a sparse derived projection and has one writer: the shared reconstruction repository used after both P.3 observation and P.5 signal processing. Signal processing and Deal audit reads perform no provider reads. The audit endpoint resolves only a cryptographically authenticated provider account into Tenant/connection scope; its Deal ID remains an untrusted selector within that scope. No public baseline/recovery endpoint, account-wide scan, recurring reconciliation, access-token cache, refresh coalescing, public disconnect API, Line Item Watch UI, physical API/worker split, production deployment, or production observability vendor integration exists yet. Controlled P.4 genuine-delivery acceptance is complete; production target details are intentionally not represented in repository configuration.
