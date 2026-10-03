# Canonical automation guide

`./scripts/liw` and the public GitHub workflows are the supported job interface for Line Item Watch. Humans and coding agents should invoke these entry points instead of reconstructing their internal command sequences. The scripts are noninteractive, validate closed inputs before work, preserve command failures, and never print secret values.

## Job matrix

| Task | Local command | GitHub workflow | Required inputs | External mutation | Approval required |
|---|---|---|---|---|---|
| Full verification | `./scripts/liw verify all` | `CI` | Installed tools; local Docker | No | No |
| Scoped verification | `./scripts/liw verify <scope>` | CI jobs | `backend`, `frontend`, `infra`, `assurance`, `artifacts`, or `automation` | No | No |
| Build both artifacts | `./scripts/liw artifact build --revision <sha>` | `Deliver staging` | Full source SHA; optional local tags | No external mutation | No locally; protected `staging` approval in GitHub |
| Inspect both artifacts | `./scripts/liw artifact inspect --revision <sha>` | Reusable staging/production workflows | Full source SHA; image references | No | No locally; part of delivery approval in GitHub |
| Build/inspect/scan artifacts | `./scripts/liw artifact verify --revision <sha>` | CI container/security jobs | Docker, Trivy, full source SHA | No | No |
| Validate Terraform | `./scripts/liw infra validate [target]` | CI `infrastructure` | Terraform 1.13.5 | Provider downloads only | No |
| Plan infrastructure | `./scripts/liw infra plan <env> --out <file> [--var-file <file>]` | None | ADC/backend access and root inputs | No resources; remote lock/state read | Plan/read authorization |
| Inspect staging | `./scripts/liw env status staging` | None | ADC/backend and GCP read access | No | Read authorization |
| Plan park/unpark | `./scripts/liw env plan-park staging ...` / `plan-unpark` | None | Same as staging plan | No resources; remote lock/state read | Plan/read authorization |
| Park/unpark staging | `./scripts/liw env park staging ...` / `unpark` | None | Previously reviewed saved plan, inputs for post-plan, exact confirmation token | **Yes: Terraform apply** | **Separate infrastructure approval** |
| Publish initial artifacts | None | `Deliver staging` with artifact-only mode | Four staging variables; full main SHA | **Yes: pushes two images** | **Protected `staging` Environment** |
| Deploy staging | None | `Deliver staging` with full mode | Runtime variables/secrets plus full main SHA | **Yes: push, migration, service update, live test** | **Protected `staging` Environment** |
| Promote production | None | `Promote production` | Accepted revision and two accepted digests | **Yes: copy, migrate, deploy, traffic, synthetic** | **Protected `production` Environment** |
| Roll back | No generic command | Production workflow performs automatic traffic rollback on verification failure | Previous revision captured by workflow | **Yes when triggered** | Existing production approval |

Run `./scripts/liw help` for the current command syntax and exit-code contract.

## Verification commands

### Full verification

Purpose and use: `./scripts/liw verify all` is the standard pre-checkpoint command. It runs backend verification; frontend install, tests, typecheck, lint, and formatting; Terraform format/contracts/module tests/root validation; deterministic and visual assurance; automation contracts; Actionlint; HubSpot lint; both container builds/inspection/scans; Java/npm dependency audits; Gitleaks; and `git diff --check`.

Parameters: none. Prerequisites are the pinned Java/Node/Terraform toolchain plus Docker, Trivy, Gitleaks, Actionlint, the HubSpot CLI, and network access for deterministic dependency/provider resolution. It creates only local dependency caches, test output, and container images. Any failed child command produces a nonzero exit and stops the sequence. Fix the reported owning scope, run that scoped verification, then rerun the full command before checkpointing.

```sh
./scripts/liw verify all
```

### Scoped verification

Purpose and use: `./scripts/liw verify <scope>` shortens repair loops while retaining the canonical command. Allowed scopes are `backend`, `frontend`, `infra`, `assurance`, `artifacts`, and `automation`; the omitted/default scope is `all`. `artifacts` uses the current `HEAD` as local candidate metadata and must not be treated as publication evidence from a dirty tree. `automation` runs command/plan/workflow contracts, Actionlint, and ShellCheck when it is installed.

External effects are local dependency downloads/build output only. An unknown scope or unavailable required tool exits 1. Re-run the failed scope after repair.

```sh
./scripts/liw verify infra
./scripts/liw verify automation
```

## Artifact commands

All artifact commands require `--revision` to match `^[0-9a-f]{40}$`. Defaults are `line-item-watch:<revision>` and `line-item-watch-assurance:<revision>`; override them with `--app-image` and `--assurance-image`. `latest` is rejected. Neither command authenticates to GCP or pushes.

### Build

`./scripts/liw artifact build` builds the application and assurance images together with a fixed creation time and matching OCI revision. Both images are required because acceptance and later production promotion record two digests. A Docker/build failure exits 1; repair the source/tooling and rerun.

```sh
./scripts/liw artifact build --revision "$(git rev-parse HEAD)"
```

### Inspect

`./scripts/liw artifact inspect` verifies both referenced images exist; revision and image-type labels; application role metadata `SERVICE,MIGRATE,OPERATOR`; non-root users; Java 25/minimal runtime content; absence of local YAML; and assurance fail-closed behavior with no target configuration. It accepts local tags or immutable digest references. A mismatched/missing artifact or violated boundary exits 1; never publish it.

```sh
./scripts/liw artifact inspect \
  --revision "$(git rev-parse HEAD)" \
  --app-image "line-item-watch:$(git rev-parse HEAD)" \
  --assurance-image "line-item-watch-assurance:$(git rev-parse HEAD)"
```

### Verify

`./scripts/liw artifact verify` performs build then inspection and scans both images with Trivy for fixable HIGH/CRITICAL OS vulnerabilities and embedded secrets. Java dependencies are covered by the backend dependency audit in full verification. It requires Docker and Trivy. Any finding exits nonzero; update/remediate and rebuild rather than waiving it inside the command.

## Terraform commands

### Validate

`./scripts/liw infra validate [all|staging|production]` requires exactly Terraform 1.13.5. Default `all` checks formatting, repository Terraform contracts, mocked module tests, and both roots with backends disabled. A root scope skips the module test and other root. It does not authenticate to or mutate GCP; `terraform init -backend=false` may download providers. Exit 1 means tooling/configuration/test failure.

```sh
./scripts/liw infra validate all
```

### Plan

`./scripts/liw infra plan <staging|production> --out <file.tfplan> [--var-file <ignored-file>]` initializes only the selected checked-in backend, requires the default workspace, validates, saves the plan, converts it to transient JSON, and classifies actual resource changes. Required Terraform values may come from an ignored restricted variable file and `TF_VAR_*`; the billing account ID must remain local and is never echoed by the wrapper.

The classifier reports add/change/destroy/replacement counts and every changed address. Replacement, environment-project deletion, unexpected destroy, shared bootstrap/state identifiers, or cross-environment values exit 2. Invalid plan JSON exits 3. Terraform/input/auth failures exit 1. No unsafe plan is applyable through the canonical lifecycle commands. Review the saved plan separately, then delete it when its evidence purpose is complete.

```sh
TF_VAR_billing_account_id='set-locally' \
  ./scripts/liw infra plan staging \
  --var-file "$PWD/infra/staging/staging.auto.tfvars" \
  --out /tmp/liw-staging-reviewed.tfplan
```

### Status

`./scripts/liw infra status <env>` and the staging-only alias `./scripts/liw env status staging` initialize the selected backend, list Terraform addresses/outputs, derive the project from the Artifact Registry output, and perform read-only `gcloud` project, Cloud SQL, Cloud Run service, and job queries. They do not accept credentials or project IDs on the command line. Missing state, ADC, permissions, or tools exits 1; repair authentication/access and rerun.

## Staging lifecycle commands

`plan-park` and `plan-unpark` have the same `--out`/optional `--var-file` contract as `infra plan`. They support only `staging` and always override `database_bootstrap_active=false`. Lifecycle classification permits updates only to the staging Cloud SQL instance and application service, permits create/delete only for the known conditional uptime/active-monitoring addresses in the appropriate direction, and rejects output changes, replacements, and every unrelated resource change. Unpark permits no destroys.

```sh
./scripts/liw env plan-park staging --var-file <ignored-file> --out /tmp/staging-park.tfplan
./scripts/liw env plan-unpark staging --var-file <ignored-file> --out /tmp/staging-unpark.tfplan
```

`park` and `unpark` are the only commands able to call `terraform apply`. They accept the exact previously reviewed saved plan from `plan-park`/`plan-unpark`, reclassify it, verify its lifecycle variable values, apply that same file, and require a fresh no-changes post-plan. They are noninteractive and require a separate authorization plus exact token:

```sh
./scripts/liw env park staging \
  --plan /tmp/staging-park.tfplan --var-file <ignored-file> \
  --confirm APPLY_STAGING_PARK

./scripts/liw env unpark staging \
  --plan /tmp/staging-unpark.tfplan --var-file <ignored-file> \
  --confirm APPLY_STAGING_UNPARK
```

These commands can mutate staging Cloud SQL, Cloud Run scaling, and conditional monitoring. They cannot target production and expose no generic apply/destroy/state/import/force-unlock path. On failure, do not regenerate/apply around the classifier: inspect the plan/state, preserve the lock failure context, and follow the drift/recovery runbook. Roll back an unintended lifecycle change only by reviewing the opposite declarative operation.

## GitHub Actions

### CI

`CI` runs for pull requests and pushes to `main`; it is read-only apart from GitHub test artifacts and runner-local builds. It has only `contents: read`. Jobs cover backend, frontend, HubSpot lint, UI assurance, runtime-role container smoke, Terraform/automation contracts, and dependency/secret scanning. No environment approval or cloud credential exists. A failure blocks the checkpoint through branch protection; repair locally with the matching `./scripts/liw verify <scope>` command.

### Deliver staging

The public `Deliver staging` workflow is manual-only and delegates to `_deliver-staging.yml` (`workflow_call`). It requires the protected GitHub Environment `staging`, OIDC `id-token: write`, and these typed inputs:

| Input | Allowed contract |
|---|---|
| `revision` | Required full lowercase Git SHA; must equal the selected `main` workflow SHA |
| `artifact_only` | Boolean |
| `run_migrations` | Boolean |
| `verify` | Boolean |

Allowed modes are deliberately closed:

- initial publication: `artifact_only=true`, `run_migrations=false`, `verify=false`;
- full staging delivery: `artifact_only=false`, `run_migrations=true`, `verify=true`.

All other combinations fail before authentication or image build. The reusable job verifies `refs/heads/main`, all required variables, both images, and resolved sha256 digests. Artifact-only mode pushes exactly the two Git-SHA-tagged images and stops. Full mode additionally updates/executes the migration job, deploys the same application digest, checks readiness, and runs the protected real HubSpot journey. Failed live verification retains only the configured sanitized evidence. Recovery is rerun from the same main revision after correcting configuration; never bypass the environment gate or WIF.

### Promote production

The public manual-only `Promote production` workflow delegates to `_promote-production.yml` and requires protected Environment `production`. Inputs are `revision` (the full source SHA recorded at staging acceptance), `staging_digest`, `staging_synthetic_digest`, and booleans `run_migrations=true`, `verify=true`. Both digests must be `sha256:` plus 64 lowercase hex characters. The selected workflow ref must be `main`; the accepted revision may be older than current main and is checked against both pulled image labels.

The workflow copies the exact accepted manifests to production (release tags are navigation only), checks digest preservation, runs migration, creates a no-traffic revision, shifts traffic, verifies readiness, and runs the read-only production synthetic. Readiness or synthetic failure restores traffic to the previous revision. It never rebuilds either artifact. Production is not provisioned today, so this workflow is a future protected entry point, not authorization to create production.

## GitHub Environment prerequisite

The `staging` GitHub Environment is required but its existence is currently **UNKNOWN**. Do not run delivery until a separately authorized GitHub change verifies/creates it, limits deployment branches to `main`, configures reviewers and self-review policy, and supplies these non-secret values:

- `GCP_STAGING_PROJECT_ID`
- `GCP_STAGING_WIF_PROVIDER`
- `GCP_STAGING_DEPLOYER_SERVICE_ACCOUNT`
- `GCP_STAGING_ARTIFACT_REPOSITORY`

Full delivery later also requires the documented runtime variables and protected assurance values. No static GCP service-account JSON is supported. Missing values fail clearly in preflight; WIF independently requires repository `udmconsulting/line-item-watch`, Environment `staging`, and `refs/heads/main`.

## Release and branch model

Feature branches are for implementation, review, and local/CI verification. They cannot authenticate to environment WIF. An accepted P.9 checkpoint may merge to `main` before all of P.9 is complete; this is how staging can exercise the exact trunk revision while production remains protected, manual-only, and unprovisioned. Merging does not publish or deploy because external delivery workflows are manual-only.

The immutable chain is:

```text
accepted main source -> one application + assurance build -> recorded staging digests
  -> staging migration/deploy/acceptance -> same two manifests copied to production
```

Tags aid navigation. Digests are deployment, acceptance, promotion, and rollback identity. `SERVICE`, `MIGRATE`, and `OPERATOR` always use the same application digest.

## Procedures intentionally kept manual

- **GitHub Environment creation/protection:** changes repository security policy and reviewer authority; it needs a separate GitHub mutation approval.
- **Terraform project/foundation/runtime apply:** only staging park/unpark has a sufficiently narrow automated apply boundary. Other plans can create or destroy broad infrastructure and retain separate reviewed-plan approval.
- **Secret payload/version injection:** payloads must never enter Terraform, command output, repository files, or coding-agent context.
- **Database role bootstrap:** privileged SQL and credentials require a separately controlled operator session and evidence.
- **Rollback selection:** choosing an older digest is deterministic, but schema compatibility and customer-data impact are release-specific. Production workflow automatically restores traffic on verification failure; deliberate rollback still requires incident/change review of the selected accepted digest and migration compatibility.
- **HubSpot, DNS, and production provisioning:** these cross external trust/availability boundaries and remain separately authorized phases.

The implementation details remain in `scripts/liw`, its classifier/contracts, and reusable workflows. This guide defines when and how to invoke them; it does not duplicate every internal shell step.
