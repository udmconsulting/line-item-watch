# Environment lifecycle and Terraform operations

## Source of truth and boundaries

Terraform in this repository is the environment source of truth; the GCP Console is an observation and emergency interface, not the normal configuration path.

```text
Git repository
  -> infra/staging
       -> gs://udm-liw-tfstate-1007247511793/line-item-watch/staging
  -> infra/production
       -> gs://udm-liw-tfstate-1007247511793/line-item-watch/production
```

The bucket lives in manual bootstrap project `udm-liw-bootstrap-02`. Neither environment owns it. Use only the default Terraform workspace and never reuse plans or variable files across roots. State and saved plans are sensitive infrastructure metadata.

Current status is recorded in [GCP environment inventory](gcp-environment-inventory.md): staging S1 is complete and parked, runtime is not deployed, and production is not provisioned.

## Standard reviewed workflow

Run the pinned Terraform version from a clean understanding of the existing worktree. Supply account-specific billing metadata locally through `TF_VAR_billing_account_id` or an ignored, permission-restricted variable file; never commit it.

```sh
terraform fmt -check -recursive infra
terraform -chdir=infra/staging init -reconfigure -input=false
terraform -chdir=infra/staging validate
terraform -chdir=infra/staging plan -out=staging-reviewed.tfplan
terraform -chdir=infra/staging show staging-reviewed.tfplan
terraform -chdir=infra/staging apply staging-reviewed.tfplan
terraform -chdir=infra/staging plan -detailed-exitcode
```

Every apply requires explicit authorization for the reviewed saved plan. An exit code of 0 on the final plan means `NO CHANGES`; 2 means changes remain and requires investigation. Never apply a freshly regenerated plan under an approval that covered a different saved plan. Delete local plans and transient billing files after review/application; never delete remote state.

## Completed S1 partial-apply recovery

The first S1 apply preserved 55 successfully created addresses. The separately approved recovery completed on 2026-10-03:

- **S1R-A — completed:** with `staging_parked=true`, `database_bootstrap_active=true`, `provision_runtime=false`, and `deploy_service=false`, the reviewed plan added only the WIF provider, Cloud SQL instance at `activation_policy=ALWAYS`, and `line_item_watch` database. State reached 58 addresses.
- **S1R-B — completed:** setting `database_bootstrap_active=false` changed only Cloud SQL `ALWAYS -> NEVER`. GCP reported the instance `STOPPED`, and the required fresh plan reported `NO CHANGES` across all 58 addresses.

`database_bootstrap_active` exists only because Cloud SQL rejects an initial create with `NEVER`. It defaults to false, is valid only for a parked foundation with both runtime gates false, and affects only the database activation policy. It is an exceptional first-create/recovery mechanism, not an operator control and not a routine unpark path. It never enables Cloud Run, jobs, HubSpot behavior, or active monitoring. Production does not expose this input because its database is created active.

Do not use `terraform import`, `state rm`, `state mv`, `force-unlock`, targeted destroy, console creation, or `gcloud sql` patching to bypass recovery.

## Drift and emergency/manual changes

The reconciliation path is:

```text
incident requires manual containment
  -> record actor, time, reason, commands, and observed state
  -> preserve logs/evidence
  -> encode the intended steady state in Terraform (or revert the manual change)
  -> review a full plan
  -> apply the reviewed plan with approval
  -> fresh plan reports NO CHANGES
```

First use `terraform plan -refresh-only` to understand drift when appropriate; do not apply it automatically because accepting drift into state can hide an unwanted manual change. Never edit state to make configuration appear reconciled. If provider import is genuinely required after an incident, design and authorize it separately with exact resource identity and rollback evidence.

## Staging park and unpark

### Park

Normal parked inputs are:

```hcl
staging_parked            = true
database_bootstrap_active = false
```

After runtime exists, the plan must set Cloud SQL to `NEVER`, Cloud Run minimum instances to 0, and remove active public uptime, Cloud Run availability/utilization, SQL/database-readiness, and worker/reliability alerts. It retains the project, service/jobs definitions, database storage/backups, artifacts, secret containers/versions, logs/metrics, and state. Cloud SQL compute stops; storage, backups/PITR, artifacts, secrets, logs, and network usage can still cost money. Apply only after reviewing that there is no destroy beyond deliberately conditional monitoring resources.

Verify with a fresh Terraform plan, `gcloud sql instances describe liw-staging-postgres --project=udm-liw-staging-100724`, Cloud Run service description when it exists, and lists of uptime checks/alert policies. Roll back an unintended park by restoring `staging_parked=false`, reviewing the reverse plan, and applying with approval.

### Unpark

Set `staging_parked=false` and keep `database_bootstrap_active=false`. The plan must set Cloud SQL to `ALWAYS`, Cloud Run minimum instances to 1 (maximum remains 1), and restore active readiness/database/worker monitoring when the service exists. After apply, wait for SQL availability and service readiness, verify worker backlog/reconciliation health, then run backend smoke and the protected read-only HubSpot Playwright acceptance journey. Roll back by reviewing and applying the parked inputs above.

`database_bootstrap_active=true` is never a routine unpark control.

## Deliberate scaling

Scale only from measured latency, concurrency, CPU/memory, database connections/latency/storage, backlog age, and reconciliation freshness. All actions require source changes, a full plan, approval, post-change metrics, and a documented rollback.

| Order | Current Terraform control | Change / verification | Rollback |
|---|---|---|---|
| 1. Cloud Run max instances | `cloud_run_max_instances = 1` is currently explicit in each environment root, not an input variable | If measurements justify it, first add a bounded validated root variable, then change 1 -> 2 -> N. Review database pool capacity and verify revision scaling, latency, backlog, and connections. | Restore the previous maximum after confirming in-flight work and connection headroom. |
| 2. Cloud Run CPU/memory | `1` CPU and `1Gi` are currently explicit in `infra/modules/environment/run.tf` | No clean variable exists. Add reviewed validated inputs before changing limits; verify saturation, GC, memory, cost, and startup behavior. | Restore prior limits through Terraform. |
| 3. Cloud SQL tier | Staging `db-f1-micro` and production `db-custom-1-3840` are explicit root module inputs | Change the root value only after capacity/cost review; plan may show an in-place restart/maintenance impact. Verify tier, connections, latency, backups, and readiness. | Revert to the prior supported tier after data/load safety review. |
| 4. Regional HA | `availability_type = "ZONAL"` is currently explicit in the module | No clean variable exists. Add one with backup/restore/failover and cost acceptance before setting `REGIONAL`. | Downgrade only after a separate availability/data-safety review. |
| 5. Cloud Armor | Not implemented | Add only after observed abuse or policy need; requires design, Terraform resources, rules, preview/false-positive tests, and cost review. | Remove rules/service association through a reviewed plan, not console drift. |
| 6. API/worker split | One modular-monolith Cloud Run service | This is an architecture/application change, not an infrastructure toggle. Require measured independent-scaling or isolation need and a new ADR. | Preserve a compatible rollback release/topology; do not improvise during an incident. |

## Cost and shutdown modes

- **Staging parked:** project and data plane retained; SQL compute off; Cloud Run absent or min 0; active-only alerts absent; residual SSD, idle public IPv4, backups, secrets, artifacts, and logging costs remain.
- **Production pre-launch:** production project/resources do not exist; production runtime cost is zero. Shared bootstrap storage remains.
- **Production install-ready:** database on, Cloud Run min 0, public callback/API available. Cold starts apply and continuous background work is not guaranteed.
- **Production active:** database on, Cloud Run min at least 1, workers continuous, monitoring and approved synthetic enabled.
- **Zero active customers:** production may return to min 0 only if new installs remain accepted and the business accepts cold starts/suspended background work. The database and callback remain available.

A production environment cannot be created only after the first OAuth callback: the callback URL, TLS/edge, service, database, secrets, and monitoring must exist and be accepted before that callback can succeed.

## Emergency containment

Prefer reversible containment and preserve database, state, logs, and audit evidence:

- **Unexpected cost spike:** identify project/service/SKU; reduce bounded Cloud Run capacity or park staging through Terraform. Do not delete SQL/state.
- **Compromised credential:** revoke/disable the exposed credential or secret version through the incident authority, block its deployment path, rotate, and reconcile version references in Terraform. Preserve audit logs.
- **Runaway Cloud Run:** route traffic to a known-good revision, lower maximum capacity through a reviewed emergency change, or park staging. Protect database connections.
- **Broken deployment:** use the documented traffic rollback; do not reverse a successful migration without a proven database procedure.
- **Provider outage:** stop retries that amplify impact, preserve durable queues/data, communicate degraded behavior, and wait/fail over only through an accepted design.
- **Project/account access issue:** use the durable recovery identity and support path; do not delete resources or state. Creator Owner stays until that recovery path is proven.

Complete migration and decommission procedures are in [provider exit plan](provider-exit-plan.md). Park is never decommission.
