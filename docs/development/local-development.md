# Local development

## Repository status

The repository contains HubSpot project metadata, read-only feasibility probes, and one backend Maven module under `backend/`. The backend implements Platform Core identity/entitlements plus HubSpot OAuth installation, encrypted refresh credentials, on-demand refresh, and internal uninstall. It exposes install/callback HTTP endpoints but no business API or disconnect endpoint.

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

The Compose database publishes PostgreSQL only on the host loopback interface at `127.0.0.1:5433`; `application-local.yml` uses the same port and matching, explicitly non-production credentials. Supply OAuth and encryption configuration from the environment:

```sh
cd backend
SPRING_PROFILES_ACTIVE=local \
HUBSPOT_OAUTH_CLIENT_ID='your-development-client-id' \
HUBSPOT_OAUTH_CLIENT_SECRET='from-your-secret-store' \
HUBSPOT_OAUTH_REDIRECT_URI='http://localhost:8080/integrations/hubspot/oauth/callback' \
HUBSPOT_CREDENTIAL_KEY_ID='local-key-1' \
HUBSPOT_CREDENTIAL_ENCRYPTION_KEY='base64-encoded-32-byte-key' \
./mvnw spring-boot:run
```

Begin installation at `http://localhost:8080/integrations/hubspot/oauth/install`. The HubSpot app configuration must contain the matching callback URL. `HUBSPOT_API_BASE_URL` and `HUBSPOT_AUTHORIZATION_BASE_URL` are optional test overrides; HTTPS is mandatory except for explicit localhost/loopback development endpoints. `HUBSPOT_CONNECT_TIMEOUT` and `HUBSPOT_READ_TIMEOUT` optionally override the bounded `5s` and `20s` defaults. Without the local profile, database variables are also mandatory. Do not place any secret or generated key in repository files or shell history.

Run the complete backend verification suite with Docker available:

```sh
cd backend
./mvnw verify
```

The suite starts PostgreSQL 18.6 through Testcontainers, applies Liquibase migrations, validates with Hibernate, exercises OAuth/credential concurrency, persistence and isolation, provider HTTP contracts, MVC behavior, and ArchUnit rules. It does not call live HubSpot or use the Compose database.

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
