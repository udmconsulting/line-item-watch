# P.9 production environment and platform hardening plan

## Status and boundary

Status: **IN PROGRESS — staging S1 foundation complete and parked; runtime not deployed; S2 not started; S1H deferred**.

The manual organization/billing/bootstrap-project foundation, billing budget alert, and protected remote-state bucket are complete and recorded in the canonical bootstrap inventory. Terraform-created staging project `udm-liw-staging-100724` now has a complete 58-address S1 foundation. S1R-A created the bounded WIF provider, PostgreSQL instance, and `line_item_watch` database; S1R-B parked the database at `activation_policy=NEVER`, and the mandatory follow-up plan reported `NO CHANGES`. Production is not provisioned. Runtime, secret payloads, application images, DNS, GitHub environment settings, and HubSpot upload/deploy remain unauthorised and absent. S2 artifact bootstrap is next; S1H bootstrap hardening remains a separate pre-production requirement. See [GCP environment inventory](../operations/gcp-environment-inventory.md), [environment lifecycle](../operations/environment-lifecycle.md), and [provider exit plan](../operations/provider-exit-plan.md).

## Approved baseline

GCP `europe-west1` is selected with separate staging and production projects. One Java 25 OCI image supports service, migration, and operator roles. Production runs the combined API/webhook/workers on Cloud Run at 1 vCPU/1 GiB, instance-based CPU, max one initially, min zero while install-ready without active customers, min one for active customers, concurrency 20, and uses a dedicated-core PostgreSQL 18 Enterprise instance in one zone with 20 GiB SSD, auto-growth, backups, PITR, deletion protection, and final-backup protection.

Production owns the stable `api.lineitemwatch.com` contract through a global external HTTPS load balancer, managed certificate, redirect, static address, and serverless NEG. Staging uses its stable Terraform-output `run.app` origin, min zero while parked or min one while active (always max one), a shared-core zonal database, and no dedicated edge/domain. Cloud Armor and production Regional HA are explicitly deferred.

## Implemented repository work

- Production-safe role invariants, one-shot migration/operator exit, graceful worker claim shutdown, aggregate liveness/readiness, and local compatibility.
- Cloud SQL Java Connector and isolated GCP profile while retaining ordinary JDBC.
- Active/previous credential encryption key ring, explicit key selection, bounded tenant-scoped optimistic rewrap/verify, activity audit, and metrics.
- Non-root multi-stage Java 25 OCI image with reproducible metadata inputs.
- Reusable, provider-pinned Terraform for organization-parented environment projects, billing association, deletion safeguards, ordered APIs, identities, Artifact Registry policy, secrets containers/IAM, Cloud Run service/jobs, PostgreSQL, WIF, monitoring, optional environment budget, and the production edge.
- CI, staging delivery, and approval-gated exact-digest production promotion workflows.
- Structured JSON production logging, isolated Cloud Monitoring export, bounded new metrics, alert definitions, runbooks, cost tiers, and environment-specific HubSpot profile examples.
- Explicit GCS backends in the existing `udm-liw-tfstate-1007247511793` bucket using `line-item-watch/staging` and `line-item-watch/production`, plus a canonical bootstrap inventory and developer tooling/machine-migration guide.
- Terraform-native staging SQL first-create/recovery gating plus canonical live inventory, lifecycle/park/scale/drift, provider migration, and controlled decommission documentation.

## Acceptance gates

Repository completion requires backend/frontend/HubSpot verification, OCI build/runtime checks, Terraform format/validate, audits/secret scan, and a full privacy/portability/IAM/runtime review. Provisioning acceptance later must prove WIF, explicit secret versions, role bootstrap, migrations, staging OAuth/webhooks/card, direct-ingress restriction, production certificate/DNS, alerts, backups/PITR restore, rollback, and cost visibility.

## Cost and scale tiers

### Tier 0 — bootstrap and pre-launch

Before S1, only the bootstrap project, protected state bucket, and billing budget notification existed. Staging now retains a complete parked foundation: SQL compute is stopped, WIF is active, Artifact Registry is empty, eight secret containers have zero versions, and no runtime or monitoring workload exists. Production remains absent until a production-readiness decision and explicit authorization. A plan never creates resources by itself. The budget notification is not a cap. The abandoned `udm-liw-bootstrap-01` project remains in `DELETE_REQUESTED` and is never reused or restored.

### Tier 1 — install-ready/customer-ready baseline

Delay the production root apply until the product is ready to accept installs; the production project/resources need not exist before then. Once created, use min zero/max one while there are no active customers, with the database and callback/API available; move to min/max one before continuous customer processing is required. Staging uses min zero parked/min one active, max one, shared-core database, `run.app`, no load balancer, and is normally parked when unused. Parking stops SQL compute and removes active uptime/database/worker alerts while retaining the project, storage, backups, secrets, artifacts, and state. Neither environment initially uses Armor or database HA.

Planning estimate: production approximately **$125–140/month**, staging approximately **$10–15/month while mostly parked plus active usage**, combined approximately **$135–155/month** before taxes, FX, traffic, logs, egress, and storage variation. These are not guaranteed prices. Recalculate with the official [Google Cloud Pricing Calculator](https://cloud.google.com/products/calculator), [Cloud Run pricing](https://cloud.google.com/run/pricing), [Cloud SQL pricing](https://cloud.google.com/sql/pricing), [load-balancing pricing](https://cloud.google.com/vpc/network-pricing#lb), and [operations-suite pricing](https://cloud.google.com/stackdriver/pricing) before provisioning.

At the current empty, foundation-only parked checkpoint, the approximate list-price baseline is **$9–10/month**: about $7.30/month for Cloud SQL's idle public IPv4 plus about $1.70/month for 10 GiB SSD, before incremental backup usage, logs, state storage, taxes, or FX. There is no SQL, Cloud Run, job, or synthetic compute charge while this state is retained.

An eligible new billing account may receive the advertised **$300/90-day** trial credit. Free-tier allowances and promotional credits are distinct from the sustainable post-credit run rate; this architecture does not rely on either.

### Cost guardrails

- The manually configured billing budget sends notifications; it is not a hard spending cap.
- Staging and the initial production service are capped at one Cloud Run instance. Staging is parked outside acceptance work, and production is not created until install readiness.
- Staging has no load balancer or custom domain. Neither environment starts with Cloud Armor or Regional HA; both databases are initially zonal.
- The production browser synthetic runs on a bounded default ten-minute schedule and retains only private safe evidence for 30 days.
- Artifact Registry keeps the 20 most recent versions and protected release/rollback/incident-hold tags, then deletes untagged artifacts older than 30 days.
- Structured logs and metrics use bounded, low-cardinality labels and exclude customer/provider identifiers. Investigate logging, storage, egress, and unexpected active compute during cost anomalies before weakening recovery or security controls.

### Tier 2 — availability/growth

Measured triggers can raise Cloud Run max instances/resources, resize Cloud SQL, enable Regional HA, or attach Cloud Armor. The triggers are sustained latency/concurrency, backlog age, CPU/memory/connection/storage headroom, observed abuse, and agreed zonal-outage impact.

### Tier 3 — higher scale

Only evidence of independent scaling, isolation, or deployment needs justifies splitting API/workers, adding worker replicas, or adding a DR replica. Existing durable claims, leases, idempotency, and database ownership remain the foundation.

## UI assurance delta

The separately reviewed P.9 delta now supplies Playwright customer-facing and selective visual regression coverage, a staging post-deploy HubSpot gate, a production read-only Cloud Run Job synthetic, Cloud Monitoring alert integration, bounded failure evidence, and an optional non-authoritative AI diagnosis boundary. Repository implementation does not claim live acceptance: dedicated HubSpot users/fixtures, auth-state secrets, environment GCP resources, notification delivery, and scheduled execution remain provisioning gates. The bootstrap project and remote-state bucket contain no customer workload. See [ADR 0012](../adr/0012-browser-ui-assurance.md) and [UI assurance architecture](../architecture/ui-assurance.md).

No P.10 Marketplace scope is included. Production provisioning, live plans, restore acceptance, routing/notification ownership, retention/RPO/RTO approval, and external security/legal acceptance remain explicit gates rather than repository claims.
