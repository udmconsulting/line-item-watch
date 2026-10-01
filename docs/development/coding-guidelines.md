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

Do not silently swallow failures. Public error codes are localization-neutral identifiers, never localized text or raw technical/provider detail. Keep the closed public registry separate from bounded internal operational categories and protocol-specific response shapes.

Use a typed domain/application failure when callers, retry policy, logs, metrics, or tests need stable semantic identity independent of diagnostic wording. Keep one-off programmer, configuration, and local invariant messages as diagnostics rather than creating ceremonial enums. Retryability belongs to the typed failure when it is intrinsic; keep it at the handling boundary when it genuinely depends on runtime context such as attempt count or exception class. Never branch on exception message text.

Domain/application failure codes, `OperationalErrorCode`, `PublicErrorCode`, and UI localization keys are separate concepts. Map between them explicitly only at a boundary that needs the mapping. An internal failure does not automatically become a public response code, localization key, log field, or metric tag, and diagnostic English text is not a semantic identifier.

Every initial HTTP request uses the shared server-owned correlation filter; do not accept caller IDs or add slice-specific filters. Use a fresh operation ID for each worker claim and keep causal resource references separate. Scope MDC with `finally`/`AutoCloseable` cleanup. A correlation/operation ID is not actor identity.

Structured logs use fixed application-owned `component`, `operation`, `result`, and error categories plus only necessary `*Ref` internal context and named aggregate counts. Never log customer/provider object identifiers, URIs/query strings, bodies, cursors, credentials/signatures, deduplication inputs, names, or business/property values. Normalize provider errors to a bounded owned enum. Known operational failures do not need stacks; unexpected failures use `SafeDiagnosticException`, never a raw throwable whose message/cause may be unsafe.

Operation metrics use `application.operation.duration` and only bounded `component`, `operation`, `outcome`, and `error_code` tags. Never tag identifiers or provider-controlled values. Retain non-sensitive diagnostic context, retry only classified retryable failures, design idempotent event/job processing, and expose terminal failures.

Control-plane state changes that have an activity action must append through the Platform activity port in the same transaction. Record only committed transitions, use truthful actor types, and do not use technical logging or Product Module semantic audit as a substitute. Append-only application semantics do not imply WORM storage.

## Persistence and migrations

Use Liquibase as the only schema-management mechanism, with an ordered YAML manifest and human-reviewable formatted SQL changesets under `backend/src/main/resources/db/changelog`. Hibernate uses `ddl-auto=validate`; `create`, `create-drop`, `update`, and `schema.sql` are forbidden for schema management. Add constraints and indexes for important invariants where practical. Every customer-owned persisted record carries tenant ownership. Provider-backed records also retain necessary connection provenance.

Domain and application types must not carry JPA annotations. JPA entities, Spring Data repositories, PostgreSQL-specific queries, and explicit mappings remain within the owning capability's `infrastructure.persistence` package. Do not introduce generic base entities or repositories.

## Documentation as Definition of Done

Update the relevant documentation in the same task whenever behavior, architecture, boundaries, providers, API, data model, configuration, operations, security/privacy, deployment, onboarding, retention, subprocessors, or extension mechanisms change. Add an ADR for a material architecture decision. Describe current reality and mark unresolved decisions `TBD`.

## Out-of-scope discoveries and the product roadmap

When implementation or review reveals a valuable product, UX, operational, or technical idea outside the current approved scope:

- do not silently implement it or expand the phase;
- do not leave it only in chat or a source-code TODO;
- add or update it in [`docs/product/roadmap.md`](../product/roadmap.md);
- assign a P0/P1/P2/P3 priority and explain its user value or rationale; and
- record important dependencies, security/privacy implications, risks, and unresolved decisions.

Roadmap capture is not implementation approval. Keep the active change focused and obtain explicit scope approval before moving a backlog item into implementation.
