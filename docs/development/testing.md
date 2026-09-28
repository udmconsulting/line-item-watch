# Testing strategy

## Select the smallest meaningful level

- **Unit tests:** business/domain rules and application decisions without infrastructure where practical.
- **Integration tests:** provider clients/mappers, signature validation, persistence adapters, migrations, configuration, and external boundary behavior.
- **Focused end-to-end tests:** only critical customer and operational flows where the assembled behavior adds material confidence.

Avoid tests written only to increase coverage. The backend uses JUnit 6, Spring Boot test support, PostgreSQL 18.6 through Testcontainers, and ArchUnit. Run the full suite from `backend/` with `./mvnw verify`; Docker is required for persistence tests. H2 or mocked PostgreSQL behavior must not replace tests of migrations, constraints, or PostgreSQL-specific queries.

The suite verifies Liquibase/Hibernate startup, foundation and module isolation/constraints, OAuth state hashing/expiry/replay/bounded cleanup, AES-GCM authenticated encryption and tamper rejection, current `2026-09` token and introspection paths/forms, hardened fixed MVC outcomes, install/reinstall, introspected client/account/scope validation, entitlement activation, encrypted-only persistence, on-demand refresh, generation compare-and-advance, wrong-Tenant rejection, and uninstall. It also covers normalized snapshot values, immutable baseline/replaceable latest semantics, explicit null clearing, complete association replacement, atomic rollback, idempotent/concurrent inserts, equal/out-of-order observations, cross-Tenant provenance rejection, same external ID across connections, exact focused HubSpot paths/property selection, association pagination, HTTP 207/partial/error-envelope rejection, malformed commercial fields and timestamps, and retryable versus terminal provider failures.

P.4 tests use only a synthetic HTTPS target, client secret, clock, IDs, and payloads. They cover strict canonical URI configuration; exact raw-body v3 signatures and timestamp bounds; JSON/media/encoding/body/batch bounds; all ten monitored properties including empty clearing; creation, deletion, association directions/actions, exact integral provider IDs, ignored semantics, out-of-order occurrence times, routing/grouping, fixed empty HTTP outcomes, and database retry surfacing. PostgreSQL 18.6 tests cover migration constraints, exact property retention before deletion, immutable insert/deduplication, same dedup key across connections, cross-Tenant rejection, atomic group rollback, concurrent duplicate capture, cascades, and deterministic disconnect/reauthentication/entitlement races.

P.5 unit tests cover baseline-first and signal-first reconstruction, explicit `UNKNOWN`, out-of-order replay, sparse known properties, same-timestamp lifecycle precedence, ambiguous equal-time property values, association transition collapse, opposite transitions, and deletion freeze. PostgreSQL 18.6 tests cover migration/constraints, the single shared P.3/P.5 LATEST writer, late-signal repair, audit/source/Deal-context projection, raw-evidence deletion without audit loss, `SKIP LOCKED` claims, per-Line-Item serialization, stale leases, bounded/terminal failure isolation, and transaction rollback/reclaim. ArchUnit continues to prove that module processing cannot depend on the HubSpot integration.

P.6 tests cover canonical-origin and app configuration, strict signed metadata and request shape, signature/query tampering, host/forwarding-header exclusion, account/connection/entitlement resolution, enumeration-safe failures, independent bounded cursors and zero-limit section skips, stable product fields, explicit value states, Unicode-safe truncation, localization-neutral errors and sanitized correlation logging, repeatable-read declaration, and a bounded worst-case response. PostgreSQL 18.6 tests run migration 006 both fresh and as an upgrade from populated migration 005 state, enforce copied chronology against the parent event, verify baseline-anchored and signal-first evidence boundaries, exclude pre-boundary events, retain historical disassociation/deletion relevance, freeze membership before post-deletion cleanup, exercise same-timestamp keysets, and prove Tenant/connection isolation for identical Deal IDs. ArchUnit proves that the read adapter has no provider-read dependency.

P.6.B tests cover server-owned correlation headers across success/error/OAuth/webhook outcomes, caller override rejection, P.6 body/header equality, MDC restoration and redispatch reuse, independent worker operation IDs, safe throwable diagnostics, closed public/provider/internal categories, bounded operation metric tags, and the machine-readable localization namespaces/error coverage/normalization vectors. PostgreSQL 18.6 tests cover migration 007, strict Tenant/connection ownership, truthful actors, install/reauthorization/reauthentication/disconnect/entitlement activity, idempotent no-ops, concurrent losing transitions, rollback in both directions, audit-insert failure, insert-only application access, and Tenant cascade. No live provider acceptance is part of P.6.B.

The provider regression fixture deliberately omits `hub_id` and `scopes` from token issuance and supplies them only through introspection. PostgreSQL race tests cover ordinary generation advancement plus invalid-grant, replacement, stale introspection, scope-loss, uninstall responses after credential replacement or deletion/reinstall, concurrent first baseline writes, and commit-guard rejection after a winning disconnect, reauthentication transition, or entitlement removal. Commit-guard tests hold the winning mutation uncommitted, identify its PostgreSQL backend PID, and require PostgreSQL to report the persistence backend as lock-blocked by that PID before permitting the mutation to commit.

Live HubSpot acceptance is intentionally opt-in and is not part of Maven verification. It requires an approved isolated account, environment-supplied secrets, matching callback configuration, and an explicit cleanup plan. Controlled P.4 genuine webhook delivery acceptance and cleanup were completed. Its retained local PostgreSQL evidence is deliberately not fabricated or redelivered for P.5.

### Retained P.4 signal-processing acceptance

`P5RetainedEvidenceAcceptanceIT` is an opt-in, provider-free harness for the already retained local P.4 database evidence. Maven Surefire does not select its `*IT` name during normal `./mvnw verify`. It fails before mutation unless `P5_RETAINED_EVIDENCE_CONFIRM=true` is present, runs transactionally, and never resolves or invokes any HubSpot provider client.

```shell
SPRING_PROFILES_ACTIVE=local \
P5_RETAINED_EVIDENCE_CONFIRM=true \
./mvnw -Dtest=P5RetainedEvidenceAcceptanceIT test
```

The retained sequence is signal-first and intentionally has no matching P.3 BASELINE. The harness verifies `CREATED`, the first property's `UNKNOWN` predecessor, directional association evidence collapsing to one logical transition, `REMOVED` cleanup retained as evidence without erasing frozen Deal context, auditable `DELETED`, sparse LATEST state, and identical semantic keys/counts after replay. It prints counts only and does not contact HubSpot.

### Live one-Deal baseline acceptance

`HubSpotLiveBaselineAcceptanceIT` is a non-destructive but customer-data-reading harness for the approved developer-test account and feasibility Deal. Maven Surefire does not select its `*IT` name during normal `./mvnw verify`. It fails before provider access unless `HUBSPOT_LIVE_BASELINE_CONFIRM=149377304:521984899298` exactly matches the approved account and Deal.

Run it only from `backend/` with the `local` Spring profile, real environment-supplied HubSpot OAuth and credential-encryption configuration, the approved local PostgreSQL installation, and explicit authorization for live reads and local snapshot writes:

```shell
SPRING_PROFILES_ACTIVE=local \
HUBSPOT_LIVE_BASELINE_CONFIRM=149377304:521984899298 \
./mvnw -Dtest=HubSpotLiveBaselineAcceptanceIT test
```

The harness resolves the installed account through Platform Core, confirms the active connection and entitlement, reads Deal `521984899298` through production OAuth/provider wiring, confirms feasibility Line Item `486464823492`, invokes observation synchronization twice, and verifies normalized BASELINE/OBSERVED/derived-LATEST rows, complete known Deal associations, Tenant/connection provenance, rerun uniqueness, and the absence of token/secret/raw-JSON persistence columns. It prints only counts and pass indicators, never credentials or commercial values. It still refreshes credentials on demand and reads live provider/customer data, so it must not be run casually or against production customer installations.

### Live on-demand refresh acceptance

`HubSpotLiveRefreshAcceptanceIT` is a test-only harness for one controlled refresh of the existing local HubSpot installation. Maven Surefire does not select its `*IT` name during normal `./mvnw verify`; it runs only when explicitly selected with `-Dtest=HubSpotLiveRefreshAcceptanceIT` and fails before provider access unless `HUBSPOT_LIVE_ACCEPTANCE=true` is also present.

Run it only from `backend/` with the `local` Spring profile, the real environment-supplied HubSpot OAuth and credential-encryption configuration, the approved local PostgreSQL installation, and explicit authorization for a live refresh:

```shell
SPRING_PROFILES_ACTIVE=local \
HUBSPOT_LIVE_ACCEPTANCE=true \
./mvnw -Dtest=HubSpotLiveRefreshAcceptanceIT test
```

The harness resolves HubSpot account `149377304` through Platform Core, invokes the production on-demand access-token provider exactly once, validates the resulting token only as a nonblank transient value, and checks the connection, entitlement, encrypted credential, and generation state before and after the call. HubSpot may return a replacement refresh credential, in which case the production compare-and-advance path increases `credential_generation`. The harness never prints access or refresh tokens and must not be run casually or against production customer installations.

### Live uninstall acceptance

`HubSpotLiveUninstallAcceptanceIT` is a destructive, test-only harness for one controlled uninstall of the existing local HubSpot installation. Maven Surefire does not select its `*IT` name during normal `./mvnw verify`. It runs only when explicitly selected with `-Dtest=HubSpotLiveUninstallAcceptanceIT`, and it fails before provider access unless `HUBSPOT_LIVE_UNINSTALL_CONFIRM=149377304` exactly matches the approved developer-test account.

Run it only from `backend/` with the `local` Spring profile, the real environment-supplied HubSpot OAuth and credential-encryption configuration, the approved local PostgreSQL installation, and explicit authorization for a destructive live uninstall:

```shell
SPRING_PROFILES_ACTIVE=local \
HUBSPOT_LIVE_UNINSTALL_CONFIRM=149377304 \
./mvnw -Dtest=HubSpotLiveUninstallAcceptanceIT test
```

The harness resolves the approved account through Platform Core and invokes the production uninstall service exactly once. That path may first refresh and introspect a transient access token, including a generation-guarded replacement refresh credential, before performing the real HubSpot uninstall and conditionally finalizing local disconnection. Durable credential generation may therefore advance before the credential is deleted. A successful uninstall preserves the Tenant, Platform Connection, and `LINE_ITEM_WATCH` entitlement, deletes the encrypted refresh credential, and marks the connection `DISCONNECTED`. The harness never prints access or refresh tokens and must never be run casually or against a customer or production installation. After acceptance, restoring the developer environment for P.3 requires a separate, explicitly authorized OAuth reinstall.

## Required critical coverage

As applicable to a change, verify:

- installation, connection identity, and entitlement gates;
- initial baseline and latest-snapshot maintenance;
- deterministic audit reconstruction from complete checkpoints and immutable evidence;
- creation, association, disassociation, and MODEL B deletion handling;
- duplicate, delayed, missing, and out-of-order event behavior;
- idempotent retry, retry exhaustion, terminal failure, and recovery;
- reconciliation without treating polling as normal primary detection;
- provider errors, rate limits, token expiry/refresh failure, and unknown external values;
- negative tenant-isolation cases for reads, writes, jobs, caches, and administrative operations;
- storage constraints, migrations, and deletion/lifecycle behavior;
- webhook signature rejection, input validation, authorization, secret redaction, and logging privacy; and
- alerts and operational status for important failure paths.

Provider integration tests must not use production customer data. Secrets stay outside source and test artifacts. Live tests require an approved isolated account, scoped credentials, explicit purpose, and cleanup plan.

## Definition of Done

A production change is not complete until meaningful automated verification passes at the appropriate levels; build, lint, format, and migration checks pass where available; `git diff --check` passes; changed/tracked files are checked for secrets; documentation is current; and architecture, tenant, security/privacy, configuration, dependency/subprocessor, and operational impacts are reported.

Document any intentionally unrun or unavailable verification and its risk. Do not invent passing results.
