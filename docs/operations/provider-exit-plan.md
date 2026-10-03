# Provider exit and product decommission plan

This runbook separates three operations with different risk and authorization. It does not authorize any external change.

## A. Park / temporary shutdown

Parking reduces active compute while retaining the environment, database data/storage, backups/PITR, secrets, artifacts, Terraform state, audit evidence, and recovery path. Use the declarative controls in [environment lifecycle](environment-lifecycle.md). Do not delete resources, remove protection, revoke recovery access, or remove the state prefix. A park can be reversed by a reviewed unpark plan.

## B. Provider migration

### Portability boundary

Portable components are the OCI application image, Java/Spring Boot runtime, PostgreSQL 18 plus `pg_trgm`, Liquibase changesets, environment/configuration contract, JDBC behavior, business/application layers, and provider-neutral operational semantics.

GCP-specific components that require replacement or an adapter are Cloud Run service/jobs, Cloud SQL Connector, Secret Manager version references, Artifact Registry, Cloud Monitoring/Logging export and alerts, production load balancer/TLS/DNS resources, Cloud Scheduler/synthetic job plumbing, evidence storage, WIF delivery, and the GCS Terraform backend. Provider-specific infrastructure must not leak into the business/domain layers.

### Migration sequence

1. Approve the target-provider architecture, security/tenant boundaries, availability, cost, RPO/RTO, observability, and rollback criteria.
2. Build isolated target infrastructure and a separate protected state backend; do not repoint the GCP state.
3. Provision target PostgreSQL 18 with `pg_trgm`, backups, PITR-equivalent controls, encryption, network isolation, and deletion safeguards.
4. Transfer secrets through an approved secure channel and rotate provider/application credentials where possible; never copy Terraform state or expose payloads in plans/logs.
5. Replicate the accepted OCI digest into the target registry and deploy it with equivalent service, migration, and operator identities/configuration.
6. Run Liquibase status/schema verification against the target without accepting drift or editing changelog tables.
7. Create a portable PostgreSQL export, verify checksums/access controls, import it, and retain the source recovery point.
8. Validate row counts, constraints, tenant/connection provenance, timestamps, audit chains, `pg_trgm`, Liquibase state, and representative deterministic reconstruction.
9. Start the target application read-only or with processing disabled; verify health/readiness, signed API behavior, OAuth/webhook configuration without accepting live writes.
10. Validate workers, leases/idempotency, reconciliation, replay/rebuild, metrics, and operator procedures under controlled synthetic data.
11. Run the staging-equivalent Playwright customer journey against an isolated fixture.
12. Accept deterministic monitoring, alert routing, synthetic cadence/evidence retention, backups, and restore.
13. Freeze or coordinate writes, take the final delta/export, update provider callback/webhook settings and DNS/traffic, and verify TLS, signatures, tenant routing, and no dual processing.
14. Observe both platforms through an agreed window; compare application health, database consistency, backlogs, provider events, cost, and audit evidence.
15. Stop old GCP runtime/workers only after target acceptance; keep source DB/state/backups recoverable.
16. Hold the old environment for the approved rollback/retention window.
17. After explicit exit acceptance, use the complete GCP decommission sequence below.

Zero downtime is not automatic. The approved plan must define the write-freeze or replication mechanism, maximum data-loss window, DNS/TTL and external callback timing, duplicate webhook/worker prevention, success criteria, decision owner, rollback deadline, and how to reverse traffic/configuration while the GCP source remains intact.

## C. Complete product/environment decommission

`terraform destroy` is not the first step. Complete the following controlled sequence:

1. Obtain product/environment closure authority; production requires explicit production decommission approval.
2. Stop new installations and communicate the customer/service closure process.
3. Freeze delivery and stop application writes, workers, reconciliation, migrations, and operator executions in a controlled order.
4. Disable external integrations: HubSpot installs/webhooks/redirects/app configuration, GitHub delivery/environment trust, DNS/traffic, notification routes, and cross-project access. Preserve evidence of each change.
5. Take final database backup and portable PostgreSQL export; verify integrity and perform the required restore test.
6. Capture final inventory, Terraform plan/state references, artifact digests, audit/activity/log evidence, retention decisions, customer deletion/export evidence, and legal/contractual holds.
7. Revoke or retain secrets/keys according to the approved recovery and evidence policy. Record that key deletion can make retained ciphertext unrecoverable.
8. Shut down runtime and jobs; stop Cloud SQL only after the final export/backup is verified.
9. Hold the parked environment for the approved rollback, customer, incident, legal, backup, and audit retention window.
10. Obtain a second explicit decommission authorization after the retention window.
11. Make a guardrail-only Terraform change that deliberately removes relevant `prevent_destroy` rules and changes provider deletion protection/policy. Review and apply only that transition.
12. Produce a complete destroy plan, review every address and external dependency, prove the shared bootstrap project/bucket and the other environment are absent, then obtain destroy approval.
13. Apply the reviewed environment destroy; verify child-resource cleanup and project emptiness/deletion evidence.
14. Decide whether to archive or later remove the now-unused environment state prefix. Preserve final state, plans, apply logs, exports, restore proof, and deletion evidence for the approved retention period.

The shared organization, billing relationship, `udm-liw-bootstrap-02`, state bucket, and billing budget guardrail survive an individual environment decommission. Remove shared bootstrap only after staging and production are both gone, no retained environment needs state, retention is satisfied, and a separately reviewed shared-bootstrap decommission is explicitly approved.

Never use console project deletion, `terraform state rm`, `state mv`, import, force-unlock, or deletion-protection bypass as a shortcut.

## Disaster/emergency exit

For a cost spike, compromised credential, runaway service, broken deployment, provider outage, or access incident, contain reversibly first: reduce or stop compute, disable the delivery path, route to a known-good revision, revoke/rotate the compromised credential, suspend processing that amplifies damage, and preserve database/state/log/audit/forensic evidence. Delete only when incident leadership and the decommission authority conclude that retention and recovery are no longer required.
