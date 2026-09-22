# System architecture overview

## Status legend

- **Exists today:** HubSpot project metadata and the accepted feasibility probes under `spike/`.
- **Target Beta:** required architecture direction; not yet implemented.
- **TBD:** an implementation decision intentionally deferred until the relevant feature or infrastructure task.

## Target Beta logical architecture

```text
External providers
  HubSpot (first)                         [provider implementation: Target Beta]
        |
        v
Provider adapters                         [Target Beta]
  authentication, API and webhook mapping
        |
        v
Platform / application boundaries         [Target Beta]
  Tenant | Platform Connection | Entitlements
  authenticated event ingress | durable jobs
  security | configuration | observability
        |
        v
Product Modules                           [Target Beta]
  LINE_ITEM_WATCH (only defined module)
        |
        v
Module-owned persistence ports            [Target Beta]
        |
        v
Infrastructure adapters                   [Target Beta]
  PostgreSQL | job mechanism TBD | monitoring vendors TBD
```

The arrows show dependency flow into application-owned contracts and business behavior. Domain/application code must not depend outward on HubSpot clients, webhook DTOs, HTTP, JPA/PostgreSQL implementations, hosting APIs, monitoring vendors, or billing-provider APIs.

## Runtime direction

The target is one codebase and a modular monolith with explicit internal ownership. The same artifact may support logically separate API and background-worker roles so asynchronous work is not forced into request threads and each role can scale independently later. Physical separation is not a Beta default.

PostgreSQL is the expected initial database. Public endpoints require HTTPS. Managed infrastructure, backups, restore verification, secret/key management, event/job technology, deployment topology, hosting provider, database provider, and region are TBD.

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

No production tenant, connection, entitlement, webhook-processing, module, persistence, API/worker, security, or observability implementation exists yet. Exact packages, ports, schemas, and runtime mechanisms are deliberately not designed in P.0.
