# Production topology

## Approved topology

```text
HubSpot and signed clients
          |
          v
api.lineitemwatch.com
          |
global external HTTPS Application Load Balancer
  stable IP + HTTP-to-HTTPS redirect + Google-managed certificate
          |
serverless NEG
          |
Cloud Run SERVICE, europe-west1
  1 vCPU / 1 GiB, instance-based CPU, min 0 install-ready / min 1 active, max 1, concurrency 20
  API + webhook + signal worker + reconciliation worker
          |
Cloud SQL Java Connector (IAM-authenticated encrypted transport)
          |
Cloud SQL PostgreSQL 18 Enterprise
  dedicated 1 vCPU / 3.75 GiB, zonal, SSD 20 GiB, auto-growth
  automated backup + PITR + deletion/final-backup protection
```

Production and staging are isolated in separate Terraform-managed GCP projects under organization `656511895628`. Each root creates and owns its project, billing association, mandatory `application`, `environment`, and `managed_by` labels, APIs, and environment resources. Staging project `udm-liw-staging-100724` has a complete, parked 58-address S1 foundation with Cloud SQL stopped and no runtime; production is not provisioned. The provider is independent of a target project during initial creation; the manual bootstrap project and remote-state bucket are not in either environment graph. Runtime, migration, operator, and delivery identities are distinct. The normal service uses `liw_app`; the single-task migration job uses `liw_migrator`; the one-shot operator job uses `liw_operator`. Secret Manager containers and narrow access bindings are infrastructure-managed, while payloads and explicit versions are injected in a separately authorized process.

The production-only UI assurance extension is described in [UI assurance architecture](ui-assurance.md): Cloud Scheduler invokes a single-task Playwright Cloud Run Job about every ten minutes, using a dedicated HubSpot synthetic fixture, isolated auth-state secrets, sanitized result metrics/logs, and a bounded private evidence bucket. It has no database role and cannot mutate CRM.

Production Cloud Run accepts public invocation only through ingress restricted to the internal/load-balancer path. `allUsers` has the invoker role so the external load balancer can reach the serverless NEG; Cloud Run's ingress setting prevents a direct `run.app` bypass. Validate that restriction during provisioning before treating the edge as accepted.

Production is deliberately single-zone and has no Cloud Armor. A zone outage can make the database unavailable until recovery. Regional HA is an infrastructure-only future upgrade; Cloud Armor can attach to the existing backend service when real traffic supplies defensible thresholds.

## Production lifecycle

Production is not created before launch readiness. Once the product accepts installs, the public origin, load balancer, certificate, service, secrets, migrations, database, monitoring, and recovery controls must already be operational: the first OAuth install cannot safely provision its own callback endpoint, database, IAM, DNS, or secrets.

The bounded operating modes are:

- **Pre-launch:** do not provision the production environment, so production runtime cost is zero. The bootstrap project and remote-state bucket remain as shared prerequisites, but they run no customer application workload.
- **Install-ready, no active customers:** keep the database and public service available, set `production_cloud_run_min_instances=0`, and retain `max_instances=1`. Request-driven OAuth, callback, API, and webhook traffic can cold-start Cloud Run, but continuous background processing is not guaranteed while no instance is running.
- **Active customers:** set `production_cloud_run_min_instances=1` before continuous webhook processing, reconciliation, reliability work, or low-latency response is required. Enable and accept the production synthetic and alert routes. Keep the initial maximum at one until measurements justify a reviewed increase.
- **Zero active customers after launch:** production may return to min zero only if the service must remain open for new installs and the business accepts cold starts plus suspended continuous workers between requests. Do not stop the database or remove the public callback while installs remain enabled.

Every transition is a reviewed Terraform plan/apply by an operator. The application never changes infrastructure. A future scheduled control workflow may read a trusted installation/entitlement count and propose or apply the bounded `0`/`1` minimum through protected infrastructure automation; it must be auditable, fail safe at one when state is uncertain, and remain outside request and install transactions.

## Staging topology

Staging uses the same image and roles, a Cloud Run service with min zero while parked or min one while active (always max one), and a shared-core zonal PostgreSQL 18 database. Its stable origin is the Terraform `api_origin`/provider `cloud_run_uri` output. It does not have a load balancer, custom domain, Cloud Armor, or hardcoded generated hostname. The database may be stopped and Cloud Run parked, but both must be unparked before HubSpot acceptance. Parking also removes active public uptime, Cloud Run, Cloud SQL availability, and worker/reliability alerts so the stopped database neither gets probed nor causes expected alert noise; unpark restores them.

No explicit staging zone is configured. That is intentional for the low-cost zonal tier: Cloud SQL selects a zone within `europe-west1`, while the architecture makes no fixed-zone affinity or HA claim. Pinning a zone would add a placement constraint without improving this single-zone staging recovery posture. If later evidence requires co-location or deterministic zonal capacity, expose a reviewed variable rather than hardcoding a zone.

Parking retains the environment project, storage/backups, secrets, artifacts, and state. Complete environment decommission is a separate authorized lifecycle with export/retention and external-system cleanup, followed by deliberate removal of both project deletion safeguards. The shared bootstrap/state bucket always survives.

Cloud SQL must be active on its initial create because the API rejects direct creation with activation policy `NEVER`. Staging uses the first-create/recovery-only `database_bootstrap_active=true` while all runtime gates and active monitoring remain off, then a separately reviewed plan returns that flag to false so the parked steady state becomes `NEVER`. See [environment lifecycle](../operations/environment-lifecycle.md).

The outputs also derive the OAuth callback and webhook URLs used by the environment-specific HubSpot profile. Once staging is live, this origin replaces the temporary quick-tunnel acceptance flow.

## Scale triggers

Use measured load and reliability signals, not customer count alone. The primary signals are request rate/concurrency and latency, Cloud Run CPU/memory, database CPU/connections/latency/storage headroom, processing backlog age, processing latency, and reconciliation lag. Scale in this order:

1. Raise Cloud Run maximum instances from **1 → 2 → N** in reviewed bounded steps when concurrency, request latency, processing backlog, or reconciliation lag shows one instance is insufficient; confirm the database connection budget first. PostgreSQL leases, `SKIP LOCKED`, idempotency, and optimistic guards support multiple instances.
2. Increase Cloud Run CPU or memory when saturation, GC, or memory pressure is the bottleneck rather than instance count.
3. Resize Cloud SQL when database CPU, memory, connections, latency, or storage headroom breach agreed operating margins.
4. Enable Regional HA when the business impact of a zonal outage exceeds its ongoing cost and restore/failover acceptance is complete.
5. Add Cloud Armor when observed abuse, bot traffic, or edge policy requirements justify evidence-based rules.
6. Split API and workers only when independent scaling, noisy-neighbor isolation, deployment cadence, or failure-domain evidence outweighs the operational cost.

No customer-count threshold substitutes for these runtime measurements.

## Portability boundary

The deployable remains a standard OCI image using PostgreSQL 18, `pg_trgm`, Liquibase, JDBC, environment configuration, and Micrometer. Cloud Run, Secret Manager, and Cloud Monitoring are not referenced by domain/application code. The Cloud SQL connector and Stackdriver registry are isolated runtime adapters. Moving to Render, Azure Container Apps, or AWS ECS/App Runner requires deployment/configuration replacement, not business-logic changes.
