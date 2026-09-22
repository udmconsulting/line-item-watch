# System architecture overview

## Status legend

- **Exists today:** HubSpot project metadata, accepted feasibility probes under `spike/`, and the Java/PostgreSQL Platform Core foundation under `backend/`.
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

PostgreSQL 18 is the implemented initial database, with Liquibase-managed schema and JPA confined to persistence adapters. Public endpoints require HTTPS. Managed infrastructure, backups, restore verification, secret/key management, event/job technology, deployment topology, hosting provider, database provider, and region are TBD.

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

The single Spring Boot application under `backend/` implements internal Tenant identity, Platform Connection identity and account resolution, closed Product Module identity, tenant/module entitlements, PostgreSQL persistence, and boundary tests. Packages are rooted at `com.udmconsulting`, with Platform Core capabilities under `platform.*` and JPA adapters under each capability's `infrastructure.persistence` package.

No HubSpot adapter, credential storage, webhook processing, Line Item Watch business behavior, public API, job mechanism, API/worker role split, production deployment, or production observability integration exists yet.
