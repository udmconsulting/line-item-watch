# P.7.B audit usability hardening plan

## Status and phase boundary

This plan is based on `main` at `fba716752622c0fe3bd70f981eb2199ab645eeee`, where P.7 is merged. P.7.B remains one focused implementation phase owned by `LINE_ITEM_WATCH`:

- backend/API search and filtering on the existing Deal audit read boundary;
- a compact investigation UX in the existing HubSpot Deal card;
- bounded client state, query-bound cursors, and resilience coverage; and
- migration `008` for search/filter query support.

No new service, provider read, CRM write, entitlement model, authentication mechanism, or architecture boundary is required. Implementation, automated verification, and live acceptance are complete. Large-history, cursor-security, request-race, and DST edge cases remain automated acceptance by design because live CRM data must not be manufactured for them.

## Scope decision

### Must have now: P.7.B

1. Server-side, case-insensitive substring search of retained latest Line Item names across the complete Deal-relevant dataset.
2. Server-side event filtering by semantic event type, changed monitored field, exact Line Item ID, and time range.
3. A `Show history` action on every Line Item that applies the same server-side `lineItemId` event filter.
4. A self-contained Line Item identity on every event, including latest retained name state and ID.
5. Obvious active-filter state, removable filters, clear-all behavior, and distinct unfiltered versus filtered empty states.
6. Manual refresh that preserves applied query context, replaces results from page one, invalidates in-flight work, and does not poll.
7. Today/Yesterday/locale-date event grouping in the portal time zone without changing event timestamps or ordering.
8. A bounded hybrid pagination strategy: at most 50 accumulated Line Items and 100 accumulated events per card lifecycle/query context.
9. Versioned opaque cursors bound to the normalized effective query for their section.
10. Unit, integration, migration, frontend, and automated large-history/race coverage.

### Roadmap/later

- Exact or partial search across arbitrary current or historical property values. Values have different canonical types, historical-value search would touch immutable audit values, substring search needs additional indexes, and search terms can contain customer data. A generic implementation in P.7.B would either be misleading or create an unbounded scan surface. The roadmap records a separately designed full-text/property-value search capability.
- Exports, saved filters, deep links, date-to-date state reconstruction, analytics, notifications, configuration, and cross-Deal exploration.
- Automatic reconciliation, customer administration/recovery, and production operations remain separate Beta work even though some are launch blockers.

## Current-state findings

### Existing backend and data boundary

- `GET /api/v1/line-item-watch/deals/{dealId}/audit` is a signed `hubspot.fetch` boundary. It resolves the authenticated HubSpot account to an active Platform Connection, Tenant, and enabled `LINE_ITEM_WATCH` entitlement before reading.
- Reads are provider-free and use only the local module projection. Filters must not introduce HubSpot clients, token access, CRM calls, or provider DTOs.
- The read use case runs both requested page domains in one lock-capable `REPEATABLE_READ` transaction. It intentionally takes shared connection/entitlement authorization locks and performs no business-data writes.
- Line Items are ordered by external Line Item ID ascending. Events are ordered by `(occurred_at DESC, semantic_key DESC)`. Both are stable keysets with a maximum API page size of 20 and no `OFFSET`.
- The event Deal-context projection already copies occurrence time and semantic key so its Deal chronology index can serve the read. Migration `008` can extend that same read projection with immutable filter dimensions rather than creating a new read model.
- Tenant, connection, and Deal predicates are present in the current queries. A Deal or Line Item ID remains an untrusted selector within the authenticated owner scope and must never become an ownership key.

### Existing card

- The card requests 10 Line Items and 20 events initially, then appends independent pages forever. Duplicate IDs are removed, duplicate clicks are suppressed, and generation/request tokens ignore stale Deal and pagination responses.
- Event labels currently depend on whatever Line Item summaries happen to be loaded. An event outside that client page falls back to its ID, so the response should carry its own latest retained Line Item name state.
- There is no search, event filter, manual refresh, date grouping, or accumulated-state cap.
- The card already uses `user.language`, `user.locale`, and `portal.timezone`, has English and Hungarian bundles, preserves explicit `UNKNOWN`/`ABSENT` states, and renders safe localized errors.
- The pinned stable `@hubspot/ui-extensions` `0.17.0` declarations export `SearchInput`, `Input`, `Select`, `DateInput`, `StatusTag` with removal support, `Button`, `LoadingButton`, `Flex`, and the existing display components. None of those selected APIs is marked deprecated in the installed declarations. P.7.B should use these supported components and keep the declaration-based deprecation lint rule.

### Architecture, security, and privacy assessment

- Architecture does not materially change: this is an extension of the accepted Deal-scoped read model and card.
- Search/filter input and returned data are customer data and remain Tenant/connection scoped. Search terms, filter values, URLs/query strings, cursors, Line Item IDs, names, property values, and date ranges must not be added to logs or metric tags.
- Search terms travel in the signed HTTPS GET query. Application and reverse-proxy access logging must continue to omit query strings. Responses remain `Cache-Control: no-store`.
- No credential, scope, external dependency, frontend package, vendor, subprocessor, retention rule, deletion behavior, or CRM permission changes.
- The additive event name field repeats an already retained latest name in a bounded response; it does not add a new stored customer-data category.

## P.7.B implemented UX

### Compact card layout

```text
Line Item Watch                                      [Refresh]
Retained state and changes for this Deal.  Refreshed <time>

Search line items
[ SearchInput: Line Item name                         ] [Search]

Line items
<bounded accordions; each has Show history>
[Load more line items]

Recent changes
[Event type] [Changed field]
[Line Item reference]
[From date] [To date] [Apply filters]

Active filters: [Type ×] [Field ×] [Line Item ×] [Date ×]
[Clear filters]

Today
  <events>
Yesterday
  <events>
<locale date>
  <events>
[Load more changes]
```

Controls may wrap vertically in the narrow card. Do not use a Line Item dropdown populated only from loaded rows: that would falsely imply completeness. `Show history` is the primary Line Item filter path; the exact reference input supports pasted IDs and makes the active scope explicit.

### Search interaction

- Keep draft text local until `Search` is activated or the input is committed and submitted. Do not issue one request per keystroke.
- Applying a search starts the Line Item section at page one, invalidates its older requests/cursor, and leaves event filters/results unchanged.
- Search is server-side. The client never filters the loaded Line Item array and calls it complete.
- Clearing the search immediately resets the Line Item section to unfiltered page one.
- A valid search appears as a removable active filter. A search with no matches says `No Line Items match this search`, not `No audit history yet`.

### Event filters and Line Item navigation

- `Event type` is a single-select over the existing semantic API values: `CREATED`, `PROPERTY_CHANGED`, `DEAL_ASSOCIATED`, `DEAL_DISASSOCIATED`, and `DELETED`, shown with localized labels.
- `Changed field` is a single-select over the existing monitored property API names. Selecting it also constrains type to `PROPERTY_CHANGED`; a conflicting non-property event type is prevented in the UI and rejected by the API.
- `Line Item reference` is an exact positive provider ID. A row's `Show history` action sets this value, applies the event filter, moves focus/attention to Recent changes, and starts at event page one.
- `From date` and `To date` use stable `DateInput` controls with `timezone="portalTz"`. The inclusive displayed end date becomes the exclusive start of the following portal-local day in the API. The card converts both portal-local boundaries to UTC instants, including DST transitions, before requesting.
- `Apply filters` applies the whole draft atomically. Two rapid applies still invalidate the earlier generation. Removing one filter or clearing all also starts event results at page one.
- All filters combine with logical `AND`.

### Active and empty states

- Render every applied search/filter as a removable `StatusTag`; render `Clear filters` whenever any query constraint is active.
- Keep draft controls visually distinct from applied tags so the user knows which state produced the visible results.
- With no filters and no retained Line Items/events, show `No audit history yet` and the existing evidence-boundary explanation.
- With active event filters and zero events, show `No changes match the current filters`, keep the active tags visible, and offer `Clear filters`.
- With an active Line Item name search and zero Line Items, show `No Line Items match this search` while leaving the event section and its independent filters truthful.
- With no event filters and zero events but retained Line Items, show `No retained changes for this Deal`.
- Never label a capped or filtered subset as the complete history.

### Manual refresh

- Refresh performs one new page-one request for both sections using the currently applied Line Item search and event filters. It reads the newest local projection only; it does not trigger provider synchronization or a HubSpot read.
- Increment the global request generation and both section request IDs before starting. Any older initial, pagination, search, filter, or refresh response becomes ineligible to update state. Duplicate refresh activation is disabled while the refresh is in flight.
- Preserve the applied and draft controls. Replace both visible sections and both cursors atomically only after a successful response; do not append refreshed data to old pages.
- While refreshing, retain the current data with a subtle loading state. On failure, retain it with a localized non-destructive warning and retry action. Initial-load failure continues to use the full error view.
- Set `last refreshed` only on successful receipt, using client receipt time formatted with the user's locale and portal time zone. It is a UI freshness hint, not an audit timestamp or ingestion-health claim.
- There is no polling or automatic retry.

### Date/time grouping

- Keep the API's descending event order unchanged. Build contiguous display groups from each event's portal-local calendar day.
- Compare day keys in `portal.timezone` to label `Today`, `Yesterday`, or a locale-formatted full date. Invalid portal time zones retain the existing UTC fallback.
- Keep the exact localized timestamp on every event. Group headings do not copy, round, or mutate `occurredAt`.
- Equal-timestamp ordering remains the server's semantic-key order; grouping must not sort again.

## Backend/API design

### Smallest endpoint extension

Keep the existing route and optional section limits/cursors. Add these camel-case query parameters:

| Parameter | Applies to | Semantics |
|---|---|---|
| `lineItemSearch` | Line Items | Case-insensitive literal substring of latest retained Line Item name |
| `eventType` | Events | One existing `LineItemAuditType` API value |
| `field` | Events | One existing monitored-property API name, such as `quantity` or `unitPrice` |
| `lineItemId` | Events | Exact positive external Line Item ID |
| `from` | Events | Inclusive RFC 3339 instant |
| `to` | Events | Exclusive RFC 3339 instant |

The initial card request can still ask for both sections. Section-only pagination continues to send the other limit as zero, but it must also repeat the applied parameters for the requested section so the server can validate the cursor context. A Line Item pagination call need only repeat `lineItemSearch`; an event pagination call repeats the five event-filter parameters. The client should use one typed query object and deterministic serializer rather than hand-building unrelated URLs.

Add all six parameters to `HubSpotUiExtensionRequestAuthenticator`'s closed query-parameter allowlist. They remain inside the exact HubSpot v3 signed URI, so intermediary or caller changes fail authentication before query execution. Keep the existing 4,096-character raw-query bound; the individual limits below leave ample room for signed HubSpot metadata and cursors.

### Request validation and semantics

- Existing duplicate/blank rejection remains. Optional empty controls are omitted, not serialized as blank.
- `lineItemSearch`: apply Java `strip`; preserve internal characters and whitespace; require 2–100 Unicode code points; then compare `lower(latest.name) LIKE lower('%literal%')` under the database collation. Escape `%`, `_`, and the escape character so they are literal. Matching is case-insensitive, accent-sensitive, non-fuzzy, and not tokenized. `UNKNOWN`/`ABSENT` names do not match. No parameter means no name filter.
- Do not claim locale-specific case folding beyond PostgreSQL's configured collation. Document and test the configured local/test collation; changing it is deployment work, not request behavior.
- `eventType`: exact owned API enum spelling; unknown values fail `400 INVALID_REQUEST`.
- `field`: exact `MonitoredLineItemProperty.apiName()` value and mapped once to its stored provider name. `field` implies `PROPERTY_CHANGED`; `field` combined with another event type fails `400` rather than silently returning none.
- `lineItemId`: `[1-9][0-9]{0,254}` and exact match. A valid ID with no evidence in this authenticated Deal scope returns an empty event page; it does not return an enumeration-specific error.
- `from`/`to`: each is at most 64 characters and must parse as an RFC 3339 instant. `from` is inclusive, `to` is exclusive, and `from < to` when both exist. Either boundary may be used alone.
- All event filters are conjunctive and are evaluated after the existing history-observation boundary.
- Limits remain `0–20`; API result sets remain bounded; no total count is added.

### Application and DTO changes

- Extend `DealAuditQuery` with a Line Item query value and an event-filter value. Use focused immutable records rather than passing raw HTTP strings into the repository.
- Extend `DealAuditQueryFactory.QueryInput` to carry the canonical effective query and decode cursors only against that canonical context.
- Keep `ReadDealAudit` as the use-case coordinator and `DealAuditViewRepository` as the persistence boundary.
- Extend `DealAuditView.AuditEvent` and the public event DTO with `latestRetainedLineItemName`, represented by the existing explicit value state shape (`UNKNOWN`, `ABSENT`, or bounded `VALUE`). The repository derives it from the already joined LATEST coverage row; there is no per-event lookup and the field is explicitly not an event-time name.
- The frontend contract accepts the additive field and renders name plus the Line Item ID. Unknown/absent names become localized `Unnamed Line Item`, never an empty identity.
- Do not add applied filters, internal IDs, source IDs, query totals, or provider data to the response.

### Repository/query shape

- Keep a relevant-Line-Items CTE and ID keyset. Add the latest-name predicate before `ORDER BY external_line_item_id`, request `limit + 1`, and return no unbounded intermediate list to Java.
- Drive event reads from the Deal-context projection, apply owner/Deal/history-boundary/filter predicates in SQL, retain the existing descending keyset predicate and `limit + 1`, and join the immutable parent event and one LATEST snapshot in the same query.
- Select the Line Item name state in that query. Do not query names per result row.
- Keep exact Tenant and connection predicates on every participating table even where foreign keys make them inferable.
- No `OFFSET`, in-memory full-history filtering, full count, provider call, or N+1 query is permitted.

### Authorization, locking, and errors

- Signed-fetch authentication, exact app/account validation, account-to-connection resolution, active connection check, entitlement lock, `REPEATABLE_READ`, and enumeration-safe account failures remain unchanged.
- Valid filters that find nothing return `200` with empty requested sections. Invalid syntax, invalid combinations, malformed cursor, unsupported cursor version, or cursor/query mismatch return `400 INVALID_REQUEST` with the standard correlation envelope.
- Database/query failures retain the existing safe public mapping. Customer input and query strings must not appear in exception messages returned to the card, structured logs, or metric tags.
- Filters cannot weaken owner scope and cannot authorize another Deal or Line Item.

## Cursor/search/filter semantics

### Version 2 cursor format

Generate new opaque cursor version 2 values containing:

1. version and section discriminator;
2. requested Deal ID;
3. a SHA-256 fingerprint of a canonical, length-prefixed effective query for that section; and
4. the existing section position: Line Item ID, or event instant plus semantic key; and
5. an HMAC-SHA-256 over the complete preceding payload.

The canonical Line Item context contains only normalized `lineItemSearch`. The canonical event context contains `eventType`, `field`, `lineItemId`, `from`, and `to` in fixed order with explicit null markers. Page limit is excluded because changing page size does not change ordering or membership. The Deal and section remain explicit header fields.

The fingerprint keeps names and other customer input out of the cursor. Use a dedicated 32-byte cursor-integrity HMAC key with an explicit cursor-v2 domain-separation prefix; never reuse the HubSpot OAuth client secret or credential-encryption key. The configuration accepts one active key ID/key and one optional previous key ID/key for a short rotation window. It fails startup only when the audit endpoint is enabled and the active pair is absent or invalid. Verify the HMAC in constant time before accepting the decoded position. Removing a previous key may invalidate an open in-memory cursor and requires a page-one restart, which is acceptable because cursors are not durable state.

The HMAC rejects cursor tampering, but it does not replace signed-request authentication or Tenant/connection predicates as the authorization boundary. Continue to cap cursor text at 1,024 characters and use URL-safe base64 without padding.

### Compatibility and mismatch behavior

- Generate only v2 cursors after rollout.
- Decode existing v1 cursors only for a completely unfiltered request during one compatibility window. Reject v1 whenever the effective section query is filtered/searched because it cannot prove context binding.
- A cursor from `field=quantity` under `field=unitPrice`, another Line Item, date range, search term, Deal, or section fails with `400 INVALID_REQUEST`; it is never silently reused.
- Malformed base64, invalid length/version/position, trailing bytes, invalid HMAC, and fingerprint mismatch have the same safe invalid-request behavior.
- The card invalidates stored cursors before issuing any changed query. If a pagination request still receives `INVALID_REQUEST`, preserve visible data and offer `Restart results`, which fetches page one under the current query instead of retrying the bad cursor.

## Large-history strategy

### Options evaluated

| Option | Benefit | Cost/risk | Decision |
|---|---|---|---|
| Bounded rolling window | Constant render size while continuing arbitrarily far | Discards newer rows, needs back navigation, and makes `Load more` semantics surprising | Reject for P.7.B |
| Page navigation | Smallest render tree and clear positions | Requires previous/next state and more navigation in a narrow card; cursor stacks also need bounding | Roadmap option if usage proves necessary |
| Hybrid Load more with hard cap | Minimal change to the proven card, preserves scan context, bounds state | Broad queries stop at the cap and require narrowing | Adopt |

### Adopted limits

- Continue pages of 10 Line Items up to 50 accumulated unique Line Items.
- Continue pages of 20 events up to 100 accumulated unique events.
- Use named card-owned constants and never exceed the cap even if a malformed/duplicate-heavy response would do so.
- At the cap, remove `Load more` and show a localized notice that more retained results may exist and the user should search/filter or narrow dates. Do not say `all results`.
- Every new search/filter/Deal context starts from one bounded first page. Refresh also returns to page one.
- Deduplicate before applying the cap and preserve server order. A duplicate-only page may advance its cursor, but repeated/non-advancing cursor or no-new-identity behavior fails safely rather than looping.
- The backend continues to return at most 20 rows per section and does not load the complete result into memory.

## Database/index impact

### Expected migration

Create migration `008-line-item-watch-audit-usability.sql` and include it after `007` in `db.changelog-master.yaml`.

### Name search

- Enable `pg_trgm` with `CREATE EXTENSION IF NOT EXISTS pg_trgm` and add a partial GIN trigram expression index on `lower(name)` for LATEST snapshots with non-null names.
- Keep the existing owner/kind and external-ID indexes for scope and keyset ordering. PostgreSQL can combine/select the owner/relevance and trigram paths; verify with representative `EXPLAIN (ANALYZE, BUFFERS)` outside normal automated assertions.
- Deployment preflight must confirm the managed PostgreSQL role may install/use `pg_trgm`; migration failure blocks rollout rather than falling back to an unindexed wildcard scan.
- The index supports literal substring matching. Accent folding, fuzzy ranking, stemming, and property-value search are explicitly later work.

### Event filters

Extend `line_item_watch_audit_event_deal_context` with copied immutable `line_item_id`, `event_type`, and nullable `property_name`, backfilled from `line_item_watch_audit_event`.

- Make Line Item and event type non-null and enforce the same closed values/shape as the parent; property is present only for `PROPERTY_CHANGED`.
- Extend parent uniqueness and foreign-key consistency so copied Line Item and event type must match the parent event. Add a second nullable-property foreign key (effective for non-null property rows) plus the shape constraint; do not rely only on application correctness.
- Update `JdbcLineItemProjectionRepository` to write the copied filter dimensions whenever it rebuilds Deal context.
- Retain the existing base Deal chronology index for unfiltered and date-bounded reads.
- Add Deal-scoped chronology indexes with prefixes for `line_item_id`, `event_type`, and a partial non-null `property_name`, each followed by `(occurred_at DESC, semantic_key DESC)` and including the parent event ID as useful.
- Combined filters use the most selective applicable index with remaining predicates evaluated in SQL. Do not create every combinatorial index in P.7.B; inspect real plans and add another only with evidence.

Migration tests must cover fresh installation and upgrade from populated migration `007`, copied-dimension backfill, constraint rejection, rollback SQL, and continued parent chronology consistency.

### Isolation and write impact

The copied context values duplicate only immutable module-owned identifiers/enums already in the event. They modestly increase projection-write and index cost but prevent rare-filter scans across a large Deal history. The projection writer already replaces context rows in the same transaction, so no new cross-module writer or lock is introduced.

## Test strategy

### Backend unit and contract tests

- Query factory: defaults, valid normalization, literal wildcard escaping, min/max Unicode length, duplicate/blank parameters, closed enums/API property names, Line Item ID rules, timestamp bounds, `from < to`, and field/type compatibility.
- Cursor codec: v2 round trips, section/Deal/query binding, every one-filter mismatch, compound mismatch, v1 unfiltered compatibility, v1 filtered rejection, constant-time HMAC validation, malformed/tampered/trailing/oversized input, and timestamp bounds.
- Application/DTO: filters reach the repository unchanged as typed values; zero-limit skips only its section; event Line Item name covers `VALUE`, `ABSENT`, `UNKNOWN`, truncation, and no internal data leakage.
- Controller/error tests: optional filters preserve signed-fetch authentication and invalid input maps only to `INVALID_REQUEST` plus correlation ID.
- Architecture test: the read path remains free of HubSpot/provider-read dependencies.

### PostgreSQL integration and migration tests

Use synthetic local/Testcontainers rows, not CRM data, to cover:

- name matches before/after the first frontend page, case differences, literal `%`/`_`, unknown/absent names, no match, and Tenant/connection/Deal isolation;
- every event type, every monitored field mapping, exact Line Item, inclusive `from`, exclusive `to`, combined filters, deletion events, history-observation boundary, and empty filtered results;
- many pages with same and different timestamps, deterministic semantic-key tie breaking, no duplicates, no skipped events, and stable keyset advancement;
- large Line Item counts, searched Line Item keysets, and independent section queries;
- filter-context cursor mismatch at the HTTP boundary and correct empty/non-enumerating behavior for another valid Line Item ID;
- migration `008` fresh and `007 -> 008`, copied-dimension consistency, expected index definitions, and projection-writer rebuilds.

Performance review should seed enough local synthetic rows to compare representative unfiltered, name, type, field, Line Item, and date plans. Record plans during implementation review; do not make timing-only CI assertions.

### Frontend tests

- Typed query serialization and URL encoding for initial, section-only, search, combined filters, and date boundaries; no native fetch/custom headers/body.
- Search only after explicit submission, first-page reset, clear, invalid short input, and no client-side claim of complete search.
- Active tags, individual removal, clear all, `Show history`, exact event Line Item name/reference, and the three distinct empty-result families.
- Today/Yesterday/locale-date grouping in English and Hungarian, portal-zone midnight boundaries, DST start/end, invalid-zone UTC fallback, preserved exact timestamp, and unchanged equal-time input order.
- Refresh preserves query context, resets both cursors, atomically replaces sections, updates last-refreshed only on success, keeps old data on failure, prevents double activation, and performs no polling.
- Stale initial/page response after a search/filter change; rapid successive filter applies; rapid refresh; filter reset during request; out-of-order section responses; and stale refresh after Deal change.
- Load-more double click, duplicate IDs, duplicate-only/non-advancing page safety, no skipped order in fixture pages, and hard caps of 50 Line Items/100 events with the truthful cap notice.
- Deletion events under filters, explicit unknown Line Item name/state rendering, pagination failure preservation, cursor mismatch restart, malformed response, and safe localized errors.
- i18n key parity and SDK deprecation lint remain mandatory.

## Live acceptance strategy

P.7.B implementation acceptance can reuse the existing approved backend/PostgreSQL/tunnel/profile/card environment after separate implementation/deployment authorization. Do not restart, redeploy, change CRM records, or manufacture history as part of this planning task.

### Live acceptance

Use only already retained data on an approved Deal:

1. Confirm the card opens with the current Deal and its first pages through signed `hubspot.fetch`; backend authentication, app/account checks, active connection, entitlement, Tenant scope, and `no-store` response remain effective.
2. Search with a known two-or-more-character fragment from a retained Line Item name, verify matching beyond client-only behavior where the retained dataset permits, clear it, and verify unfiltered page one returns.
3. Apply each filter whose evidence already exists. For a valid filter with no retained match, verify the honest filtered-empty state rather than creating CRM data.
4. Activate `Show history` from a Line Item and verify every visible event is server-scoped to that exact item and displays its name/reference independently of the loaded Line Item page.
5. Remove individual filters and clear all; verify tags and results remain synchronized and the UI never presents a filtered/capped view as complete.
6. Apply a portal-date range around existing events and verify headings/timestamps in the portal time zone and current user locale. Verify an already approved English/Hungarian user context where available; do not mutate a user setting solely for acceptance.
7. Use Refresh and verify current local state reloads, applied controls remain, cursors return to page one, and last-refreshed changes only after success. Confirm no provider read, CRM write, or polling occurs.
8. Exercise ordinary load-more within available retained data and confirm no duplicate display. A live dataset is not required to reach the cap.
9. Exercise a safe available failure path and verify localized copy/correlation handling without exposing diagnostics. Environment reconfiguration, backend/tunnel restart, or deployment to manufacture an error requires separate authorization.

Record the Deal/account only in the ignored acceptance profile or operator notes permitted by the existing runbook; do not add customer identifiers or temporary tunnel URLs to tracked documentation.

### Automated-only acceptance

The following do not require and should not manufacture live CRM data:

- thousands of synthetic retained events/Line Items and many keyset pages;
- exactly 50/100 client caps and bounded rendered/state sizes;
- same-timestamp deterministic order, duplicate/no-skip pagination, and deletion/unknown variants;
- stale responses, rapid filter/search/refresh, reset-during-request, out-of-order resolution, and load-more double click;
- malformed/tampered/mismatched/v1-filtered cursors;
- migration/backfill/constraint/index behavior and performance-plan inspection;
- tenant/account/Deal isolation negatives and provider-free architecture checks; and
- all locale/time-zone/DST and failure-contract permutations.

## Rollout and backward compatibility

- Run migration `008` before serving filtered queries. Preflight `pg_trgm` privilege and index-build operational impact on the target database.
- The route, authentication, existing parameters, limits, ordering, and response fields remain backward compatible. `latestRetainedLineItemName` is additive; the P.7 parser already ignores unknown object fields.
- Deploying the backend before the P.7.B card is safe. Existing P.7 clients remain unfiltered and can consume v2 cursors opaquely.
- Accept unfiltered v1 cursors for one rollout window. No filter may use them.
- Deploy the card after backend readiness. A card refresh returns it to page one, so cursors are not durable client state and need no data migration.
- Rollback of the card leaves the additive backend harmless. Database rollback must account for the potentially expensive index/column removal and should follow the migration's reviewed rollback SQL; do not roll back by manual schema drift.

## Implementation sequence

### 1. Migration and projection consistency

Likely files:

- `backend/src/main/resources/db/changelog/changes/008-line-item-watch-audit-usability.sql` (new)
- `backend/src/main/resources/db/changelog/db.changelog-master.yaml`
- `backend/src/main/java/com/udmconsulting/modules/lineitemwatch/infrastructure/persistence/JdbcLineItemProjectionRepository.java`
- projection persistence integration tests

Work:

- add trigram support/name index;
- add/backfill/constrain event Deal-context filter dimensions and indexes;
- update context writes; and
- test fresh/upgrade migrations and invariant rejection.

Acceptance: migration `008` applies from both empty and populated `007` state; projection rebuilds preserve exact context; no customer row loses owner provenance; expected indexes exist.

### 2. Typed API query and context-bound cursors

Likely files:

- `DealAuditQuery.java`
- `DealAuditQueryFactory.java`
- `DealAuditCursorCodec.java`
- `HubSpotUiExtensionRequestAuthenticator.java`
- corresponding unit tests

Work:

- introduce typed search/event filters and validation;
- admit only the new bounded parameters through the signed-query allowlist;
- define canonical effective-query encoding and v2 cursor fingerprints;
- authenticate v2 cursor payloads with a domain-separated HMAC using a dedicated active/previous key ring;
- retain unfiltered v1 compatibility; and
- map every invalid/mismatch case to the existing safe request error.

Acceptance: every filter is closed/bounded/canonical; a cursor cannot cross any effective query, Deal, or section; no customer input is logged.

### 3. Filtered repository and additive event identity

Likely files:

- `DealAuditView.java`
- `DealAuditViewRepository.java`
- `ReadDealAudit.java`
- `JdbcDealAuditViewRepository.java`
- `DealAuditViewDto.java`
- `HubSpotDealAuditReadService.java` only as needed to pass the typed query
- existing backend unit/integration/DTO tests

Work:

- add name/filter predicates and keep stable keysets;
- return latest retained Line Item name state with each event;
- preserve owner/Deal/history-boundary predicates and zero-limit behavior; and
- add large synthetic query coverage and inspect query plans.

Acceptance: complete server-side search/filtering, deterministic bounded pages, no duplicates/skips, no N+1/provider read/OFFSET/full-history load, and unchanged authorization/locking.

### 4. Typed frontend client and formatting utilities

Likely files:

- `src/app/cards/lib/contracts.ts`
- `src/app/cards/lib/client.ts`
- `src/app/cards/lib/formatting.ts`
- their tests and fixtures

Work:

- model canonical applied query state and serialize it deterministically;
- validate additive event Line Item name state;
- add portal-day boundary/grouping utilities with DST tests; and
- retain strict response/error validation.

Acceptance: generated URLs exactly match the backend contract, names/dates remain bounded and safe, and all locale/time-zone cases are deterministic.

### 5. Card investigation UX and bounded state

Likely files:

- `DealAuditCard.tsx`
- `LineItemsSection.tsx`
- `EventsSection.tsx`
- focused new filter/group components if they reduce complexity
- `DealAuditCard.test.tsx`
- `en.json`, `hu.json`, and i18n validation

Work:

- add search, filter controls, active tags, clear/remove, and `Show history`;
- add manual refresh, last-refreshed hint, filtered empty states, and day headings;
- centralize request generations so every query transition invalidates stale work; and
- cap accumulated unique rows at 50/100 with a truthful narrowing notice.

Acceptance: all P.7.B interactions work with stable supported SDK components, no deprecated API/native fetch/CRM write/polling is introduced, and race/cap tests pass.

### 6. Documentation and complete verification

Likely files:

- `docs/architecture/deal-audit-api.md`
- `docs/architecture/deal-app-card.md`
- `docs/development/testing.md`
- trust/operations docs only if implementation evidence changes their current statements
- `docs/product/roadmap.md`

Verification expected during implementation:

```sh
cd backend && ./mvnw verify
cd src/app/cards && npm ci
cd src/app/cards && npm run typecheck
cd src/app/cards && npm run lint
cd src/app/cards && npm run format:check
cd src/app/cards && npm test
hs project lint
hs project validate --profile <approved-profile>
git diff --check
```

Run secret scanning through the repository's established mechanism if one is available at implementation time; do not invent a passing result. Live acceptance, upload, and deployment remain separate explicit gates.

Acceptance: architecture/API/card/testing documentation describes implemented reality, routine automated checks pass, live-only checks are clearly separated, and branch/status/migration/security impacts are reported.

## Phase assessment

All P.7.B P0 items reasonably fit one phase. The database read-projection extension is a migration and index change, but it supports the same use case and does not justify a separate architecture phase. Implement in the sequence above so the backend remains backward compatible before the card begins sending filters.

Full property-value search is the only investigated item that materially expands semantics/index/privacy scope and is intentionally roadmap work. Exports, saved views, deep links, analytics, notifications, configuration, reconciliation, and administration likewise require separate approval.
