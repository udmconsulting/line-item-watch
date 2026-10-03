# System architecture overview

## Status legend

- **Exists today:** HubSpot app project metadata, a Deal-only Line Item Watch App Card, accepted feasibility probes, the Java/PostgreSQL Platform Core, HubSpot OAuth installation/credential lifecycle, the one-Deal `LINE_ITEM_WATCH` checkpoint slice, authenticated durable HubSpot Line Item webhook capture, provider-free signal/audit projection and replay, tracked-scope reconciliation, reliability/observation state, replay anchors and policy-gated retention mechanics, a disabled-by-default signed Deal audit read API, shared supportability, and Platform activity auditing under `backend/`.
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
  reconciliation/replay/recovery jobs     [implemented; opt-in]
  security | configuration | supportability | activity audit
        |
        v
Product Modules
  LINE_ITEM_WATCH checkpoints/signals      [implemented]
  reliability/reconciliation/replay        [implemented]
  audit reconstruction/deletion handling  [implemented]
  Deal-scoped audit query/read model       [implemented]
  Deal App Card                             [implemented; profile-configured]
        |
        v
Module-owned checkpoint/audit persistence  [implemented]
        |
        v
Infrastructure adapters
  PostgreSQL (foundation + checkpoints + signals + jobs + audits implemented)
  Cloud SQL/Cloud Run/Cloud Monitoring adapters and Terraform [staging foundation partial/recovery required; runtime and production not provisioned]
```

The arrows show dependency flow into application-owned contracts and business behavior. Domain/application code must not depend outward on HubSpot clients, webhook DTOs, HTTP, JPA/PostgreSQL implementations, hosting APIs, monitoring vendors, or billing-provider APIs.

## Runtime direction

The target is one codebase and a modular monolith with explicit internal ownership. The same artifact may support logically separate API and background-worker roles so asynchronous work is not forced into request threads and each role can scale independently later. Physical separation is not a Beta default.

PostgreSQL 18 is the implemented database, with Liquibase-managed schema, persistence adapters, durable processing queues, and Platform-owned application activity audit. One OCI artifact has explicit local, service, migration, and operator roles. The approved production service combines HTTP and both workers; migration and operator roles are isolated one-shot jobs. Shared supportability supplies server-owned request correlation, worker operation identity, stable error categories, privacy-aware structured logs, safe unexpected-failure diagnostics, and bounded vendor-neutral metrics.

GCP `europe-west1` is the selected deployment foundation: separate Terraform-managed staging/production projects, Cloud Run, Cloud SQL PostgreSQL 18, Secret Manager, Cloud Monitoring, WIF delivery, and a production-only global HTTPS load balancer for `api.lineitemwatch.com`. Staging uses `run.app`. The organization, bootstrap project, billing link/budget alert, and protected GCS state bucket exist; staging S1 is partial with no database/runtime, and production is not provisioned. Production begins zonal and max one, with min zero while install-ready without active customers and min one for active processing; Regional HA, Cloud Armor, and a physical API/worker split require measured need.

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

The single Spring Boot application under `backend/` implements Tenant identity, Platform Connections with `ACTIVE`, `REAUTH_REQUIRED`, and `DISCONNECTED` lifecycle states, Product Module identity, entitlements, encrypted connection credentials, OAuth state, HubSpot install/callback, on-demand refresh, service-only uninstall, one explicitly invoked Deal observation synchronization, a conditional authenticated HubSpot webhook receiver, independently conditional signal and reliability workers, shared supportability, and committed connection/entitlement/operator activity auditing. HubSpot code is isolated under `integrations.hubspot`; shared credential lifecycle and activity audit remain in Platform Core. `modules.lineitemwatch` owns normalized complete checkpoints, immutable signals, pure reconstruction, semantic audits, reliability/observation state, tracked-scope reconciliation, provider-free replay/recovery, replay anchors, policy-gated retention, and atomic PostgreSQL projection writes. Technical logs, Platform activity rows, and Product Module semantic audit events are distinct records.

`BASELINE` is the immutable first complete provider observation. `OBSERVED` is the replaceable latest complete provider checkpoint. `LATEST` is a sparse derived projection and has one writer: the shared reconstruction repository used after P.3 observation, P.5 signal processing, P.8 reconciliation, and provider-free replay. Signal processing, replay, and Deal audit reads perform no provider reads. The audit endpoint resolves only a cryptographically authenticated provider account into Tenant/connection scope; its Deal ID remains an untrusted selector within that scope. The Deal App Card uses that endpoint through signed `hubspot.fetch` only and performs no CRM write. P.8 reconciliation is limited to already tracked Deals and their current provider associations; no account-wide scan exists. No public baseline/recovery endpoint, access-token cache, refresh coalescing, public disconnect API, or physical API/worker split exists. Controlled P.4 genuine-delivery acceptance is complete; P.9 now represents target production configuration without claiming a live deployment.
