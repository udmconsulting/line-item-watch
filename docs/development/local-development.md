# Local development

## Repository status

The repository contains HubSpot project metadata, read-only feasibility probes, and one production backend Maven module under `backend/`. The backend implements the Platform Core Tenant, Platform Connection, Product Module identity, entitlement, and PostgreSQL persistence foundation. It does not expose HTTP endpoints or integrate with HubSpot.

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

The Compose database publishes PostgreSQL only on the host loopback interface at `127.0.0.1:5433`; `application-local.yml` uses the same port and matching, explicitly non-production credentials. No `.env` file is required. Start the foundation application with the local profile:

```sh
cd backend
SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run
```

Without the local profile, `DATABASE_URL`, `DATABASE_USERNAME`, and `DATABASE_PASSWORD` are mandatory. Do not place production values in repository files.

Run the complete backend verification suite with Docker available:

```sh
cd backend
./mvnw verify
```

The suite starts PostgreSQL 18.6 through Testcontainers, applies Liquibase migrations, validates the schema with Hibernate, runs persistence and isolation tests, and runs ArchUnit rules. It does not use the Compose database.

## Verified commands

Run from the repository root:

```sh
# Validate every tracked JSON document.
for file in $(git ls-files '*.json'); do jq empty "$file"; done

# Check documentation and source edits for whitespace errors.
git diff --check
```

The feasibility probes and their required environment variables are documented under [technical feasibility evidence](../../README.md#technical-feasibility-evidence). They made live provider reads during the accepted spike and are not routine P.0 checks. Do not run them without an approved test account and explicit reason; never store or print the access token.

The earlier template suggested HubSpot CLI development commands, but no production component or locally verified CLI workflow currently exists. Add commands here only after testing them in this repository. HubSpot project validation/upload/deployment must never be conflated: validation may be used when available and non-destructive; upload or deployment requires explicit authorization.

## Required reading before implementation

- [Business overview](../business/product-overview.md)
- [Private Beta scope](../business/beta-v1.md)
- [Architecture overview](../architecture/system-overview.md)
- [Coding guidelines](coding-guidelines.md)
- [Testing](testing.md)
- [Security overview](../trust/security-overview.md)
- repository root [`AGENTS.md`](../../AGENTS.md)

Production deployment and operations commands remain TBD.
