# Deployment and environment preparation

The manual GCP bootstrap is complete and inventoried in [GCP bootstrap inventory](../operations/gcp-bootstrap-inventory.md). The organization, billing account, bootstrap project, remote-state bucket, and billing-level budget guardrail already exist. Terraform owns both environment projects. Staging project `udm-liw-staging-100724` is at **S1 PARTIAL — RECOVERY REQUIRED** with 55 preserved state addresses; production does not exist. See the live [GCP environment inventory](../operations/gcp-environment-inventory.md) and canonical [environment lifecycle](../operations/environment-lifecycle.md). Review never authorizes apply, secret payloads, DNS/GitHub changes, or HubSpot deployment.

## Build and runtime roles

Build the single OCI artifact from the repository root:

```bash
docker build \
  --build-arg SOURCE_REVISION="$(git rev-parse HEAD)" \
  --build-arg BUILD_TIME=1970-01-01T00:00:00Z \
  -t line-item-watch:local .
```

`APPLICATION_RUNTIME_ROLE` selects `SERVICE`, `MIGRATE`, `OPERATOR`, or local compatibility mode. Production uses the first three only:

- `SERVICE`: HTTP and configured workers; Liquibase release mutation is disabled.
- `MIGRATE`: no HTTP/workers/operator; Liquibase runs once and the process exits.
- `OPERATOR`: no HTTP/workers/Liquibase; one explicit command runs and exits.

The image entrypoint adds the `gcp` profile only when `CLOUD_SQL_INSTANCE_CONNECTION_NAME` is present. Without it, normal JDBC remains the portable default. `PORT` defaults to 8080 and Spring graceful shutdown is enabled.

## Terraform state bootstrap

Both roots use the existing bootstrap-owned bucket `udm-liw-tfstate-1007247511793`. The non-secret bucket name and isolated prefixes are checked into each backend block:

- staging: `line-item-watch/staging`
- production: `line-item-watch/production`

The bucket is external to both environment modules and must not be imported into or recreated by them. Separate roots and prefixes are the isolation boundary; use only the default workspace and do not use Terraform workspaces to switch environments. State can contain sensitive infrastructure metadata, so runtime identities get no access and configuration must continue to keep secret payloads outside Terraform.

The initialization path is: **authenticated local operator → user Application Default Credentials → existing GCS bucket → `terraform init` → environment plan/review**. On first creation the plan includes the target project; on current staging recovery it refreshes the 55-address partial state. CLI login and ADC are separate as described in [developer tooling](tooling.md).

For staging initialization on an authenticated workstation:

```sh
gcloud auth login
gcloud auth application-default login
gcloud auth list
gcloud config set project udm-liw-bootstrap-02
terraform -chdir=infra/staging init
terraform -chdir=infra/staging fmt -check
terraform -chdir=infra/staging validate
terraform -chdir=infra/staging plan -out=staging.tfplan
terraform -chdir=infra/staging show staging.tfplan
```

This flow is human `gcloud` authentication plus user ADC, then the existing GCS bucket, then `terraform init`, then staging plan/review. Do not run `terraform init -migrate-state`; the staging state already belongs at its checked-in prefix. Do not commit the saved plan or `.terraform/`; plan files can contain sensitive infrastructure values.

To switch environments, change directory/root explicitly and inspect `pwd` before running Terraform. Production uses `terraform -chdir=infra/production ...` and its own prefix. Never reuse a plan file across roots.

## Environment provisioning sequence

After external authorization:

1. Confirm the manual bootstrap inventory instead of recreating the organization, billing account, bootstrap project, state bucket, or billing-level budget.
2. Choose a globally unique environment `project_id` and the approved `project_name`, `environment`, and `region`. Supply the account-specific billing ID only through a protected local mechanism such as `TF_VAR_billing_account_id`; do not commit it.
3. Authenticate `gcloud` and ADC, then initialize the chosen root against its checked-in backend. For a new environment, the target project must not pre-exist: the provider has no target-project default, and the module creates it under the configured organization before enabling its APIs. Current staging is not new; preserve and refresh its existing remote state.
4. Copy the non-secret example values to an ignored local variable file, then run `terraform fmt -check`, `terraform validate`, and review a saved staging `terraform plan`. Confirm the `google_project` organization parent, billing association, required labels, deletion safeguards, API order, resource count, database tier/storage, parked state, edge exclusions, and estimated cost. Applying requires a separate explicit authorization.
5. Apply the foundation with `provision_runtime=false` and `deploy_service=false`. This creates the environment project, attaches billing, enables Service Usage then Resource Manager then the remaining required APIs, and creates Artifact Registry, secret containers, identities/WIF, Cloud SQL, and foundation monitoring. It creates no Cloud Run service/jobs and needs no image digest, HubSpot runtime IDs, secret numeric versions, or generated Cloud Run origin input. Terraform creates no secret payloads. Staging Cloud SQL initial creation additionally requires `staging_parked=true` and the transitional `database_bootstrap_active=true`; immediately follow it with a separately reviewed `database_bootstrap_active=false` park plan. The override affects only SQL activation and is invalid with runtime gates.
6. Provision `pg_trgm` and the initial database roles with `infra/database/bootstrap-roles.sql` as the bootstrap database administrator. Pass passwords with protected `psql` variables or a secure interactive method; never save them in shell history/source.
7. Add each secret payload out of band and record its immutable numeric version. Update `secret_versions`; never use `latest`.
8. Configure the staging GitHub Environment and manually run `Deliver staging` with `artifact_only=true`. Record the emitted immutable image reference; this bootstrap digest is not yet staging-accepted.
9. Set `image_digest` to that digest and `provision_runtime=true` while keeping `deploy_service=false`; supply the HubSpot IDs, active key IDs, and only the explicit secret versions consumed by the jobs. Review and apply to create the migration/operator jobs. Never set `provision_runtime` back to false after this point because that requests destruction of the jobs.
10. Execute the migration job, then rerun `infra/database/bootstrap-roles.sql` so existing application tables receive runtime and bounded operator grants while Liquibase tables remain inaccessible to runtime/operator identities.
11. Set `deploy_service=true`; for staging also set `staging_parked=false`. Review and apply to create the service and, in production only, its load-balancer resources. Never set `deploy_service` back to false after this point because that requests service destruction.
12. Configure the dedicated staging HubSpot automation user/fixture and protected environment values, then deliver the same application digest. Require backend readiness and the real Playwright HubSpot journey before accepting both application and assurance image digests.
13. Before enabling the production synthetic, create its dedicated HubSpot user/fixture and inject explicit versions for browser state, fixture JSON, and record URL. Set `enable_production_synthetic=true` only with an immutable assurance image, review/apply, and verify the job, scheduler, evidence lifecycle, metrics, and notification routes.
14. Configure and protect the production GitHub Environment, promote only both staging-accepted `sha256` digests through the manual production workflow, and require the read-only production job smoke before release acceptance. Separately authorize DNS/HubSpot changes.

The bootstrap SQL creates `liw_app` for runtime DML/sequences, `liw_migrator` for schema/Liquibase ownership work, and `liw_operator` for bounded P.8/P.9 recovery actions. None owns the database or can create extensions; the bootstrap administrator installs `pg_trgm` and is never an application credential.

## Required GitHub environment variables

Staging: `GCP_STAGING_PROJECT_ID`, `GCP_STAGING_WIF_PROVIDER`, `GCP_STAGING_DEPLOYER_SERVICE_ACCOUNT`, `GCP_STAGING_ARTIFACT_REPOSITORY`, `GCP_STAGING_MIGRATION_JOB`, `GCP_STAGING_SERVICE`, and `GCP_STAGING_API_ORIGIN`. The protected environment also needs secret `LIW_STAGING_HUBSPOT_RECORD_URL`, secret `LIW_STAGING_HUBSPOT_STORAGE_STATE_JSON`, and variable `LIW_STAGING_ASSURANCE_FIXTURE_JSON`; none is available to PR CI.

Production: `GCP_PRODUCTION_PROJECT_ID`, `GCP_PRODUCTION_WIF_PROVIDER`, `GCP_PRODUCTION_DEPLOYER_SERVICE_ACCOUNT`, `GCP_PRODUCTION_ARTIFACT_REPOSITORY`, `GCP_PRODUCTION_MIGRATION_JOB`, `GCP_PRODUCTION_SERVICE`, `GCP_PRODUCTION_SYNTHETIC_JOB`, and `GCP_PRODUCTION_API_ORIGIN`. Promotion also reads the staging repository so its identity needs the narrowly scoped cross-project artifact access prepared by Terraform. Production browser secrets are read by the Cloud Run job directly from Secret Manager and never enter GitHub Actions.

No static service-account JSON key is supported. Both GCP WIF providers require the exact repository, protected GitHub Environment, and `refs/heads/main`; manual `workflow_dispatch` remains supported when dispatched from `main`. Production remains isolated in its own project/pool and never auto-deploys from `main`; it must use the exact staging-accepted digest.

## HubSpot environment values

Resolve both project variables per environment:

- `LINE_ITEM_WATCH_API_ORIGIN`: staging Terraform `api_origin`; production `https://api.lineitemwatch.com`.
- `LINE_ITEM_WATCH_OAUTH_REDIRECT_URI`: the matching Terraform `oauth_redirect_uri`.

Use the examples under `config/hubspot/` to create ignored, local `src/hsprofile.<name>.json` files as required by the HubSpot CLI. Linting is read-only. Upload/deploy remains an explicit external mutation.

## Staging park and unpark

The detailed verification, rollback, drift, emergency, and scaling procedures are canonical in [environment lifecycle](../operations/environment-lifecycle.md).

### Park

1. Confirm no acceptance, migration, operator, or incident work is running and no webhook continuity is expected.
2. Set `staging_parked=true` and `database_bootstrap_active=false`, create and inspect a staging-only saved plan, and apply only through an authorized infrastructure change.
3. Verify Cloud Run minimum instances are zero, Cloud SQL activation is `NEVER`, and Terraform removes public readiness uptime checks plus active Cloud Run, Cloud SQL, database-availability, and worker/reliability alert policies. Staging has no scheduled browser synthetic; protected delivery E2E remains available only after unpark.

The project, Artifact Registry, secret containers, database storage/backups, metric definitions/logging, and remote state remain. Active application/database alerts are intentionally absent while parked so a stopped database cannot generate an expected outage storm. Those retained services create residual storage/logging and database-storage/backup cost even while compute is parked.

With Cloud Run min zero, an HTTP/webhook request can cold-start the service only if Cloud SQL is available. While the database is stopped, readiness fails and webhook-driven work is not reliably accepted. Even with the database running and min zero, Cloud Run does not guarantee an instance remains alive for polling workers, so continuous signal/reconciliation/reliability processing is not guaranteed.

### Unpark

1. Set `staging_parked=false` and keep `database_bootstrap_active=false`, review the staging-only plan, and apply with explicit authorization.
2. Wait for Cloud SQL to report runnable and Cloud Run to reach min one.
3. Confirm Terraform restores public readiness, Cloud Run, Cloud SQL/database-availability, and worker/reliability alert policies. Execute the migration job, verify liveness and readiness, then verify workers and backlog/processing/reconciliation health.
4. Run backend smoke and the protected real HubSpot Playwright staging journey before acceptance work.

Never apply the parked setting to production. A future manual GitHub Actions lifecycle workflow may wrap the same reviewed Terraform change, but console clicks and application-owned infrastructure control are not the target operating model.

## Environment decommission

The complete canonical sequence is [provider exit plan](../operations/provider-exit-plan.md). The summary below does not replace it.

Park and decommission are different operations. **Park** retains the project, state, data, backups, secrets, artifacts, and recovery path while reducing staging runtime cost. It never destroys the project. **Decommission** intentionally removes a complete environment and requires its own approved change record; production always needs separate explicit authorization.

Before decommissioning, freeze delivery and writes, inventory legal/contractual retention duties, produce and verify required database exports/backups, preserve restore evidence, record artifact/secret version and key-recovery implications, and decide what evidence must outlive the project. Clean up or transfer external DNS, HubSpot redirect/webhook/app configuration, GitHub Environment/WIF variables, monitoring routes, and cross-project Artifact Registry grants in an explicit order so no dangling trust remains.

Project deletion is blocked twice in normal configuration: Terraform `prevent_destroy` rejects the plan, and Google provider `deletion_policy = "PREVENT"` rejects provider-driven deletion. A decommission therefore uses two separately reviewed Terraform changes: first keep the project resource configured while deliberately removing `prevent_destroy` and changing the deletion policy to `DELETE`; apply/review that guardrail-only transition. Only then review a second plan that removes the environment resources/project, and apply it with the decommission authorization. Never bypass this with `terraform state rm`, console deletion, or an ordinary park change.

The shared bootstrap project and `gs://udm-liw-tfstate-1007247511793` bucket are outside the environment graph and survive. After the final environment destroy, retain/export the final state and plan/apply/audit evidence according to the approved retention policy; archive or remove that environment prefix only as a separate state-custodian action after recovery and audit requirements are satisfied. Do not delete the shared bucket.

Production lifecycle phases are defined in [production topology](../architecture/production-topology.md) and the [production runbooks](../operations/production-runbooks.md).

## Verification without GCP

```bash
./backend/mvnw -f backend/pom.xml verify
npm --prefix src/app/cards ci
npm --prefix src/app/cards run typecheck
npm --prefix src/app/cards run lint
npm --prefix src/app/cards run format:check
npm --prefix src/app/cards test -- --run
npm --prefix assurance ci
npm --prefix assurance run typecheck
npm --prefix assurance run test:config
npm --prefix assurance test
npm --prefix assurance run test:visual
hs project lint --install-missing-deps=false --no-color
terraform fmt -check -recursive infra
(cd infra/staging && terraform init -backend=false && terraform validate)
(cd infra/production && terraform init -backend=false && terraform validate)
```

The CI container job additionally builds the image, verifies Java 25 and the minimal non-root JRE contents, exercises successful and failed `MIGRATE`, proves an invalid `OPERATOR` invocation exits non-zero, starts `SERVICE` on a non-default `PORT`, checks liveness/readiness, and requests a graceful stop.
