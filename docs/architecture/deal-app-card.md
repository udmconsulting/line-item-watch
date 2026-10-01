# Deal App Card architecture and operations

## Boundary and data flow

The P.7 card is the first frontend package under `src/app/cards`. Its HubSpot component is registered only at `crm.record.tab` for `DEALS`. The runtime accepts the current record ID from `context.crm.objectId`, requires a positive safe integer, normalizes it to a decimal string, and rejects invalid context before any request.

```text
HubSpot Deal context
  -> validate Deal ID and LINE_ITEM_WATCH_API_ORIGIN
  -> hubspot.fetch GET /api/v1/line-item-watch/deals/{dealId}/audit
  -> P.6 signed-fetch authentication and Tenant/connection/entitlement resolution
  -> local bounded projection read (no HubSpot/provider call)
  -> strict consumed-field validation
  -> localized line-item and event presentation
```

The card never calls HubSpot CRM APIs, never uses native `fetch`, adds no custom headers or body, defines no card-owned automatic retry policy, and has no CRM write path. It keeps only in-memory React state for the current render and does not use browser storage, cookies, or another client persistence mechanism. HubSpot adds signed request metadata to `hubspot.fetch`; any transport behavior intrinsic to that platform API remains HubSpot-owned. The backend remains the sole authority for account-to-Tenant/connection resolution; a Deal ID is only an untrusted selector within that authenticated boundary.

## Configuration and permissions

`src/app/app-hsmeta.json` retains the existing app identity and OAuth scopes. Its fetch allowlist contains one path-scoped entry:

```text
${LINE_ITEM_WATCH_API_ORIGIN}/api/v1/line-item-watch/deals/
```

`LINE_ITEM_WATCH_API_ORIGIN` is a HubSpot project-profile variable and is also available at runtime through `context.variables`. It must be a canonical origin-only HTTPS value without a trailing slash and must exactly match the backend `HUBSPOT_UI_EXTENSION_PUBLIC_BASE_URI`. The client rejects paths, user information, queries, fragments, blank values, non-HTTPS schemes, localhost/loopback names and addresses, non-canonical origins, and invalid URLs. This keeps the permission and runtime host on one deployment-owned value without committing a development, staging, or production hostname.

Profile substitution is resolved by HubSpot project translation. Therefore `hs project lint` can run without a profile, while project validation that translates `${LINE_ITEM_WATCH_API_ORIGIN}` requires `hs project validate --profile <name>` and an existing `src/hsprofile.<name>.json`. Upload/deployment is not part of validation and requires separate authorization.

## UI behavior

The card shows each relevant Line Item in a compact accordion. It displays all ten monitored fields, explicit `UNKNOWN`/`ABSENT` values, current Deal membership or membership at deletion, a neutral deleted state with deletion time, and the history-coverage boundary/disclaimer. Server-side name search covers the complete retained Deal dataset; event filters cover type, allowlisted field, exact Line Item, and portal-calendar date range. `Show history` applies the exact Line Item filter. Applied filters are visible, individually removable, and clearable.

Recent changes are rendered newest first and grouped by portal-calendar Today, Yesterday, or a locale date. Event identity uses the latest retained Line Item name plus ID and explicitly does not claim an event-time name. Manual Refresh returns both sections to page one under the current query, preserves visible data on failure, and performs no polling or provider synchronization. Line Item and event cursors advance independently; duplicate clicks are suppressed, stale responses are ignored, repeated identities are de-duplicated, and a section failure preserves already rendered data. An invalid cursor offers a page-one restart.

Accumulated client state is capped at 50 unique Line Items and 100 unique events. Reaching a cap removes Load more and states that additional retained results may exist, directing the investigator to narrow search/filters rather than implying end of history.

English and Hungarian are bundled locally. Message selection uses `user.language`; number/date formatting uses `user.locale`; timestamps, day grouping, and date-filter boundaries use `portal.timezone`; invalid time zones fall back to UTC. Date filters serialize portal midnight as inclusive `from` and the next portal midnight as exclusive `to`, including 23/25-hour DST days. Date-only fields are formatted through a UTC calendar construction so they cannot shift days. Numeric text is formatted only when it is conservatively representable without precision loss. The API provides no currency code, so the card never invents a currency.

## Errors, privacy, and logging

The client accepts only the P.6 public error codes `INVALID_REQUEST`, `AUTHENTICATION_FAILED`, `ACCOUNT_UNAVAILABLE`, `SERVICE_UNAVAILABLE`, and `INTERNAL_ERROR`, plus card-owned network, timeout, rate-limit, malformed-response, configuration, and context categories. Visible copy is bundle-owned. The only backend diagnostic rendered or copied is the body `correlationId`. Response headers are not read because `hubspot.fetch` does not expose them.

Unknown enum values, missing consumed fields, invalid invariants, mismatched Deal IDs, and malformed public errors fail closed into the generic malformed-response UI. Additional object properties are ignored for forward compatibility. Exception messages, response diagnostics, raw payloads, cursors, credentials, internal Tenant/connection/source IDs, and customer values are never logged. Missing-translation logging can contain only a compile-time closed app-owned translation key.

## Verification and acceptance

Routine repository verification is non-live:

```sh
cd src/app/cards
npm ci
npm run typecheck
npm run lint
npm test
cd ../../..
hs project lint
hs project validate --profile <name> # only with a configured profile
cd backend
./mvnw verify
```

Live acceptance is a separate configuration gate. It requires an approved project profile/portal, an installed app/card, a reachable HTTPS backend whose origin matches both frontend/backend configuration, the backend read feature enabled, valid app ID/client-secret signing configuration, an active entitled Platform Connection, and representative retained history. A temporary HTTPS tunnel may be used only as a separately authorized acceptance mechanism; its hostname must not be committed or treated as a production target. The permanent production hostname remains deferred. Verify English and Hungarian, full and section empty states, pagination, retry/error/correlation behavior, deleted/membership/coverage semantics, and correct portal-time-zone rendering. Do not mutate CRM data to manufacture evidence, upload/deploy without authorization, or weaken signed-fetch validation for local testing.

No new database table, backend mutation, CRM write permission, external frontend service, telemetry vendor, or subprocessor is introduced by the card. React, the HubSpot UI Extensions SDK, and frontend development/test tooling are the only new package dependencies.

The package intentionally pins ESLint 9.39.5 because `@hubspot/eslint-config-ui-extensions` 1.2.0 declares ESLint 9 compatibility. npm may report ESLint 9's general deprecation now that ESLint 10 exists; upgrading ESLint independently would move outside the HubSpot lint configuration's supported peer range.
