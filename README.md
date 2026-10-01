# Line Item Watch

Line Item Watch is the first Product Module of a planned modular B2B SaaS. Its initial purpose is to give HubSpot users a useful audit history for Deal Line Item changes: what changed, old and new values, when, who where available, and creation/removal behavior.

The repository contains the accepted HubSpot feasibility probes, a production backend foundation, and the first HubSpot Deal App Card. The backend provides internal Tenant and Platform Connection identity, module entitlements, HubSpot OAuth installation, encrypted refresh-credential lifecycle, on-demand token refresh, an internal uninstall capability, explicit one-Deal baseline synchronization, authenticated HubSpot webhook ingestion, deterministic PostgreSQL-backed processing of immutable Line Item signals into audit history and a derived latest projection, tracked-scope reconciliation, provider-free replay and recovery, policy-gated retention mechanics, and a disabled-by-default signed Deal-scoped audit read API. The Deal-only card renders that retained state and recent history with independent cursor pagination, reliability warnings, and English/Hungarian localization.

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

The backend also provides the P.6.B supportability foundation: server-owned request correlation, independent worker operation IDs, stable localization-neutral errors, privacy-aware structured logs, bounded Micrometer operation timers, and atomic Platform Connection/entitlement activity auditing. No telemetry vendor, dashboard, alert routing, translation bundle, or UI is added. See [observability](docs/architecture/observability.md) and [ADR 0009](docs/adr/0009-production-supportability-foundation.md).

Webhook receipt is disabled by default. Enabling it requires `HUBSPOT_WEBHOOK_ENABLED=true` and an exact externally reachable canonical HTTPS `HUBSPOT_WEBHOOK_PUBLIC_URI` ending in `/integrations/hubspot/webhooks`. Signal processing is independently opt-in with `LINE_ITEM_WATCH_PROCESSING_ENABLED=true`; it is disabled by default. The backend receiver, authentication, normalization, routing, deduplication, durable-before-ack capture, and provider-free asynchronous projection are implemented. Controlled P.4 genuine-delivery acceptance was completed and its retained local PostgreSQL evidence is used by the P.5 acceptance harness. Production target/deployment details remain environment-specific and are not committed here.

The Deal audit endpoint is also disabled by default. Enabling it requires `HUBSPOT_UI_EXTENSION_ENABLED=true`, the exact public HTTPS origin in `HUBSPOT_UI_EXTENSION_PUBLIC_BASE_URI`, the numeric HubSpot app ID in `HUBSPOT_UI_EXTENSION_APP_ID`, and a dedicated cursor-integrity key ID/Base64 32-byte key in `LINE_ITEM_WATCH_AUDIT_CURSOR_ACTIVE_KEY_ID` and `LINE_ITEM_WATCH_AUDIT_CURSOR_ACTIVE_KEY`. It validates HubSpot v3 signed fetch requests and serves only local Tenant/connection-scoped projection data; it never calls HubSpot. Migration `008` requires `pg_trgm` and intentionally fails if the database cannot provide it. See [ADR 0008](docs/adr/0008-deal-scoped-audit-read-boundary.md).

## Deal App Card

The first frontend package is under `src/app/cards`. It uses only React and `@hubspot/ui-extensions`, calls the P.6 endpoint only through `hubspot.fetch`, and does not call HubSpot CRM APIs or mutate CRM records. Configure `LINE_ITEM_WATCH_API_ORIGIN` as an origin-only HTTPS HubSpot project-profile variable; the same variable supplies the runtime origin and the exact permitted fetch prefix. Do not commit a real environment origin to the app manifest.

```sh
cd src/app/cards
npm ci
npm run typecheck
npm run lint
npm test
```

HubSpot project validation is profile-dependent. Create/select a profile with the required variable before running `hs project validate --profile <name>`. Validation is read-only; upload, deploy, and live portal acceptance remain separate authorized operations. See [Deal App Card architecture and operations](docs/architecture/deal-app-card.md).

## Project documentation

- [Product overview](docs/business/product-overview.md)
- [Private Beta scope](docs/business/beta-v1.md)
- [Product Module model](docs/business/modules.md)
- [Architecture overview](docs/architecture/system-overview.md)
- [Modularity](docs/architecture/modularity.md)
- [Provider integration](docs/architecture/provider-integration.md)
- [Deal audit API](docs/architecture/deal-audit-api.md)
- [Deal App Card architecture and operations](docs/architecture/deal-app-card.md)
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
