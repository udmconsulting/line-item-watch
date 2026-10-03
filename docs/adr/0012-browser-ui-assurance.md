# ADR 0012: Use shared Playwright journeys and a scheduled Cloud Run Job for UI assurance

## Status

Accepted for repository implementation; live staging/production acceptance remains pending.

## Context

Line Item Watch is a HubSpot worker UI extension. Unit tests can render its remote component tree, but cannot prove the card loads inside HubSpot, an authenticated signed fetch reaches the deployed service, or the customer journey remains usable. Native Google Cloud synthetic monitors run a second-generation Cloud Run function and provide Puppeteer samples, but they do not provide the same Playwright journey used by delivery, the required authenticated-state boundary, or this repository's bounded failure-evidence contract.

## Decision

- Keep Vitest as the component/contract authority and add a deterministic Playwright layer around the real `DealAuditCard` through a browser-only adapter for HubSpot's remote UI tree. This validates loading, integrated fetch/query behavior, search, Show history, pagination, refresh, warnings, errors, correlation reference, and EN/HU copy without a live account.
- Use the same semantic `LineItemWatchPage` journey for protected staging delivery and production. Live targets are explicit, fail closed, never default to production, and do not run in pull requests.
- Run the recurring production journey as a single-task, zero-retry Cloud Run Job invoked by Cloud Scheduler about every ten minutes. Backend readiness remains a separate one-minute uptime check.
- The production fixture is a dedicated synthetic HubSpot account/Deal with only synthetic data. The journey reads, searches, paginates, and refreshes; it never edits CRM or drives OAuth lifecycle.
- Store browser state, record URL, and fixture contract as separate environment-specific Secret Manager values with explicit versions. Treat storage state as a credential.
- Emit structured, low-cardinality results classified as `PRODUCT_FAILURE`, `SYNTHETIC_AUTH_FAILURE`, or `SYNTHETIC_HARNESS_FAILURE`. A label-free product-failure gauge resets on success, enabling a critical alert only after the failure remains set across two scheduled observations. A label-free success heartbeat supports freshness alerting.
- Keep production trace/video and automatic screenshots off. Failed production runs upload only sanitized JSON evidence to a non-public, write-only, 30-day bucket. Mock-only CI may retain trace/screenshots for seven days; visual comparison covers only the owned harness surface.
- AI diagnosis is optional, post-failure, read-only, and never a release/alert authority. Only the sanitized evidence schema may be supplied after human or policy approval; no screenshot, trace, storage state, URL, customer data, or raw logs may leave the approved boundary.

## Consequences

The Cloud Run design adds one container image, job, scheduler, two service accounts, three secret containers, a small evidence bucket, log metrics, and alerts. It has more repository code than a native Puppeteer function but avoids two browser stacks and supports the real protected delivery gates. The design remains GCP-portable at the browser layer: Playwright tests and evidence classification are vendor-neutral; only scheduling, secrets, metrics, and storage adapters are GCP-specific.

Official references: [Cloud Run jobs](https://cloud.google.com/run/docs/create-jobs), [scheduled job execution](https://cloud.google.com/run/docs/execute/jobs-on-schedule), [Google synthetic monitors](https://cloud.google.com/monitoring/synthetic-monitors/create), and [Playwright authentication state](https://playwright.dev/docs/auth).
