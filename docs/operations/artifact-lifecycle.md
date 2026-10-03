# OCI artifact lifecycle

## Artifact model and canonical names

Line Item Watch publishes two OCI images to the Terraform output `artifact_registry_repository`. Staging currently resolves that output to `europe-west1-docker.pkg.dev/udm-liw-staging-100724/liw-containers`.

| Image | Canonical staging name | Purpose |
|---|---|---|
| Application | `europe-west1-docker.pkg.dev/udm-liw-staging-100724/liw-containers/line-item-watch` | One Java 25 artifact selected at runtime as `SERVICE`, `MIGRATE`, or `OPERATOR` |
| Assurance | `europe-west1-docker.pkg.dev/udm-liw-staging-100724/liw-containers/line-item-watch-assurance` | Playwright/Chromium production synthetic runner; deterministic and visual harnesses remain repository/CI checks |

Do not split the application roles into separate images. The image, dependencies, and schema-aware code are identical; `APPLICATION_RUNTIME_ROLE` selects the bounded process behavior. The assurance image is separate because its browser runtime and security boundary are different.

## Build identity and immutable deployment

Both Dockerfiles emit OCI source, revision, version, creation-time, and image-type labels. CI passes the exact checked-out `GITHUB_SHA`; a dirty local build must use an explicitly non-release identity and must never be published as if it represented a clean commit.

The canonical discovery tag for both images is the full lowercase Git SHA:

```text
line-item-watch:<git-sha>
line-item-watch-assurance:<git-sha>
```

Tags support human navigation only. Terraform, Cloud Run services/jobs, release evidence, rollback records, and production promotion use the resolved immutable references:

```text
<repository>/line-item-watch@sha256:<digest>
<repository>/line-item-watch-assurance@sha256:<digest>
```

Never use `latest` as a build, deployment, or rollback contract. The application digest selected in staging is copied to the production repository without rebuilding; the assurance digest follows the same rule.

## Initial staging publication sequence

The staging WIF provider requires all three claims: repository `udmconsulting/line-item-watch`, GitHub Environment `staging`, and ref `refs/heads/main`. A feature branch cannot authenticate and is not an allowed bootstrap exception.

1. Review the S2 repository delta without committing or publishing from the review task. Use the canonical artifact commands in the [automation guide](automation-guide.md).
2. Approve and create a separate S2 checkpoint commit, then push the feature branch without publishing an image.
3. Before merging the delivery workflow to `main`, verify or create the GitHub Environment `staging` as a separately authorized GitHub mutation. This ordering prevents a first manual run from implicitly creating an unprotected environment. Restrict deployment branches to `main`, require the approved reviewer set, prevent self-review where supported, and add only the four non-secret environment variables needed for artifact-only publication:
   - `GCP_STAGING_PROJECT_ID`
   - `GCP_STAGING_WIF_PROVIDER`
   - `GCP_STAGING_DEPLOYER_SERVICE_ACCOUNT`
   - `GCP_STAGING_ARTIFACT_REPOSITORY`
4. Merge the accepted S2 checkpoint to `main` through the normal protected path. P.9 may use accepted trunk checkpoints for staging exercise before the overall phase is complete; production remains unprovisioned and separately protected. Merge alone performs no delivery because `Deliver staging` is manual-only.
5. From that exact merged `main` commit, manually dispatch `Deliver staging` with its full revision SHA, `artifact_only=true`, `run_migrations=false`, and `verify=false` under a separate artifact-publication approval.
6. Verify that exactly the two Git-SHA tags exist, resolve and record both digests, confirm OCI revision labels match the merged commit, and confirm no Cloud Run, Secret Manager, Cloud SQL, IAM, or Terraform resource changed.
7. Stop. Artifact bootstrap does not authorize runtime provisioning, migration execution, application deployment, HubSpot configuration, or live browser acceptance.

The remaining staging runtime variables and protected HubSpot assurance values are later delivery inputs, not S2 artifact-only inputs. No service-account JSON key is supported. A local authenticated push would bypass the WIF path that S2 needs to prove and is therefore not the recommended bootstrap.

## Retention and rollback

Artifact Registry keeps the 20 most recent versions, keeps `release-`, `rollback-`, and `incident-hold-` tags, and deletes only untagged versions older than 30 days. Git-SHA-tagged staging artifacts and protected production tags are therefore not eligible for the normal untagged deletion rule. Before deliberately removing a tag, verify that no Cloud Run service/job, rollback record, incident hold, or acceptance record references its digest.

Rollback selects a previously accepted digest; it never rebuilds old source. Apply a `rollback-` or `incident-hold-` tag when evidence must be retained beyond ordinary release navigation, then record the immutable digest in the incident/change record.

## Provenance and SBOM boundary

OCI labels and immutable digests are required for S2. The current plain Docker build/push workflow does not publish a signed provenance attestation or SBOM. BuildKit attestations, signing, and registry vulnerability scanning are later supply-chain hardening: add them only with an explicit trust/keyless-signing design and an acceptance check that production promotion preserves the attestation relationship. Their absence does not permit mutable-tag deployment.
