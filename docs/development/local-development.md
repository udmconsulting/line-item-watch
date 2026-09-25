# Local development

## Repository status

The repository contains HubSpot project metadata, read-only feasibility probes, and one backend Maven module under `backend/`. The backend implements Platform Core identity/entitlements, HubSpot OAuth installation, encrypted refresh credentials, on-demand refresh, internal uninstall, and the internal one-Deal `LINE_ITEM_WATCH` baseline use case. It exposes install/callback HTTP endpoints but no public baseline, business administration, or disconnect endpoint.

## Prerequisites

- JDK 25
- Docker with Docker Compose

Maven 3.9.16 is supplied by `backend/mvnw`; a separately installed Maven is not required.

## Backend database and application

From the repository root, start the local-only PostgreSQL 18.6 service:

```sh
docker compose up -d postgres
docker compose ps
```

The Compose database publishes PostgreSQL only on the host loopback interface at `127.0.0.1:5433`; the tracked classpath `application-local.yml` uses the same port and matching, explicitly non-production credentials. Local launches run from `backend/`, so Spring Boot automatically loads the external profile-specific file `backend/config/application-local.yaml` when the `local` profile is active.

```sh
cd backend
cp config/application-local.example.yaml config/application-local.yaml
chmod 600 config/application-local.yaml
# Replace every placeholder in the ignored local file before OAuth operations.
SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run
```

The real local file is Git-ignored and must remain readable only by its developer (`chmod 600`). Its tracked sibling, `application-local.example.yaml`, contains only invalid placeholders and non-secret defaults. Environment variables remain supported and are the production configuration mechanism.

The local credential-encryption key must survive for as long as any local database credential encrypted with that key exists. Losing or changing the key requires controlled local credential re-establishment. Generating a different key under an existing key ID is invalid because the key ID identifies the encryption material used for the persisted ciphertext. Do not rely on ephemeral shell-only keys when local encrypted credentials outlive the shell session.

Begin installation at `http://localhost:8080/integrations/hubspot/oauth/install`. The HubSpot app configuration must contain the matching callback URL. `HUBSPOT_API_BASE_URL` and `HUBSPOT_AUTHORIZATION_BASE_URL` are optional test overrides; HTTPS is mandatory except for explicit localhost/loopback development endpoints. `HUBSPOT_CONNECT_TIMEOUT` and `HUBSPOT_READ_TIMEOUT` optionally override the bounded `5s` and `20s` defaults. Without the local profile, database variables are also mandatory. Never commit, print, or add local secrets to shell startup files.

Production secrets must remain external runtime configuration. Select a managed mechanism such as Vault, Azure Key Vault, Kubernetes Secrets, or an equivalent appropriate to the eventual hosting platform; the production choice remains TBD and production secret values must never be stored in repository configuration.

Run the complete backend verification suite with Docker available:

```sh
cd backend
./mvnw verify
```

The suite starts PostgreSQL 18.6 through Testcontainers, applies Liquibase migrations, validates with Hibernate, exercises OAuth/credential concurrency, baseline/latest persistence and isolation, provider HTTP contracts, MVC behavior, and ArchUnit rules. It does not call live HubSpot or use the Compose database. The guarded P.3 live baseline harness and its exact invocation are documented in [Testing](testing.md); do not run it without separate live-provider authorization.

## Verified commands

Run from the repository root:

```sh
# Validate every tracked JSON document.
for file in $(git ls-files '*.json'); do jq empty "$file"; done

# Check documentation and source edits for whitespace errors.
git diff --check
```

The feasibility probes and their required environment variables are documented under [technical feasibility evidence](../../README.md#technical-feasibility-evidence). They made live provider reads during the accepted spike and are not routine P.0 checks. Do not run them without an approved test account and explicit reason; never store or print the access token.

The HubSpot app component is configured under `src/app`, but the HubSpot CLI workflow has not been locally verified in this repository. Add commands here only after testing them. HubSpot project validation/upload/deployment must never be conflated: validation may be used when available and non-destructive; upload or deployment requires explicit authorization.

## Required reading before implementation

- [Business overview](../business/product-overview.md)
- [Private Beta scope](../business/beta-v1.md)
- [Architecture overview](../architecture/system-overview.md)
- [Coding guidelines](coding-guidelines.md)
- [Testing](testing.md)
- [Security overview](../trust/security-overview.md)
- repository root [`AGENTS.md`](../../AGENTS.md)

Production deployment and operations commands remain TBD.
