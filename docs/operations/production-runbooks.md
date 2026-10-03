# Production runbooks

These commands are templates. Set the project, region, service, job, and immutable image explicitly; inspect before changing external state. External actions require the normal authorization and production change process.

Canonical environment ownership/status is in [GCP environment inventory](gcp-environment-inventory.md), Terraform park/unpark/scaling/drift procedures are in [environment lifecycle](environment-lifecycle.md), and migration/full-decommission procedures are in [provider exit plan](provider-exit-plan.md).

## Production lifecycle transitions

Production lifecycle modes and their worker/cold-start consequences are defined in [production topology](../architecture/production-topology.md). Before accepting the first install, provision and verify the complete public environment; an OAuth callback cannot provision the infrastructure it depends on.

For an install-ready environment with no active customers, set `production_cloud_run_min_instances=0`, retain max one, review the saved production plan, apply through the protected infrastructure process, and verify OAuth/callback/API/webhook cold-start behavior plus database availability. Background workers are not continuous at min zero.

Before the first active customer, set the minimum to one, review/apply, verify an allocated ready instance, then check webhook ingestion, processing backlog/latency, reconciliation freshness, synthetic success, and alert delivery. Returning to min zero after all customers leave is allowed only while the database and install callback stay available and the business explicitly accepts cold starts and non-continuous workers. Never use `staging_parked` for production.

A future installation-driven workflow may read a trusted active installation/entitlement count on a schedule and drive only the bounded zero/one minimum through protected Terraform automation. It must emit an audit trail, reject unexpected values, fail safe to one, and never grant the business application infrastructure credentials.

## Deploy and roll back

1. Confirm staging accepted the exact `sha256:...`; dispatch `Promote production` with that digest and obtain protected-environment approval.
2. Confirm the migration job succeeded, the candidate revision became ready, traffic reached it, and `https://api.lineitemwatch.com/actuator/health/readiness` returns aggregate `UP`.
3. To roll back application traffic: `gcloud run services update-traffic SERVICE --project PROJECT --region europe-west1 --to-revisions PREVIOUS_REVISION=100`.
4. Do not automatically reverse a successful database migration. Use a forward fix unless a separately reviewed Liquibase rollback is proven safe.

## Failed migration

1. `gcloud run jobs executions list --job MIGRATION_JOB --project PROJECT --region europe-west1`.
2. Read sanitized job logs and Liquibase status; do not expose secret environment values.
3. Keep service traffic on the prior compatible revision. Fix the changeset or configuration in source, verify it, build/accept a new digest, and rerun the job.
4. Never clear Liquibase locks or edit changelog rows without evidence, peer review, and a backup/restore point.

## Cloud Run unavailable

1. Check public HTTPS, certificate, load-balancer backend, service/revision readiness, recent deployment, instance quota, and Cloud Run logs.
2. If a new revision caused it, route 100% to the previous ready revision.
3. If capacity is exhausted, evaluate a bounded max-instance/resource increase against the database connection budget before applying.

## Cloud SQL unavailable or connections exhausted

1. Check instance state, Cloud SQL availability/maintenance, CPU, memory, disk, connections, and application readiness.
2. For exhaustion, identify active versus idle sessions and the application pool demand; stop a runaway job/revision before raising limits.
3. Account for `SERVICE`, `MIGRATE`, and `OPERATOR` pools and future instance count. Keep total configured pools below the instance connection budget with administrative headroom.
4. For a zonal outage, declare the availability incident. Restore/PITR or an HA upgrade is a reviewed infrastructure action, not an application retry loop.

## HubSpot outage or rate limiting

1. Confirm provider status and bounded provider error/rate-limit metrics without logging customer/provider identifiers.
2. Let classified retry/backoff and durable leases operate; avoid bulk manual replay during an outage.
3. Disable or reduce reconciliation only through reviewed configuration if it protects foreground/webhook capacity. Resume and measure backlog age after recovery.

## Webhook degradation and worker backlog

1. Check public availability, valid webhook-ingestion failures, processing backlog/oldest age, active claims, exhausted signals, reconciliation age/result, and suspected gaps.
2. Verify the service is `SERVICE`, both workers are intentionally enabled, and database readiness is healthy.
3. Correct the upstream/configuration/database cause first. Scale within the measured connection budget if throughput is the cause.
4. Use the operator job for bounded inspect/requeue/replay/rebuild; never edit queue rows directly.

## Exhausted signal, reconciliation, replay, rebuild, or requeue

Run one explicit command by overriding the operator job arguments, then execute and inspect its sanitized result:

```bash
gcloud run jobs update OPERATOR_JOB --project PROJECT --region europe-west1 --args='COMMAND,...'
gcloud run jobs execute OPERATOR_JOB --project PROJECT --region europe-west1 --wait
```

Use the command syntax and compare-and-set guards in [reliability operations](../development/reliability-operations.md). Scope every action to the internal tenant/connection identifiers returned by an authorized inspection. Re-run inspection afterward and retain the Platform activity audit evidence.

## Credential secret/key rotation

1. Generate a new 32-byte key securely; add it as a new Secret Manager version without displaying it.
2. Configure the old active ID/key as previous and the new ID/key as active, both with explicit versions; deploy the same application logic.
3. Execute bounded `credential-key-rewrap` operator batches by tenant/connection scope until progress reaches zero candidates.
4. Run `credential-key-verify`; require zero previous-key rows and no unknown-key failures.
5. Retain the previous key until rollback and incident policy permit retirement. Then remove previous ID/key together and deploy. Never log plaintext or key material.

## Suspected secret compromise

Contain access, rotate the affected secret/version, inspect IAM/audit logs and application activity, revoke provider credentials where applicable, and preserve evidence. Do not destroy the prior version until incident leadership permits. Decide customer/regulatory notification through the incident process; do not include secret or customer material in tickets/chat.

## Backup/PITR restore

1. Record incident time, desired recovery point, source instance, and accepted data-loss window.
2. Restore to a new isolated instance/project target where possible; do not overwrite the source during diagnosis.
3. Validate schema/Liquibase state, `pg_trgm`, tenant isolation, representative counts, and service readiness with controlled credentials.
4. Promote the restored target only through a reviewed cutover; record actual RPO/RTO. Repository validation does not substitute for provisioning-time restore acceptance.

## Production incident

Assign incident lead, establish a privacy-safe timeline/correlation set, contain, preserve evidence, communicate status, recover through the smallest reversible change, verify health/backlogs, and schedule a blameless review with owned follow-ups. Customer communication follows contractual/legal review.

## Browser synthetic failure

1. Read the failure class before declaring product impact. `SYNTHETIC_AUTH_FAILURE` means renew the dedicated user's state; `SYNTHETIC_HARNESS_FAILURE` means inspect configuration/job/metric/evidence plumbing; `PRODUCT_FAILURE` means compare the card, API readiness, release digest, browser/network summary, and safe correlation IDs.
2. One result is WARNING. Page only after the product gauge remains failed across two scheduled observations or no success heartbeat arrives within the configured freshness window. Backend/platform alerts remain faster and can corroborate impact.
3. Rerun the Cloud Run Job once after checking HubSpot status and the dedicated fixture. Never test an arbitrary customer record and never “repair” the fixture by editing CRM during an incident.
4. The job retains only `safe-evidence.json`. Do not request or share storage state, raw browser traces, headers, URLs, or screenshots. If evidence is insufficient, reproduce with the staging fixture under approved access.
5. Resolve false positives by fixing accessible semantics, fixture contract, documented third-party console allowlist, or cadence/timeout. Do not weaken product assertions merely to silence an alert.

## Synthetic authentication-state maintenance

1. Sign in interactively as the dedicated environment user with normal MFA on a controlled workstation. Confirm its least-privilege role, English UI language, and synthetic-only account/Deal.
2. Capture Playwright storage state into a temporary restricted file, inspect that it contains only the intended `app.hubspot.com` state, then add a new environment-specific Secret Manager version without printing it.
3. Update the explicit version in Terraform, review the plan, execute one read-only smoke, and verify an emitted success heartbeat. Delete the temporary file securely according to workstation policy.
4. Never copy staging state to production, place state in GitHub PR secrets/artifacts, commit it, bypass MFA, or extend user privilege to avoid renewal.

## Synthetic fixture maintenance

Keep names and history synthetic, stable, and unique. Document intentional changes in the environment fixture JSON, preserve at least one searchable line item and one expected event, and verify Show history/pagination/refresh without mutation. If HubSpot UI semantics change, validate accessible locators in staging first. No production customer tenant may be substituted.

## Optional AI-assisted diagnosis

AI is never a gate, alert authority, or autonomous remediator. An operator may submit only the sanitized evidence JSON after confirming it has no sensitive values and the provider is approved. Do not send browser state, screenshots, traces, URLs, raw logs, customer fields, or credentials. Treat the result as a hypothesis and verify it against deterministic evidence before acting.

## Staging park/unpark and cost anomaly

Use the `staging_parked` Terraform variable exactly as documented in [deployment](../development/deployment.md), reviewing the plan before apply. A parked plan must set Cloud Run min zero and Cloud SQL `NEVER`, and remove active public uptime, database-availability, Cloud Run, and worker/reliability alerts. An unpark plan must restore all of them. This is declarative lifecycle state, not manual alert muting.

For a cost anomaly, inspect billing by project/service/SKU, Cloud Run instance time, SQL tier/storage/backup growth, logging volume, Artifact Registry retention, egress, and unexpected staging activity. Set or tune budget notifications; never respond by deleting production data or disabling required recovery controls.

The existing billing budget alert is a notification guardrail, not a spending cap. Preserve database recovery, state versioning/soft-delete, and required monitoring when reducing cost. Prefer delayed production creation, parked staging, min-zero install-ready production, bounded synthetic cadence/evidence, Artifact Registry cleanup, and low-cardinality logs before weakening security or recovery controls.

## Environment decommission

Follow the canonical controlled sequence in [provider exit plan](provider-exit-plan.md); the summary below does not replace its approval, export, retention, rollback, and shared-state checks.

Decommission is never a park action and never a routine `terraform destroy`. Open a separately authorized change; production requires explicit production decommission approval. Freeze delivery/writes, identify legal and contractual retention, export and restore-test required database data, retain backup/PITR and audit evidence, record secret/key recovery consequences, and capture the final inventory and cost baseline.

Remove or transfer external dependencies deliberately: DNS and certificates, HubSpot app redirect/webhook configuration and fixtures, GitHub Environment values/approvers, notification routes, and cross-project artifact access. Then use the two-review Terraform procedure in [deployment](../development/deployment.md): first a guardrail-only change removes `prevent_destroy` and changes the project deletion policy from `PREVENT` to `DELETE`; only a second separately reviewed plan may remove the environment and project. Console deletion and `terraform state rm` are prohibited shortcuts.

The bootstrap project and shared state bucket must survive. Preserve the final state, saved-plan/apply logs, exports, restore evidence, and deletion evidence for the approved retention period. Handling the now-empty environment state prefix is a later state-custodian decision; never delete the shared bucket as part of environment decommission.
