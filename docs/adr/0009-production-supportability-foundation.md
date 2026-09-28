# ADR 0009: Production supportability foundation

## Status

Accepted and implemented for P.6.B.

## Context

OAuth, webhooks, the Deal audit API, and asynchronous signal processing previously had slice-specific diagnostics. Private Beta operations need one privacy-aware correlation, error, logging, metrics, and control-plane activity model without coupling application code to a telemetry vendor or confusing technical records with customer-facing semantic history.

## Decision

- A highest-precedence HTTP filter creates a server-owned UUID for each initial request, ignores caller correlation headers, preserves the UUID across dispatches, exposes it in `X-Correlation-ID`, scopes it in MDC, and restores prior MDC state.
- Claimed worker attempts create independent operation UUIDs. A signal UUID remains causal context and is not presented as an originating request correlation ID.
- Public APIs use the closed error registry `INVALID_REQUEST`, `AUTHENTICATION_FAILED`, `ACCOUNT_UNAVAILABLE`, `SERVICE_UNAVAILABLE`, and `INTERNAL_ERROR`. Protocol-specific response shapes remain separate, and public errors never contain raw exception or provider text.
- Logs use an application-owned structured vocabulary. Provider error values appear only after mapping to a bounded application enum. Expected failures omit stacks; unexpected failures use a safe diagnostic throwable that retains exception classes and bounded frames while dropping messages and suppressed exceptions.
- Micrometer records `application.operation.duration` with only `component`, `operation`, `outcome`, and `error_code` tags from closed application enums. Existing `line_item_watch.processing.*` meters remain intact.
- Platform Core owns an insert-only application port and PostgreSQL `application_activity_audit` table for committed connection/entitlement transitions. The state mutation and row insert share one transaction. Actors are truthful, including `UNATTRIBUTED` when no authenticated human exists.
- A versioned machine-readable localization contract defines namespaces and locale resolution for the future UI. It contains semantic keys and test vectors, not translated copy.

Technical logging, Product Module semantic audit, and Platform activity audit are separate records with different purposes. A correlation or operation ID is diagnostic metadata, not actor identity. Append-only application behavior is not cryptographic tamper resistance or WORM storage.

## Consequences

All application HTTP responses carry a fresh server-owned diagnostic identifier, and unexpected-failure diagnostics remain useful without copying unsafe messages. Metrics remain low-cardinality and vendor-neutral. Audited state changes fail rather than commit without their audit row, while idempotent or losing operations emit no contradictory success activity.

The activity table adds operational metadata and may later contain opaque actor references; verified human/operator references are pseudonymous personal data. Tenant deletion cascades activity rows. Exact activity/log retention, privileged audit access, export, cryptographic sealing, WORM storage, a telemetry vendor, alert routing, dashboards, and translated UI copy remain TBD.

## Alternatives rejected

- Accept caller correlation IDs: rejected because they are untrusted and can collide or contain unsafe values.
- Log raw provider errors or exception messages: rejected because their sensitivity and cardinality are provider-controlled.
- Use semantic Line Item audit for administrative lifecycle activity: rejected because product history and control-plane accountability have different meaning and lifecycle.
- Rename the existing OAuth-state database column for terminology alone: rejected as migration churn; only Java concepts distinguish OAuth operation identity from request correlation.
