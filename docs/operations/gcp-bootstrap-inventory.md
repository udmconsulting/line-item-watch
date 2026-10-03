# GCP bootstrap inventory

Inventory recorded on 2026-10-02 from the completed manual bootstrap and reconciled with the 2026-10-03 staging checkpoint. This is the source of truth for shared infrastructure that exists independently of any Line Item Watch environment. Environment resources are inventoried in [GCP environment inventory](gcp-environment-inventory.md).

## Existing manual resources

| Item | Actual value / status |
|---|---|
| Organization | `ud-management-co-org` |
| Organization ID | `656511895628` |
| Primary bootstrap identity | `ud.management.co@gmail.com` |
| Bootstrap project | `UDM LIW Bootstrap` |
| Bootstrap project ID | `udm-liw-bootstrap-02` |
| Bootstrap project number | `1007247511793` |
| Parent | `organization/656511895628` |
| Billing | Enabled and linked; a billing-account budget alert is configured manually |
| Default regional location | `europe-west1` |
| Terraform state bucket | `gs://udm-liw-tfstate-1007247511793` |
| Terraform-managed staging project | `udm-liw-staging-100724` — **S1 FOUNDATION COMPLETE / PARKED** |
| Terraform-managed production project | **NOT CREATED** |

No billing-account identifier or payment information belongs in repository documentation.

## State-bucket controls

The bootstrap project owns the state bucket. It uses `STANDARD` storage in `europe-west1`, uniform bucket-level access, enforced public-access prevention, object versioning, and a seven-day soft-delete policy. Environment Terraform references this pre-existing bucket but must never manage or recreate it.

The bucket can hold both environment states because the roots use non-overlapping prefixes:

- staging: `line-item-watch/staging`
- production: `line-item-watch/production`

Terraform state is sensitive infrastructure metadata even when configuration deliberately keeps secret payloads out of it. Access remains limited to authorized Terraform operators and later approved automation identities. The application runtime needs no state-bucket access.

## Ownership boundary

The organization, billing account, bootstrap project, state bucket, and billing-level budget guardrail are the complete manual shared bootstrap. Keeping them external avoids a dependency cycle in which Terraform would need its own backend before it could create that backend. They also require organization/billing authority that application runtime and delivery identities must not receive.

Each environment root owns its environment project with `google_project`, including the organization parent, billing association, required labels, target-project APIs, and all environment resources. A staging or production project must not be created manually. The provider is deliberately configured without a default target project, so a plan can create the project before enabling its APIs without a provider/project dependency cycle.

The staging root now manages a complete, parked 58-address foundation in an isolated project under `organizations/656511895628`. The separately approved S1R-A/S1R-B recovery completed on 2026-10-03 and ended with a `NO CHANGES` plan as recorded in [environment lifecycle](environment-lifecycle.md). Production remains unprovisioned. Environment Terraform must not recreate or destroy any manual shared resource listed here.

## Abandoned project

`udm-liw-bootstrap-01` is in `DELETE_REQUESTED`. It was created under **No organization** when the Free Trial Console flow did not expose the standalone organization as a parent. Never restore or use it, never reuse its project ID, and allow Google to permanently delete it after the recovery period.

## Boundary

The manual bootstrap itself contains no Line Item Watch runtime, database, service, WIF trust, secret payload, DNS, HubSpot fixture, or customer data. The separately managed staging project contains its project/APIs/IAM, active GitHub WIF trust, an empty Artifact Registry repository, eight empty secret containers, and a stopped PostgreSQL foundation with the empty `line_item_watch` database. It contains no application runtime, image, secret payload, HubSpot configuration, or customer data. Shared bootstrap ownership and billing/state survival do not change when an environment is created, parked, recovered, migrated, or decommissioned.
