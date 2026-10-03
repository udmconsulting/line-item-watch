# Deployment principles

## Initial direction

Private Beta should use simple managed infrastructure and a modular-monolith deployment. Kubernetes, service mesh, and a microservice topology are not initial requirements.

The same OCI image exposes explicit runtime roles:

- **SERVICE:** public HTTPS API/webhooks and the configured durable signal/reconciliation workers;
- **MIGRATE:** HTTP-free, worker-free, single-task Liquibase execution; and
- **OPERATOR:** HTTP-free, worker-free, one-shot bounded recovery commands.

The approved baseline keeps API and workers together. Logical role boundaries permit separation later without requiring it now. The implemented foundation is one Spring Boot 4.1.1 artifact built with Java 25 and Maven; database leases and idempotency preserve future multi-instance safety. No public administration/disconnect API exists.

## Infrastructure expectations

- PostgreSQL 18 is the implemented database baseline. Liquibase is the sole schema-management mechanism, and Hibernate validates rather than creates or updates the schema.
- Migration `008` requires the trusted PostgreSQL `pg_trgm` extension and creates the latest-name GIN index. A database/platform owner must pre-provision it when the application migration role lacks database `CREATE` privilege or the managed service restricts extension installation. `CREATE EXTENSION IF NOT EXISTS pg_trgm` then succeeds or the migration halts; there is no unindexed substring-search fallback and runtime credentials need not be superuser.
- Every public endpoint requires HTTPS/TLS.
- Secrets and OAuth credentials require managed protection and encryption at rest.
- Production data requires managed backups, defined retention, and verified restores.
- Services require health monitoring, centralized logs, error tracking, and actionable alerts.
- Access is least-privilege, individual rather than shared, controlled, and auditable where appropriate.
- An EU processing region is preferred where practical, subject to provider evaluation.
- OAuth client credentials and the Base64-encoded AES-256 credential key are mandatory environment-supplied configuration. The application fails startup when these are absent or invalid.
- When the Deal audit endpoint is enabled, a separate canonical Base64 32-byte cursor-integrity key and safe key ID are mandatory. OAuth and credential-encryption secrets must not be reused. One optional previous ID/key pair supports a bounded rotation window.
- HubSpot provider traffic requires HTTPS outside explicit localhost/loopback development stubs and uses externally configurable bounded connect/read timeouts.
- GCP is selected in `europe-west1` with separate Terraform-managed staging and production projects under the existing organization. Production uses Cloud Run behind `api.lineitemwatch.com` and a global HTTPS load balancer; staging uses its stable Terraform-output `run.app` URI without a load balancer. The manual bootstrap project/state bucket are outside both environment graphs.
- Production starts at min zero while install-ready without active customers and moves to min one before continuous customer processing is required; max one and zonal dedicated Cloud SQL PostgreSQL 18 remain the initial bounds. Staging starts parked-capable with shared-core zonal PostgreSQL. Cloud Armor and Regional HA are deferred measured upgrades.
- Secret Manager supplies explicitly pinned secret versions. Workload Identity Federation supplies delivery identity; static service-account keys are not used.

## Approved implementation

Docker Compose supplies PostgreSQL 18.6 for local development only. Production topology, state/bootstrap procedure, delivery, roles, and cost/scale triggers are documented in [production topology](production-topology.md), [ADR 0011](../adr/0011-gcp-production-foundation.md), [deployment](../development/deployment.md), and the live [GCP environment inventory](../operations/gcp-environment-inventory.md). The manual organization, billing/bootstrap project, budget alert, and protected versioned state bucket exist; they run no customer workload. Staging S1 is partially provisioned and requires recovery, but has no database/runtime/customer workload; production is not provisioned. Notification ownership, exact retention, RPO/RTO, restore acceptance, and production access review remain provisioning gates.

## Deal App Card acceptance deployment

P.7 live acceptance may temporarily resolve the project-profile variable `LINE_ITEM_WATCH_API_ORIGIN` to an authorized Cloudflare quick-tunnel HTTPS origin. The same canonical origin must configure backend signature validation through `HUBSPOT_UI_EXTENSION_PUBLIC_BASE_URI`; the app manifest derives its least-privilege fetch prefix from the profile variable. The ignored acceptance profile contains an account ID and non-secret origin only, never OAuth or encryption credentials.

This is an acceptance-only exception until the separately authorized staging runtime is live and accepted. Quick-tunnel hostnames are ephemeral and must not be committed. Staging's Terraform-output `run.app` origin then becomes the stable acceptance target. Project validation is read-only, but `hs project upload --profile=acceptance` is an explicitly authorized HubSpot mutation that auto-deploys a build. Any origin change must be validated and uploaded again because it changes the deployed `permittedUrls.fetch` and OAuth redirect resolution.

Acceptance cleanup requires an intentional deployment decision before the temporary backend or tunnel is stopped. Otherwise the deployed card remains configured for an unreachable origin. The operator must choose an authorized rollback, replacement deployment, or explicitly accepted unavailable-card state, then stop local processes and remove the ignored profile. Repository definitions for permanent hosting, managed secrets, and delivery now exist; provisioning remains external and not yet performed.
