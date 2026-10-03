# GCP environment inventory

Inventory verified read-only on 2026-10-03. The shared bootstrap inventory is maintained separately in [GCP bootstrap inventory](gcp-bootstrap-inventory.md). Never place billing-account identifiers, credentials, secret payloads, or Terraform state contents in this document.

## Current status

**STAGING S1 FOUNDATION COMPLETE — PARKED.** Project `udm-liw-staging-100724` exists under organization `656511895628`; Terraform remote state contains 58 managed addresses at prefix `line-item-watch/staging`. The approved S1R-A recovery created the WIF provider, Cloud SQL instance, and application database. S1R-B then changed only Cloud SQL `activation_policy` from `ALWAYS` to `NEVER`; the mandatory post-apply plan reported `NO CHANGES`. Runtime is not deployed. S2 artifact hardening and the executable operations layer are implemented and validated locally, but Artifact Registry still contains zero images and publication is not authorized. S1H bootstrap hardening remains deferred and required before production.

Production is **NOT PROVISIONED**. The production state prefix remains `line-item-watch/production` and is isolated from staging.

## Shared/manual resources

| Resource | Owner | Purpose | Billing | Lifecycle / protection | Read-only verification |
|---|---|---|---|---|---|
| Organization `ud-management-co-org` (`656511895628`) | Manual UDM bootstrap | Resource hierarchy and policy boundary | No standalone runtime charge | Must survive any environment lifecycle | `gcloud organizations describe 656511895628` |
| Bootstrap project `udm-liw-bootstrap-02` | Manual UDM bootstrap | Holds shared state and bootstrap controls | Usage-dependent | Never part of an environment destroy | `gcloud projects describe udm-liw-bootstrap-02` |
| State bucket `gs://udm-liw-tfstate-1007247511793` | Manual UDM bootstrap | Versioned, protected Terraform state | Yes, small storage/operations residual | Survives staging and production decommission | `gcloud storage buckets describe gs://udm-liw-tfstate-1007247511793` |
| Billing-level budget guardrail | Manual UDM bootstrap | Notification, not a spending cap | No separate charge expected | Shared; changing it is not an environment apply | Verify in the approved billing-administration path |

## Staging inventory

The project display name is `UDM LIW Staging`, the intended region is `europe-west1`, and required labels are `application=line-item-watch`, `environment=staging`, and `managed_by=terraform`. Google also records `goog-terraform-provisioned=true`. The project has provider deletion policy `PREVENT` and Terraform `prevent_destroy`.

| Resource group | Current live state | Owner | Purpose | Billing | Lifecycle / protection | Read-only verification |
|---|---|---|---|---|---|---|
| Environment project | Present and ACTIVE; billing enabled | Terraform, with Google-created bootstrap metadata | Staging isolation and billing boundary | No project fee; child usage is billable | Persistent; double deletion guard | `gcloud projects describe udm-liw-staging-100724` |
| Terraform APIs | 11 state addresses: Service Usage, Resource Manager, Artifact Registry, IAM, IAM Credentials, Logging, Monitoring, Cloud Run, Secret Manager, Cloud SQL Admin, STS | Terraform | Required platform control planes | Enabling alone has no standalone fee; API/resource usage can | Persistent; `disable_on_destroy=false` | `terraform -chdir=infra/staging state list` and `gcloud services list --enabled --project=udm-liw-staging-100724` |
| Intended service accounts | `liw-staging-runtime`, `liw-staging-migration`, `liw-staging-operator`, `liw-staging-deployer`; no user-managed keys | Terraform | Separate runtime, schema, recovery, and delivery identities | No standalone fee | Persistent; delete only in decommission | `gcloud iam service-accounts list --project=udm-liw-staging-100724` |
| IAM | 28 Terraform state addresses: scoped project, repository, service-account, Artifact Registry, and per-secret grants | Terraform | Least-privilege execution and delivery | No standalone fee | Persistent; reconcile only through Terraform | `gcloud projects get-iam-policy udm-liw-staging-100724` plus per-resource policies |
| WIF pool | `liw-staging-github` present | Terraform | Keyless GitHub federation boundary | No standalone fee | Persistent | `gcloud iam workload-identity-pools describe liw-staging-github --location=global --project=udm-liw-staging-100724` |
| WIF provider | Provider ID `github` present and ACTIVE; exact repository `udmconsulting/line-item-watch`, environment `staging`, ref `refs/heads/main` | Terraform | Enforce bounded keyless staging delivery | No standalone fee | Persistent | `gcloud iam workload-identity-pools providers describe github --workload-identity-pool=liw-staging-github --location=global --project=udm-liw-staging-100724` |
| Artifact Registry | `liw-containers` Docker repository in `europe-west1`; zero images at verification | Terraform | Immutable application/assurance OCI artifacts | Storage and network usage-dependent | Persistent; cleanup policies retain recent/protected releases | `gcloud artifacts repositories describe liw-containers --location=europe-west1 --project=udm-liw-staging-100724` |
| Secret Manager | Eight empty containers; zero versions | Terraform containers/IAM; payload injection is a separate authorized process | Runtime configuration without payloads in state | Secret versions/access operations are usage-dependent | Persistent; payload lifecycle remains external | `gcloud secrets list --project=udm-liw-staging-100724` and `gcloud secrets versions list SECRET` |
| Cloud SQL | `liw-staging-postgres` present and STOPPED: PostgreSQL 18, `db-f1-micro`, zonal `europe-west1-b`, 10 GiB SSD, 50 GiB growth cap, backups/PITR enabled, `activation_policy=NEVER` | Terraform | Parked staging PostgreSQL foundation | No database compute while stopped; SSD, idle public IPv4, and backup usage remain billable | `prevent_destroy`; instance deletion protection enabled | `gcloud sql instances describe liw-staging-postgres --project=udm-liw-staging-100724` |
| Application database | `line_item_watch` present; no Terraform-managed SQL users or credentials | Terraform | Application schema target | Included in instance/storage costs | `deletion_policy=ABANDON` and `prevent_destroy` | Terraform state remains authoritative while the stopped instance rejects database-list calls; the post-park refresh plan successfully read it |
| Cloud Run service/jobs | None | Terraform when later runtime gates are authorized | Application, migration, and operator execution | None currently; later usage/instance-based | Phase-specific; not part of S1R | `gcloud run services list --region=europe-west1 --project=udm-liw-staging-100724` and `gcloud run jobs list --region=europe-west1 --project=udm-liw-staging-100724` |
| Monitoring policies/uptime checks | None | Terraform when active runtime monitoring is authorized | Runtime/readiness/reliability alerting | None currently; later usage-dependent | Active-only monitoring stays absent while parked | `gcloud monitoring uptime list-configs --project=udm-liw-staging-100724` and alert-policy listing |
| Networks and buckets | None in staging | Terraform only if a later reviewed architecture requires them | No current purpose | None | Absence is intentional | `gcloud compute networks list --project=udm-liw-staging-100724` and `gcloud storage buckets list --project=udm-liw-staging-100724` |
| Dependency barrier | `terraform_data.environment_ready` | Terraform local state | Orders project/API creation before environment resources | No | Persistent state-only resource | `terraform -chdir=infra/staging state show module.environment.terraform_data.environment_ready` |

The eight empty secret containers are `liw-staging-hubspot-oauth-client-secret`, `liw-staging-credential-encryption-active-key`, `liw-staging-credential-encryption-previous-key`, `liw-staging-cursor-integrity-active-key`, `liw-staging-cursor-integrity-previous-key`, `liw-staging-database-runtime-password`, `liw-staging-database-migration-password`, and `liw-staging-database-operator-password`.

## Google-created bootstrap resources

These resources are observed side effects, not unmanaged application infrastructure to delete reflexively.

| Observed resource | Classification and evidence | Current dependency / cost | Recommendation |
|---|---|---|---|
| `compute.googleapis.com` | **B — provider bootstrap dependency.** The Google provider enabled it to remove the default VPC because `auto_create_network=false`. | No Compute resources exist. API enablement itself has no standalone fee. | Leave enabled for recovery. In S1H, evaluate organization policy `compute.skipDefaultNetworkCreation` for future projects and test any disable separately. Do not add it to staging's required-service set merely to hide the side effect. |
| `oslogin.googleapis.com` | **B — Compute dependency.** The Service Usage audit response enabled it with Compute. | No VM/OS Login use. No standalone enablement fee. | Leave unmanaged with its dependency; assess only together with Compute in S1H. |
| `containerregistry.googleapis.com` | **B — service dependency.** It appeared in the batch-enable response but not Terraform's requested service IDs. | No Container Registry images; Artifact Registry is the product store. No standalone enablement fee. | Do not disable while Cloud Run/service dependencies are unresolved. Recheck dependency metadata in S1H. |
| `pubsub.googleapis.com` | **B — service dependency.** It appeared in the batch-enable response but not Terraform's request. | No topics/subscriptions and no LIW application dependency. No standalone enablement fee. | Leave Google-managed; a later disable is allowed only if dependency validation succeeds and runtime design remains Pub/Sub-free. |
| `storage-api.googleapis.com` | **A — Google default project service.** It is listed in Google's default-enabled service inventory. | No staging buckets; enabled state alone has no fee. Stored data/operations would be billable. | Leave Google-managed rather than forcing an exact API set. |
| `storage-component.googleapis.com` | **A — Google default project service.** | No staging buckets; enabled state alone has no fee. | Leave Google-managed. |
| `telemetry.googleapis.com` | **A — Google default project service** and platform telemetry dependency. | Monitoring/Logging are required; enabled state alone has no fee. | Leave Google-managed. |

No observed extra service is classified **C — confirmed unnecessary optional** at this checkpoint. Google's [default-enabled services](https://cloud.google.com/service-usage/docs/enabled-service), [service dependency model](https://cloud.google.com/service-usage/docs/hierarchical-service-activation/overview), and [disable cautions](https://cloud.google.com/service-usage/docs/enable-disable) support preserving dependencies until their consumers and deletion semantics are proven.

## Default Compute service account and creator access

Google created `320916405985-compute@developer.gserviceaccount.com` when Compute was enabled. Read-only verification found it enabled, with **zero direct project IAM roles** and **zero user-managed keys**. No Line Item Watch Terraform resource references it; all planned workloads use the four explicit accounts above. Leaving this inert account is safer and simpler than deletion during recovery. Reassess only if a policy or concrete threat model requires it; deleting a default account can break workloads that depend on it. See Google's [default Compute service account guidance](https://cloud.google.com/compute/docs/access/service-accounts#default_service_account).

Google automatically granted project creator `ud.management.co@gmail.com` the direct basic role `roles/owner`. This is expected bootstrap behavior, but it is mandatory pre-production hardening. Do not remove it until a durable UDM-controlled admin/recovery identity (preferably a second identity or group), tested Terraform/operator access, and an emergency access procedure exist. Then grant only the required roles, prove recovery and policy administration, remove the direct Owner grant through a separately reviewed IAM change, and verify there is no lockout. Google documents that [project creators receive Owner](https://cloud.google.com/resource-manager/docs/access-control-proj#default_roles).

## S1H bootstrap hardening recommendation

S1R-A and S1R-B reached `NO CHANGES` on 2026-10-03. Before production, authorize a separate **S1H BOOTSTRAP HARDENING** review. It should:

1. re-read service dependency metadata and audit logs;
2. confirm no VMs, default network, Container Registry artifacts, Pub/Sub resources, or default-account consumers exist;
3. decide whether organization-level default-network prevention should replace provider cleanup for future projects;
4. validate any proposed API disable without bypassing dependency checks and review its resource-deletion effects;
5. establish the durable UDM admin/recovery path and least-privilege Terraform/operator permissions;
6. remove creator Owner only after access tests and a rollback path; and
7. finish with a reviewed Terraform/IAM inventory and `NO CHANGES` where Terraform owns the resource.

S1H was not authorized by the recovery plan, remains deferred, and must not be folded into S2 or an ordinary park/unpark operation.
