# Observability

## Private Beta requirement

Operational visibility is part of Private Beta readiness, even though no vendor has been selected or integrated. The system must make significant failures visible to the operator rather than depend on occasional dashboard inspection.

## Required capabilities

- structured, centralized, searchable logs;
- exception/error tracking;
- service and database health monitoring;
- actionable operator alerts;
- durable job/event status, attempt history, retry state, and terminal failure state;
- stuck-job and backlog detection;
- OAuth/token refresh and credential-lifecycle failure detection;
- webhook receipt/processing failure detection;
- initial-baseline and reconciliation failure detection;
- provider API error and rate-limit visibility; and
- database and service health visibility.

Alert thresholds, on-call destination, runbooks, service-level indicators, dashboards, monitoring/logging/error vendors, and log retention are TBD. They must be chosen and tested before external Private Beta operation.

## Context and privacy

Useful telemetry should carry internal tenant ID, connection ID, correlation/event/job ID, operation, attempt, outcome, and provider error category as appropriate. It must not include access or refresh tokens, secrets, unnecessary customer payloads, or sensitive business values merely for convenience.

Errors must be sanitized before they reach logs or monitoring services. High-cardinality or customer-controlled values require deliberate handling. Access to telemetry follows least privilege, and any selected external telemetry vendor must be evaluated and recorded as a subprocessor where applicable.

## Operational behavior

Failures must not be silently swallowed. Domain outcomes should be distinguishable from technical failures. Retries apply only to classified retryable failures, preserve idempotency, and end in an operator-visible terminal state after the configured policy is exhausted.

The implemented OAuth slice logs successful installation with its OAuth correlation ID, internal Tenant/connection context, and external account identity, without token values. Failures distinguish token-exchange rejection/unavailability, malformed issuance responses, introspection rejection/unavailability, invalid token metadata, account mismatch, missing required scope, and local finalization failure through fixed sanitized categories. On-demand refresh diagnostics use the non-secret connection ID and the same categories. Best-effort revocation failure produces an operator-facing warning. Public OAuth errors use fixed non-reflective HTML and do not expose provider or exception text. Successful baseline persistence logs internal Tenant/connection IDs and aggregate counts only; it excludes Deal/Line Item IDs, property values, payloads, and credentials. Provider failures are classified and sanitized, but alerts, durable attempts, and automatic retry remain future work.
