# UI assurance architecture

## Layers and authority

| Layer | Target | Purpose | Gate |
|---|---|---|---|
| Vitest | HubSpot remote component renderer | Detailed state, contracts, races, validation, and formatting | PR |
| Playwright deterministic | Real card + controlled fetcher in owned browser adapter | Integrated customer behaviors, browser errors, and stable owned-surface pixels | PR |
| Playwright staging | Dedicated staging HubSpot fixture | Actual HubSpot render, session, signed fetch, deployed API, search/history/pagination/refresh | Staging acceptance |
| Playwright production | Dedicated production synthetic fixture | Read-only semantic availability check | Release acceptance and scheduled monitoring |

Deterministic assertions are the PASS/FAIL authority. The adapter is intentionally not represented as proof of the HubSpot shell; only the protected staging journey provides that evidence. Live journeys use accessible names and owned text, not HubSpot CSS classes or full-shell snapshots.

## Target and authentication contract

`LIW_ASSURANCE_TARGET` must exactly match the invoked project. Live configuration also requires an HTTPS `app.hubspot.com` record URL, the exact HTTPS API origin, valid Playwright storage-state JSON, a bounded synthetic fixture contract, and an immutable application digest. Missing or mismatched values fail as `SYNTHETIC_HARNESS_FAILURE`; no target defaults to production. Browser/network error gating is limited to the owned API origin or Line Item Watch messages so unrelated HubSpot-shell telemetry cannot create false product outages.

Create one least-privilege HubSpot automation user per environment and keep its UI language set to English, matching the live journey's accessibility contract. Use the normal interactive sign-in and MFA policy to refresh state; never automate password entry, disable MFA, or store credentials in source. Store state as an environment secret. Staging state cannot be used by production and production state is available only to the production job. Rotation is an explicit maintenance operation followed by a read-only smoke.

The fixture contract names only dedicated synthetic records and stable expected copy. It must never identify an arbitrary customer tenant. Fixture maintenance may update known synthetic names/history out of band, but the monitor itself may not mutate CRM, manufacture drift, install/reconnect, or force token refresh.

## Visual and failure evidence

The visual baseline snapshots only the fixed-width Line Item Watch-owned deterministic surface. Dates are fixed by the fixture/timezone and dynamic last-refresh text is masked. Production uses semantic assertions because HubSpot shell pixels are neither owned nor stable.

The safe evidence schema records environment, journey, release digest, result class, sanitized failing step, safe correlation IDs, duration, and attempt. It strips credential-like fields and URL queries and caps messages. Mock CI may retain screenshots/traces for seven days because it contains no authentication or customer data. Live trace/video/automatic screenshot capture is disabled. Production uploads failure JSON only; the runtime can create unique objects but cannot read/delete them, and bucket lifecycle deletes them after 30 days.

Do not retain cookies, authorization headers, OAuth tokens, HubSpot signatures, passwords, full record URLs, request/response bodies, arbitrary console output, or customer data. A future screenshot/trace exception requires a proved owned-surface selector, redaction test, retention approval, and renewed privacy review.

## Result and alert semantics

- `PRODUCT_FAILURE`: authenticated browser reached the product, but the owned journey or browser/network health failed.
- `SYNTHETIC_AUTH_FAILURE`: the dedicated HubSpot session is invalid or redirected to login. This is not reported as product downtime.
- `SYNTHETIC_HARNESS_FAILURE`: configuration, runner, metric, or evidence infrastructure is invalid.

Each single failure creates a WARNING in its class. Only consecutive production product failures raise CRITICAL: the product-failure gauge is `1` on product failure and `0` on every non-product outcome, and the PromQL condition requires at least two samples in the configured window with every sample equal to `1`. A missing second run therefore cannot masquerade as a second product failure. No success heartbeat within the freshness window raises CRITICAL. Project isolation and fixed metric types avoid customer/record identifiers and other high-cardinality labels.

## Cost

At a ten-minute cadence the job runs about 4,380 times/month. At one vCPU, 1 GiB, and an assumed 30–60 seconds/run, gross compute is roughly 36.5–73 vCPU-hours and 36.5–73 GiB-hours before the Cloud Run jobs free tier; actual charge is expected to be low single-digit USD or zero within available free tier. Cloud Scheduler is $0.10/job/month with three jobs free per billing account. Failed JSON evidence and log/metric volume should remain pennies at this scale, subject to retention and pricing changes. Budget an incremental **$0–5/month** and investigate if it exceeds **$10/month**. This does not materially change the existing $135–155/month planning baseline.

Recalculate before provisioning from [Cloud Run pricing](https://cloud.google.com/run/pricing), [Cloud Scheduler pricing](https://cloud.google.com/scheduler/pricing), [Cloud Storage pricing](https://cloud.google.com/storage/pricing), and [Google Cloud Observability pricing](https://cloud.google.com/products/observability/pricing).
