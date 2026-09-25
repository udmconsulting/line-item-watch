# AGENTS.md

This is the canonical execution contract for coding agents in this repository. Follow it together with the linked project documentation. If tool-specific guidance conflicts with this file, this file and accepted ADRs take precedence.

## Current project state

- `LINE_ITEM_WATCH` is the only defined Product Module.
- HubSpot is the first external provider; it is not the business domain.
- The accepted feasibility spike is under `spike/`; do not repeat destructive/live tests without explicit authorization.
- The backend under `backend/` implements Tenant and Platform Connection identity/lifecycle, Product Module entitlements, PostgreSQL persistence, HubSpot OAuth install/callback, encrypted refresh credentials, on-demand refresh, internal uninstall, and explicit one-Deal `LINE_ITEM_WATCH` baseline synchronization with immutable baseline and replaceable latest snapshots. Webhooks, audit-event reconstruction, asynchronous processing, customer administration/disconnect APIs, UI, and production operations remain unimplemented.
- Read [product overview](docs/business/product-overview.md), [Beta scope](docs/business/beta-v1.md), [architecture overview](docs/architecture/system-overview.md), and the relevant development/trust documents before implementation.

## Before implementation

Every task must:

1. inspect the current Git branch, status, HEAD, upstream, and recent history;
2. preserve unrelated user changes;
3. read the relevant business, architecture, development, ADR, and trust documentation;
4. identify the owning Product Module and affected Platform Core capabilities;
5. identify affected provider adapters, tenant/security/data boundaries, contracts, configuration, and data model;
6. decide whether architecture actually changes—never change it incidentally; and
7. establish the available build, test, lint, formatting, validation, and secret-scan commands without inventing results.

## Architecture and implementation rules

- Preserve the modular monolith and explicit Platform Core/Product Module ownership.
- Keep dependency direction inward. Business/application logic must not depend directly on HubSpot clients/DTOs, OAuth mechanics, HTTP, PostgreSQL/JPA implementations, hosting, monitoring, or billing vendors.
- Keep core business rules out of controllers and adapters. Use focused application-owned boundaries based on real use cases; avoid speculative abstractions.
- A module owns its logic, persistence, APIs/UI, tests, and operational behavior. It must not directly access another module's tables or repositories.
- Do not create duplicate event ingress, credentials, tenant/auth, billing, or observability infrastructure inside a module.
- Enforce module activation through tenant/module entitlements; never hard-code pricing-plan names in product logic.
- Do not create a new microservice/runtime by default. API and worker separation is initially logical.
- Schema changes require versioned migrations and appropriate constraints/indexes. No manual production drift.

## Provider and tenant rules

- Preserve provider adapter boundaries. Provider DTOs and externally controlled values must be validated and mapped before entering business logic.
- Resolve external provider account -> Platform Connection -> internal Tenant before customer business processing.
- Never use HubSpot `portalId` as the internal tenant ID and never infer ownership from an external business-object ID.
- Every provider-backed customer record retains tenant ownership and necessary connection provenance. External object/event IDs and idempotency keys are scoped to provider/connection context.
- Use current supported provider APIs; do not silently fall back to legacy behavior.
- Document provider identity, external account identity, permissions/scopes, semantics, errors/rate limits, and security/privacy implications.
- Do not introduce a universal CRM model, generic provider factory, or shared provider interface until a concrete shared use case justifies it.
- For HubSpot Line Item Watch, webhook delivery is a signal; provider object/history APIs are authoritative reconstruction where available. Do not assume event ordering.
- Deletion uses accepted MODEL B: initial baseline plus latest known snapshot. Do not redesign it without new evidence and an explicit decision.

## Coding and configuration rules

- Use clear names, cohesive units, explicit ownership, and no hidden side effects or duplicated business rules.
- Use typed, validated, environment-aware configuration; fail fast on mandatory missing values where appropriate.
- Use enums/value objects for closed owned sets when useful. Do not force provider-controlled strings into brittle enums; handle unknown values safely.
- Replace reusable owned limits with named constants or configuration according to ownership. Keep test values in explicit fixtures.
- Do not silently swallow failures. Separate domain outcomes from technical failures, retry only retryable failures, preserve idempotency, and expose terminal failures.
- Add meaningful tests at the smallest appropriate level. Include tenant isolation, retries/idempotency, provider/persistence boundaries, and security-sensitive behavior when relevant.
- Add structured, privacy-aware observability and operator alerts for operationally significant behavior.

## Security and privacy review

For every implementation task, answer:

- Does it access, store, or transmit customer data, and is that data tenant-scoped?
- Is every collected/stored field necessary, with connection provenance where required?
- Does it introduce credentials, secrets, external input, or a new vendor/subprocessor?
- Does it change retention, deletion, backup, export, or uninstall behavior?
- Does it change logs, monitoring, or error reporting, and could they expose customer data?
- Does it require an operational alert, incident response, or privileged access?
- Which data inventory, security, retention, subprocessor, or legal-readiness documents must change?

Never commit, log, expose, or document secret values or OAuth tokens. Require HTTPS for public production endpoints, webhook authenticity validation, least privilege, encryption at rest, and managed credential lifecycle.

Never claim without explicit evidence: GDPR compliant, SOC 2 compliant, ISO 27001 compliant, HIPAA compliant, certified, zero risk, or secure by definition.

## Documentation Definition of Done

Update documentation in the same task when changing business/module behavior, architecture, provider integration, boundaries, API, data model, configuration, deployment, operations, security/privacy, onboarding, retention, subprocessors, or extension mechanisms. Add an ADR for material architecture decisions. Document current behavior accurately and use `TBD` rather than inventing decisions.

## HubSpot project constraints

- The root `hsproject.json` defines `srcDir` and `platformVersion`; current platform version is `2026.09`.
- Component configuration files end in `-hsmeta.json`; each `uid` must be unique, and components belong in the valid directory for their `type` under the configured source directory.
- An app component belongs in `src/app`; only one app component is allowed. Marketplace distribution requires OAuth.
- HubSpot UI extensions use supported `@hubspot/ui-extensions` APIs. Do not assume browser globals or arbitrary components/styles; card/settings external fetch URLs must be permitted in app configuration.
- A HubSpot webhooks component is provider infrastructure, not a Product Module. Follow current platform restrictions and shared-ingress architecture.
- Prefer an installed HubSpot integration tool over manual CLI changes to HubSpot assets. Uploads, deployments, account changes, authentication, and live provider-data changes require explicit authorization.
- Consult current official HubSpot documentation when implementing platform components; generated sample guidance is not a substitute for version-specific documentation.

## Before completion

Report:

- tests and validation run, including unavailable or intentionally skipped checks;
- build, lint, and formatting results where available;
- `git diff --check` and secret-scan results;
- files changed and any migrations;
- documentation changes and architecture impact;
- security/privacy and tenant-isolation impact;
- new configuration and external dependencies/subprocessors;
- branch, status, HEAD, upstream, changed/untracked files; and
- whether the work is commit-ready, with reason.

Never document a check as passing unless it ran successfully.

## Git rules

- Use one focused branch per task and preserve unrelated changes.
- Do not push or merge without explicit authorization.
- Do not rebase, reset, force, amend unrelated history, or rewrite user work without explicit authorization.
- Commit only when the task explicitly permits it.
- Never commit secrets, temporary credentials, build output, temporary webhook/deployment components, or IDE artifacts.
