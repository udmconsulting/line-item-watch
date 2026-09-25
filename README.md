# Line Item Watch

Line Item Watch is the first Product Module of a planned modular B2B SaaS. Its initial purpose is to give HubSpot users a useful audit history for Deal Line Item changes: what changed, old and new values, when, who where available, and creation/removal behavior.

The repository contains the accepted HubSpot feasibility probes and a production backend foundation. The backend now provides internal Tenant and Platform Connection identity, module entitlements, HubSpot OAuth installation, encrypted refresh-credential lifecycle, on-demand token refresh, an internal uninstall capability, and the first `LINE_ITEM_WATCH` business slice: explicit one-Deal baseline synchronization with immutable baseline and replaceable latest snapshots. Webhooks, audit reconstruction, scheduled reconciliation, and Line Item Watch UI are not implemented yet.

## Backend foundation

The single Maven module under `backend/` uses Java 25, Spring Boot 4.1.1, Spring MVC, PostgreSQL 18.6, Liquibase, Spring Data JPA, Testcontainers, and ArchUnit. From the repository root:

```sh
docker compose up -d postgres
cd backend
cp config/application-local.example.yaml config/application-local.yaml
chmod 600 config/application-local.yaml
# Replace the placeholders in the ignored local file, then run:
SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run
./mvnw verify
```

The tracked local profile contains only explicit non-production database credentials. Durable developer-machine secrets belong in the ignored `backend/config/application-local.yaml`; its tracked example contains placeholders only. Production secrets remain external runtime configuration through the existing environment variables. Begin a local install at `GET /integrations/hubspot/oauth/install`; see [Local development](docs/development/local-development.md).

## Project documentation

- [Product overview](docs/business/product-overview.md)
- [Private Beta scope](docs/business/beta-v1.md)
- [Product Module model](docs/business/modules.md)
- [Architecture overview](docs/architecture/system-overview.md)
- [Modularity](docs/architecture/modularity.md)
- [Provider integration](docs/architecture/provider-integration.md)
- [Local development](docs/development/local-development.md)
- [Adding a Product Module or provider](docs/development/adding-a-module.md)
- [Security overview](docs/trust/security-overview.md)
- [Data processing inventory](docs/trust/data-processing.md)
- [Legal/trust readiness](docs/trust/legal-readiness.md)
- [Agent execution contract](AGENTS.md)

## Technical feasibility evidence

The accepted spike against HubSpot platform `2026.09` established:

- **Gate 1 — PASS:** current Line Item properties and Deal associations are readable.
- **Gate 2 — PASS:** `propertiesWithHistory` deterministically reconstructed quantity `10 -> 8` and discount `10 -> 20`.
- **Gate 3 — PASS:** webhooks reported Line Item property changes, creation, deletion, and association creation/removal.
- **Deletion — MODEL B:** the deleted Line Item could not be read normally or with `archived=true`; the product must retain an initial baseline and latest known snapshot for deletion audit.

Evidence and read-only probe code:

- [`spike/read-line-items.mjs`](spike/read-line-items.mjs)
- [`spike/read-line-item-history.mjs`](spike/read-line-item-history.mjs)
- [`spike/read-deleted-line-item.mjs`](spike/read-deleted-line-item.mjs)
- [`spike/evidence/line-item-watch-webhooks.example.json`](spike/evidence/line-item-watch-webhooks.example.json)

These probes make live HubSpot API reads and are not routine documentation checks. They require scoped environment-provided credentials and approved test identifiers. Never commit or print token values, and do not rerun live/destructive spike work as part of P.0.

## Architecture in one sentence

Start with a modular monolith: shared Platform Core capabilities, independently owned Product Modules, focused external-provider adapters, explicit tenant/connection provenance, and simple infrastructure until measured needs justify separation.
