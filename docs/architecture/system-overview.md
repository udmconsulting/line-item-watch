# System architecture overview

## Status legend

- **Exists today:** HubSpot project metadata, accepted feasibility probes, the Java/PostgreSQL Platform Core, and HubSpot OAuth installation/credential lifecycle under `backend/`.
- **Target Beta:** required architecture direction; not yet implemented.
- **TBD:** an implementation decision intentionally deferred until the relevant feature or infrastructure task.

## Target Beta logical architecture

```text
External providers
  HubSpot (first)                         [OAuth: implemented; business APIs/webhooks: Target Beta]
        |
        v
Provider adapters
  OAuth installation/refresh/uninstall   [implemented]
  business APIs and webhook mapping      [Target Beta]
        |
        v
Platform / application boundaries         [Target Beta]
  Tenant | Platform Connection | Product Module identity | Entitlements
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
  PostgreSQL (foundation implemented) | job mechanism TBD | monitoring vendors TBD
```

The arrows show dependency flow into application-owned contracts and business behavior. Domain/application code must not depend outward on HubSpot clients, webhook DTOs, HTTP, JPA/PostgreSQL implementations, hosting APIs, monitoring vendors, or billing-provider APIs.

## Runtime direction

The target is one codebase and a modular monolith with explicit internal ownership. The same artifact may support logically separate API and background-worker roles so asynchronous work is not forced into request threads and each role can scale independently later. Physical separation is not a Beta default.

PostgreSQL 18 is the implemented initial database, with Liquibase-managed schema and persistence adapters. The application now runs as an HTTP service for OAuth install/callback. Production endpoints require HTTPS. Managed infrastructure, backups, restore verification, managed secret/key storage, event/job technology, deployment topology, hosting provider, database provider, and region are TBD.

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

The single Spring Boot application under `backend/` implements Tenant identity, Platform Connections with `ACTIVE`, `REAUTH_REQUIRED`, and `DISCONNECTED` lifecycle states, Product Module identity, entitlements, encrypted connection credentials, OAuth state, HubSpot install/callback, on-demand refresh, and service-only uninstall. HubSpot code is isolated under `integrations.hubspot`; shared credential lifecycle remains in Platform Core.

No recurring HubSpot business API reads, access-token cache, refresh coalescing, webhook processing, Line Item Watch business behavior, job mechanism, public disconnect API, API/worker role split, production deployment, or production observability integration exists yet.
