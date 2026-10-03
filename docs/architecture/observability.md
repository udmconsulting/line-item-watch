# Observability

## Private Beta requirement

Operational visibility is part of Private Beta readiness. GCP Cloud Logging and Cloud Monitoring are the selected deployment adapters; repository configuration exists but no environment telemetry resource or alert route has been provisioned. The existing billing-level budget notification is a separate bootstrap guardrail and is not a spending cap. The system must make significant failures visible to the operator rather than depend on occasional dashboard inspection.

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

Terraform supplies tunable starting alerts for public availability, service errors/latency/resources, database availability/resources/storage/connections, worker/reconciliation/webhook signals, certificate state, backups where observable, and budget thresholds. Notification destinations, final thresholds, service-level indicators, dashboards, and log retention remain provisioning/operations decisions and must be accepted before external Private Beta operation.

The P.9 UI assurance delta adds a production browser result without slowing the one-minute backend readiness signal. Structured synthetic results use three fixed failure classes. One failure is WARNING; two consecutive product failures and a stale success heartbeat are CRITICAL. Authentication and harness failures remain distinct from product outage. The detailed architecture and privacy boundary are in [UI assurance architecture](ui-assurance.md).

## Context and privacy

Every initial application HTTP request receives a fresh server-owned UUID. Caller `X-Correlation-ID` values are ignored; the generated value is scoped in request attributes and MDC, preserved across error/async dispatch, returned on every response, and removed/restored after each dispatch. P.6 error-body `correlationId` exactly matches the header. A correlation ID is operational metadata, not user/actor identity.

Each claimed worker attempt receives an independent `operationId`; `signalRef` is separate causal context and is not represented as the original webhook correlation. Structured fields use the fixed vocabulary `correlationId`, `operationId`, `component`, `operation`, `result`, `errorCode`, normalized `providerErrorCode`, `tenantRef`, `connectionRef`, `signalRef`, `attempt`, `durationMs`, and explicitly named aggregate counts. Internal UUID references are included only when useful. Logs exclude external HubSpot accounts/users, Deal/Line Item/provider object IDs, URIs/query strings, bodies, cursors, credentials/signatures/deduplication material, customer names, and commercial/property values.

Errors must be sanitized before they reach logs or monitoring services. `providerErrorCode` accepts only bounded application-owned categories, never raw provider strings, and never becomes a metric tag. Known operational failures log a fixed category without an unnecessary throwable. Unexpected failures use a reusable diagnostic throwable that retains exception classes and bounded stack frames but suppresses original/cause messages and suppressed exceptions. Access to telemetry follows least privilege, and any selected external telemetry vendor must be evaluated and recorded as a subprocessor where applicable.

Typed domain/application failures remain separate from the shared operational registry. A logging or metrics boundary that needs aggregation maps them explicitly to `OperationalErrorCode`; merely adding a domain failure code does not expose it publicly or make it a metric tag. Diagnostic message wording is never used for that mapping.

Micrometer records vendor-neutral `application.operation.duration` timers with exactly four bounded tags: `component`, `operation`, `outcome`, and `error_code`. Operation and error values come from closed application enums, never URLs, identifiers, cursors, or provider-controlled values. Existing `line_item_watch.processing.*` metrics remain the durable-worker operational series. P.8 adds closed-outcome reconciliation and replay counters plus reconciliation-age, suspected-gap, and exhausted-signal gauges. P.9 adds never-reconciled active connections, reconciliation last-success time/age, bounded webhook-ingestion failures, migration result, and key-rewrap remaining count. The optional Stackdriver registry is an isolated export adapter. No metric label contains a Tenant, connection, Deal, Line Item, signal, or provider-controlled value.

## Operational behavior

Failures must not be silently swallowed. Domain outcomes should be distinguishable from technical failures. Retries apply only to classified retryable failures, preserve idempotency, and end in an operator-visible terminal state after the configured policy is exhausted.

The implemented OAuth slice logs successful installation with its OAuth operation ID and internal Tenant/connection references, never external account identity or token values. Failures distinguish token-exchange rejection/unavailability, malformed issuance responses, introspection rejection/unavailability, invalid token metadata, account mismatch, missing required scope, and local finalization failure through the bounded application-owned provider category. On-demand refresh uses the same controlled categories. Best-effort revocation failure produces an operator-facing warning. Public OAuth errors use fixed non-reflective HTML and do not expose provider or exception text.

Successful observation and webhook captures log internal `tenantRef`/`connectionRef` values and aggregate counts only. Webhook request failures use the shared correlation context and a fixed category. Known authentication/payload/database failures omit stacks; unexpected internal failures use the safe class/frame diagnostic. Logs exclude raw bodies, headers/signatures, portal and object IDs, property values, names, prices, quantities, discounts, and credentials. A completed batch records event/captured/duplicate/ignored counts.

P.5 persists processing status, attempt count, due time, claim token/lease, completion time, and a fixed sanitized error code. Its structured logs contain internal `tenantRef`/`connectionRef`/`signalRef`, attempt, and outcome, but not claim tokens, external Line Item/Deal IDs, deduplication keys, properties, values, or exception messages. Micrometer records claims, successful/retried/terminal outcomes, processing duration, backlog, active claims, terminal failures, and oldest due age. Production profiles emit JSON to stdout for Cloud Logging; local logs remain readable. Alert definitions exist, while thresholds/routes must be operationally accepted around these metrics and durable `FAILED` rows.

Every P.6 Deal audit request uses the shared correlation foundation. Errors return that ID with a stable localization-neutral code; exception and database detail remain server-side. Success logs contain only internal Tenant/connection references and returned section counts; correlation is supplied by MDC. Rejection/failure logs use a fixed category and the safe throwable mechanism only for unexpected internal failures.

Technical logs diagnose execution. Product Module semantic audit explains customer-visible Line Item history. Platform activity audit records committed control-plane connection and entitlement transitions. These are deliberately independent and must not be substituted for one another.
