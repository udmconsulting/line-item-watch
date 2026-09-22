# Coding guidelines

## Boundaries and dependency direction

- Keep business/application logic inward of provider, HTTP, persistence, hosting, monitoring, and billing implementations.
- Define focused ports from current use-case needs. Do not create framework-like layers, generic provider factories, or universal CRM models in anticipation of unknown requirements.
- Keep controllers and adapters responsible for validation, mapping, and delegation—not core business decisions.
- Keep provider DTOs out of module business logic; map controlled internal concepts explicitly.
- Keep persistence behind meaningful module/application-owned boundaries. Do not build a database-agnostic persistence framework; PostgreSQL is the expected database.
- Never access another Product Module's repositories or tables directly.

## Naming and hardcoding

Use clear names, cohesive units, explicit ownership, and no hidden side effects. Avoid duplicated business rules and unexplained literals.

- Environment-specific values use typed, validated, environment-aware configuration.
- Closed, owned domain sets may use enums or value objects.
- Provider-controlled values use safe external representations and controlled mappings; unknown values should not cause unnecessary crashes.
- Reusable owned limits use named constants or configuration according to who controls them.
- Tests use explicit fixtures or test data.

Do not blindly convert strings to enums. Add abstractions only when their purpose and owner are clear.

## Tenant and provider data

- Resolve external provider account -> Platform Connection -> Tenant before customer business processing.
- Carry explicit tenant context through synchronous and asynchronous work.
- Retain tenant ownership and necessary connection provenance for provider-backed customer data.
- Never treat an external event or business-object ID as globally unique.
- Minimize collection, persistence, and duplication of provider data.

## Configuration and secrets

Mandatory configuration should fail fast where appropriate. Secrets and OAuth tokens must never be committed, logged, embedded in source, copied into examples, or exposed to clients. Credential storage must be encrypted at rest through an approved managed mechanism once selected.

## Errors and observability

Do not silently swallow failures. Separate domain outcomes from technical failures, retain non-sensitive diagnostic context, retry only classified retryable failures, design idempotent event/job processing, and expose terminal failures. Operationally important behavior requires structured logs, processing status, health signals, and actionable alerts.

## Persistence and migrations

Use Liquibase as the only schema-management mechanism, with an ordered YAML manifest and human-reviewable formatted SQL changesets under `backend/src/main/resources/db/changelog`. Hibernate uses `ddl-auto=validate`; `create`, `create-drop`, `update`, and `schema.sql` are forbidden for schema management. Add constraints and indexes for important invariants where practical. Every customer-owned persisted record carries tenant ownership. Provider-backed records also retain necessary connection provenance.

Domain and application types must not carry JPA annotations. JPA entities, Spring Data repositories, PostgreSQL-specific queries, and explicit mappings remain within the owning capability's `infrastructure.persistence` package. Do not introduce generic base entities or repositories.

## Documentation as Definition of Done

Update the relevant documentation in the same task whenever behavior, architecture, boundaries, providers, API, data model, configuration, operations, security/privacy, deployment, onboarding, retention, subprocessors, or extension mechanisms change. Add an ADR for a material architecture decision. Describe current reality and mark unresolved decisions `TBD`.
