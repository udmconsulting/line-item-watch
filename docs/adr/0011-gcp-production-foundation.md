# ADR 0011: GCP production foundation

- Status: accepted
- Date: 2026-10-01

## Context

Line Item Watch needs a stable customer-facing production origin, durable PostgreSQL, independently authorized release migrations, monitoring, and a repeatable staging path. The application already uses a portable Spring Boot modular monolith, PostgreSQL 18, Liquibase, OCI packaging, durable database work queues, and provider-neutral Micrometer metrics.

The first production environment must be credible for an external customer without paying for speculative high availability or a distributed runtime topology.

## Decision

Use separate GCP projects in `europe-west1` for staging and production. Run one OCI image on Cloud Run in explicit `SERVICE`, `MIGRATE`, or `OPERATOR` roles and connect to Cloud SQL for PostgreSQL 18 through the Cloud SQL Java Connector. Store secret payloads outside Terraform and inject explicitly pinned Secret Manager versions.

The environment roots create and own their projects beneath the existing organization, attach the existing billing account, apply environment labels, enable APIs, and create all environment resources. The shared organization, billing account, bootstrap project, state bucket, and billing-level budget remain manual prerequisites outside both state graphs. Project deletion is normally blocked by both Terraform lifecycle and the Google provider deletion policy.

Production starts with one 1 vCPU/1 GiB Cloud Run service, concurrency 20, maximum one, and a dedicated-core, single-zone Cloud SQL Enterprise instance. The minimum is zero while production is install-ready with no active customers and one before active customers require continuous processing. Its public contract is `https://api.lineitemwatch.com` through a global external Application Load Balancer, Google-managed certificate, and serverless NEG. Direct Cloud Run ingress is restricted to the load balancer. Cloud Armor and Regional HA are deferred until measured risk or load justifies them.

Staging uses a stable provider-reported `run.app` URL, min instances zero, max one, and a shared-core single-zone database. It has no load balancer or custom domain and is explicitly unparked for acceptance.

GitHub Actions authenticates with Workload Identity Federation. Staging builds once, records the immutable digest, migrates, deploys, and smokes it. Production is a separate manually dispatched, protected-environment workflow which copies and deploys the exact staging-accepted digest without rebuilding.

## Consequences

- Production has a stable external contract and avoids migration races between service revisions.
- Single-zone Cloud SQL accepts a documented availability risk; backups and PITR reduce data-loss risk but are not failover.
- One combined service preserves the existing database lease/idempotency model and permits later multi-instance scaling.
- GCP integration remains in configuration, deployment, and infrastructure adapters. Domain and application logic remain portable.
- The organization, bootstrap project, billing link, billing budget alert, and protected versioned GCS state bucket were manually created on 2026-10-02. They remain external bootstrap dependencies and are not managed by either environment root.
- Staging/production plan/apply, secret values, DNS, GitHub/HubSpot configuration, and all live environment provisioning remain separate authorized operations. Environment projects are created only by their Terraform roots, never manually.
- Staging WIF accepts only the exact repository, protected `staging` environment, and `refs/heads/main`; production has its own project/pool and protected `production` condition.
- Parking staging retains the project/data/recovery path but disables active probes and runtime/database/worker alerts. Complete decommission is a distinct, separately authorized two-review procedure that deliberately removes project deletion safeguards.
- Install-ready min zero reduces idle compute but accepts request cold starts and cannot guarantee continuous workers; active-customer operation requires min one.

ADR 0012 extends this foundation with the separately reviewed browser UI assurance design; it does not change the service/database topology.
