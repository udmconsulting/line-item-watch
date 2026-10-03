# Developer tooling

This is the canonical prerequisite and machine-migration guide for Line Item Watch. When a project-wide required tool or version changes, update this file in the same change.

## Required tools and version sources

| Tool | Requirement | Repository source of truth |
|---|---|---|
| Git | Current supported release | Source control and CI checkout |
| OpenSSH / `ssh` | Current macOS-supported release | GitHub SSH authentication |
| Java | Major version 25 | `backend/pom.xml`, Java CI, and the runtime Dockerfile |
| Maven | 3.9.16 via `./backend/mvnw` | `backend/.mvn/wrapper/maven-wrapper.properties`; do not require a global Maven install |
| Node.js | 20.17.0 | Root `.nvmrc`; frontend engines allow `>=20.17.0 <21`; CI reads the same file |
| npm | 10.8.2, bundled with Node.js 20.17.0 | npm lockfile-version 3 files in `src/app/cards` and `assurance`; do not regenerate them with an incompatible npm |
| HubSpot CLI / `hs` | `@hubspot/cli` 8.15.0 | CI global-install pin |
| Google Cloud CLI / `gcloud` | Current supported official release | Local GCP authentication and operations; CI uses `google-github-actions/setup-gcloud@v3` |
| Terraform | 1.13.5 | Root/module `required_version` and CI setup pin |
| Google Terraform provider | 8.5.0 | Terraform constraints and both environment lockfiles |
| Docker with Compose | Current supported Docker Desktop | Local PostgreSQL, Testcontainers, OCI verification, Playwright image |
| `curl` | Current supported release | Local health/readiness checks |

## Recommended operational tools

| Tool | Use / version source |
|---|---|
| GitHub CLI / `gh` | Protected GitHub authentication and workflow inspection; no repository version pin |
| SDKMAN | Convenient Java 25 installation/switching; optional |
| `psql` client | PostgreSQL 18-compatible administration and role bootstrap; the server is not installed locally |
| `jq` | JSON verification and safe command-line inspection |
| `openssl` | Local cryptographic inspection/generation under the documented secret-handling rules |
| Gitleaks | Secret scanning; CI pins container `v8.29.1` |
| actionlint | GitHub Actions validation; use a current release compatible with repository workflows |

## Project- and container-managed tools

- Playwright 1.63.0 and its matching Chromium are pinned by `assurance/package-lock.json`; the production runner uses the digest-pinned official Playwright image. Install through the assurance package, not a global Playwright.
- PostgreSQL server 18.6 is supplied by `compose.yaml`, Testcontainers, and the CI service smoke. A host PostgreSQL server is unnecessary.
- HubSpot card dependencies are isolated and locked under `src/app/cards`; install them with `npm ci` in that package.

## Explicitly not required

The current Cloud Run/Cloud SQL modular-monolith architecture does not require `kubectl`, Helm, GKE tooling, AWS CLI, or Azure CLI. Cloud SQL Auth Proxy is also not required: deployed Java uses the Cloud SQL connector and local development uses loopback PostgreSQL. Add any of these only after an accepted architecture change, then update this guide.

## Concise macOS setup

1. Install Xcode Command Line Tools (`xcode-select --install`) for Git and the system SSH toolchain. Install Homebrew if it is not already managed on the machine.
2. Install general tools as needed: `brew install git gh jq openssl libpq gitleaks actionlint`. Add Homebrew `libpq` to `PATH` if its keg-only `psql` is not visible.
3. Install a Temurin (or equivalent compatible) Java 25 distribution. SDKMAN is convenient: inspect `sdk list java`, install a listed Temurin 25 identifier, and select it. Verify the major version before running Maven.
4. Install Node 20.17.0 with an existing Node version manager or the official distribution. With `nvm`, run `nvm install` and `nvm use` from the repository root; `.nvmrc` supplies the version. A Node version manager is helpful, not a project runtime dependency.
5. Use the Maven Wrapper; do not install Maven separately.
6. Install Docker Desktop and enable its Compose integration.
7. Install Terraform **1.13.5** from HashiCorp's official release. A package-manager `latest` is unsuitable if it does not resolve to the repository pin.
8. Install the Google Cloud CLI from Google's official macOS archive/installer so its bundled Python and update behavior are unambiguous.
9. Install the pinned HubSpot CLI: `npm install --global @hubspot/cli@8.15.0`.

## Authentication is separate from installation

### GitHub

Create or securely migrate an SSH key, add the public key to the intended GitHub account, and keep host-specific options in `~/.ssh/config` only when needed. Verify with:

```sh
ssh -T git@github.com
gh auth login
gh auth status
```

`gh` authentication is optional unless a workflow requires it; Git-over-SSH does not depend on `gh`.

### HubSpot

Authenticate freshly with `hs account auth` and keep staging/development/production accounts explicitly named and separated. Inspect configured accounts before validation or upload. Environment-specific project profiles remain ignored unless deliberately reviewed as public configuration. Never copy HubSpot tokens, global CLI configuration, browser state, or synthetic-user cookies into the repository.

### Google Cloud

These are distinct credential stores:

- `gcloud auth login` authorizes the `gcloud` CLI as the human operator.
- `gcloud auth application-default login` creates user Application Default Credentials (ADC) used by Terraform's Google provider and GCS backend on a workstation.

Both can therefore be required. Authenticate freshly on a migrated machine; do not copy the old machine's entire gcloud configuration directory.

```sh
gcloud auth login
gcloud auth application-default login
gcloud auth list
gcloud organizations list
gcloud config configurations list
gcloud config set project udm-liw-bootstrap-02
```

The selected `gcloud` project is a CLI default, not authorization to mutate it. Local Terraform uses ADC. GitHub delivery uses Workload Identity Federation; static service-account JSON keys are not supported.

## New-machine verification

Check the toolchain before copying local configuration:

```sh
git --version
ssh -V
gh --version
java --version
./backend/mvnw --version
node --version
npm --version
hs --version
gcloud --version
terraform version
docker --version
docker compose version
curl --version
psql --version
jq --version
openssl version
gitleaks version
actionlint --version
```

Then run the safe project checks relevant to the machine:

```sh
./backend/mvnw -f backend/pom.xml verify
npm --prefix src/app/cards ci
npm --prefix src/app/cards run typecheck
npm --prefix src/app/cards run lint
npm --prefix src/app/cards test -- --run
hs project lint --install-missing-deps=false --no-color
terraform fmt -check -recursive infra
terraform -chdir=infra/staging init -backend=false -reconfigure
terraform -chdir=infra/staging validate
terraform -chdir=infra/production init -backend=false -reconfigure
terraform -chdir=infra/production validate
npm --prefix assurance ci
npm --prefix assurance run typecheck
npm --prefix assurance run test:config
npm --prefix assurance test
```

Backend integration tests require a running Docker daemon. Playwright requires its package-matched Chromium (`npm --prefix assurance exec -- playwright install chromium`) when it is not already available. The backend-disabled Terraform commands do not read or write live remote state.

## Machine migration safety

Carefully migrate only material you understand: reviewed `~/.ssh` keys/configuration, Git configuration, IDE preferences, non-secret shell preferences, and intentionally retained ignored project-local configuration. Recreate permissions after transfer.

Prefer fresh authentication for gcloud/ADC, GitHub CLI, HubSpot CLI, browser synthetic state, Docker registries, and all cloud sessions. Do not blindly copy expired auth caches, plaintext secrets, browser cookies, old staging/production credentials, or Docker credential stores.

Ignored files such as `backend/config/application-local.yaml` and `src/hsprofile.*.json` are not Git-backed. If still needed, migrate them separately through an approved secure channel, inspect them, preserve restrictive permissions, and rotate/recreate credentials when their provenance or lifetime is uncertain.
