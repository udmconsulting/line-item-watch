# Product roadmap and idea backlog

## Purpose and use

This is the persistent backlog for valuable product, UX, operational, and technical ideas that are intentionally outside the currently approved implementation scope. It is not an authorization to implement, promise dates, or a substitute for Beta readiness decisions.

Priorities:

- **P0 — must-have / launch blocker:** required for the approved phase or credible Private Beta readiness.
- **P1 — high-value next:** strong user or operational value after current blockers.
- **P2 — useful enhancement:** worthwhile after evidence validates the core workflow.
- **P3 — exploratory / later:** needs discovery or a demonstrated use case.

Statuses used here are `Implemented — accepted`, `Implemented — pending acceptance`, `In progress`, `Planned`, `Backlog`, and `Discovery`. Move an item to implementation only with explicit scope approval, then update its status and link the implementation plan/decision.

## P0 — must-have / launch blocker

| Title | Status | Problem / user value | Possible scope | Dependencies / considerations |
|---|---|---|---|---|
| P.7.B server-side Line Item name search | Implemented — accepted | Investigators must find an item beyond already loaded card rows. | Case-insensitive literal substring search of latest retained names, stable keysets, query index. | Migration `008`, `pg_trgm`, privacy-safe query logging; arbitrary property values excluded. |
| P.7.B event filters | Implemented — accepted | Long histories are not investigable without narrowing by meaning and time. | Semantic event type, changed field, exact Line Item, inclusive/exclusive time range. | Existing owned enums/properties, Deal-context filter indexes, provider-free read. |
| P.7.B Line Item-to-history and event identity | Implemented — accepted | Users cannot move directly from current state to that item's changes, and an event may lack a loaded name. | `Show history`, exact server filter, event DTO with latest retained name state and ID. | Additive contract; no N+1 reads; unknown/deleted identity states. |
| P.7.B active-filter and empty-state UX | Implemented — accepted | A filtered subset can be mistaken for complete history. | Applied tags, individual removal, clear all, filtered/unfiltered empty states, cap notice. | English/Hungarian copy and accessible stable SDK components. |
| P.7.B manual refresh and date grouping | Implemented — accepted | Users need deliberate freshness and readable chronology without polling. | Page-one refresh preserving query context; last-refreshed hint; Today/Yesterday/locale dates. | Race invalidation, portal timezone/DST, no provider synchronization claim. |
| P.7.B bounded large-history behavior | Implemented — accepted | Repeated Load more currently grows React state/rendering without limit. | Hybrid Load more capped at 50 Line Items and 100 events; narrow-query guidance. | Query filters must cover full data; no complete-history claim. |
| P.7.B filter-bound cursors | Implemented — accepted | Reusing a position under a different query can skip or mix results. | Opaque v2 cursor with canonical query fingerprint; safe mismatch error; unfiltered v1 window. | Backward-compatible rollout and stable canonicalization. |
| P.7.B large-history and race tests | Implemented — accepted | Normal examples do not prove ordering, caps, or asynchronous safety. | Synthetic many-page datasets; duplicate/no-skip order; rapid filter/refresh/reset; cursor mismatch. | Testcontainers and frontend deferred-response fixtures; no manufactured CRM data. |
| P.8 reconciliation and coverage recovery | Implemented — accepted | Webhook/signal gaps could degrade audit coverage without a safe recovery path. | Tracked-scope scheduled/manual reconciliation, persistent gap state, provider-free replay/rebuild, terminal-signal recovery, verified replay anchors, policy-gated retention mechanics, and a minimal localized warning. | Migration `009`, ADR 0010, deterministic reconstruction, closed metrics, operator CLI, and controlled live acceptance complete; retention remained preview-only. |
| P.9 GCP production foundation | In progress | Private Beta needs stable production ingress and repeatable, recoverable delivery without speculative HA cost. | Terraform-managed separate GCP projects; Cloud Run service/jobs; zonal PostgreSQL 18; production load balancer/domain; staging `run.app`; WIF/digest promotion; secrets, telemetry, backups/PITR, and runbooks. | Manual shared bootstrap: **COMPLETE**. Staging S1 foundation: **COMPLETE / PARKED** at 58 Terraform addresses with `NO CHANGES`. Runtime: **NOT DEPLOYED**. S2 artifact bootstrap: **NOT STARTED / NEXT**. S1H bootstrap hardening: **DEFERRED / PRE-PRODUCTION REQUIRED**. Production: **NOT PROVISIONED**. |
| P.9 UI E2E and synthetic monitoring delta | In progress | Foundation acceptance needs customer-facing regression evidence and ongoing read-only UI availability signals. | Playwright customer E2E, visual regression, staging post-deploy smoke, production read-only synthetic monitoring, Cloud Monitoring alert integration, failed-check evidence package, and optional AI-assisted diagnosis. | Repository implementation is complete pending staging/prod provisioning review and live acceptance. Uses isolated fixtures, protected auth state, non-mutating checks, and deterministic pass/fail authority. |
| Customer connection recovery and disconnect | Planned | Beta customers need a supported way to recover reauthentication and end access. | Admin-visible connection status, reauthorize/disconnect actions, safe lifecycle messaging. | OAuth lifecycle, truthful activity audit, destructive-action confirmation, data-retention policy. |
| Production reliability and recovery gates | In progress | Private Beta cannot rely on local-only monitoring or unverified recovery. | Managed secrets/storage, backups and restore verification, alert delivery, runbooks, deployment/rollback. | GCP `europe-west1` selected; repository definitions exist in P.9, while provisioning, restore acceptance, alert routing, and vendor/legal acceptance remain gated. |

## P1 — high-value next

| Title | Status | Problem / user value | Possible scope | Dependencies / considerations |
|---|---|---|---|---|
| CSV export | Backlog | Support and compliance users need the filtered audit dataset outside the card. | Export applied filters, stable columns/IDs/timestamps, bounded synchronous path and download metadata. | Authorization parity, CSV injection protection, privacy, retention, and large-result threshold. |
| PDF audit report | Backlog | Users need a human-readable report suitable for sharing or evidence. | Branded report with Deal context, applied filters, generation timestamp, coverage disclaimer, pagination. | Rendering service/library, localization, data classification, and immutable-report expectations. |
| Full-text/property-value search | Backlog | Name-only search cannot find a remembered commercial value or historical change. | Explicit selected-field exact search first; later partial/fuzzy search over current and/or event values. | Typed canonical semantics, privacy, indexes, sensitive query logging, retention, and performance evidence. |
| Saved filters | Backlog | Investigators repeatedly construct the same views. | User- or tenant-scoped named views for event/date/Line Item criteria. | Identity/ownership model, persistence, configuration UI, migration/versioning, privacy. |
| Compare two dates | Backlog | Users want to understand effective Line Item state at two business moments. | Reconstruct state at A and B and highlight field/membership differences. | Deterministic temporal projection, coverage boundaries, deletion semantics, timezone choice. |
| Diff-focused view | Backlog | Full event detail can obscure which fields actually changed. | Collapse to changed fields and optionally aggregate consecutive edits without losing evidence links. | Preserve event ordering/identity and unknown-state semantics. |
| Deep links to investigation context | Backlog | Support tickets need a repeatable path to an item/event/filter view. | URL-safe reference to Deal context, filters, selected item/event, and optional date window. | Avoid customer data in URLs, authentication, expiry/versioning, cursor exclusion, HubSpot navigation support. |
| Copyable support/event reference | Backlog | Users need to discuss one event without exposing customer identifiers. | Stable opaque event/support reference and copy action. | Existing public event ID, support lookup authorization, retention and non-enumerability. |
| Notifications for changes of interest | Discovery | Users may need action without continually opening the Deal. | Tenant/user rules, digest or near-real-time channels, quiet periods, links back to evidence. | Identity/preferences, delivery providers, consent, retries, deduplication, spam control. |
| Webhook/ingestion health | In progress | Operators need early detection of possible audit-coverage degradation. | Production dashboards/alerts over P.8 processing lag, exhausted-signal, reconciliation-age/result, replay-result, and suspected-gap metrics. | P.9 adds bounded metrics, Terraform alert definitions, and runbooks; live thresholds/routing require provisioning acceptance. |
| Coverage diagnostics | Backlog | `SIGNAL_FIRST`, `BASELINE_ANCHORED`, and `observedFrom` are accurate but hard to interpret. | Explain evidence boundary, latest checkpoint, detected gaps, and suggested recovery without overclaiming completeness. | Reconciliation/health model, localization, privacy-safe diagnostics. |
| Admin-configurable monitored fields | Backlog | Hardcoded properties may not match each customer's commercial model. | Allowlisted supported properties, display labels, normalization/type rules, webhook subscriptions. | Schema/index strategy, reconstruction compatibility, historical configuration versions, provider limits. |
| Retention configuration | Backlog | Tenants may require different evidence-retention policies. | Policy tiers for signals, semantic history, exports, and deletion lifecycle. | Legal basis, contracts, backups, auditability, minimum viable history, cascade behavior. |
| Cross-Deal/account audit explorer | Backlog | Some investigations start with a Line Item, user report, or time window rather than one Deal. | Separate account-scoped explorer with strong search/filter/export. | New UI surface, authorization model, scale/indexing, privacy, not a larger Deal card. |
| Data freshness and processing status | Backlog | A user cannot tell whether the latest retained projection may lag ingestion. | Show safe last-processed/observed indicators and degraded-state guidance. | Ingestion health, worker metrics, avoid exposing internal IDs or claiming provider completeness. |
| Installation-driven production lifecycle automation | Backlog | Install-ready min zero saves idle compute, while active customers need a continuously allocated worker instance. | A protected scheduled/control workflow reads a trusted active-installation signal and manages only the reviewed production min-instance value between zero and one. | Keep infrastructure credentials out of the business application; audit changes, fail safe to one, handle count races, and retain manual override/plan review. |

## P2 — useful enhancement

| Title | Status | Problem / user value | Possible scope | Dependencies / considerations |
|---|---|---|---|---|
| Asynchronous export jobs | Backlog | Large histories may exceed synchronous response and card limits. | Server-side job, bounded status polling, expiring download, cancellation and audit. | Needed only after export demand/size evidence; job infrastructure, storage, cleanup, notifications. |
| Configurable columns/fields | Backlog | Power users need denser or role-specific summaries. | Choose visible current fields and event detail columns; tenant/user defaults. | Saved preferences, responsive card constraints, monitored-field configuration. |
| Severity/importance classification | Discovery | Users need prioritization, but no change is universally severe. | Configurable importance rules, labels, sorting/filtering, optional notification routing. | Tenant-specific semantics, explainability, rule versioning; never hardcode universal severity. |
| Tenant/product configuration | Backlog | Future modules and policies need controlled tenant-level settings. | Module settings, defaults, validation, activity audit, admin UI. | Administration identity/authorization, configuration versioning, avoid generic framework design. |
| Dashboards and change analytics | Backlog | Teams may want trends beyond individual investigations. | Change volume, common fields, periods, health/coverage-qualified charts. | Separate surface, aggregation privacy, coverage caveats, performance and retention. |
| Investigation annotations | Discovery | Support teams may need to record why an event was reviewed. | Tenant-owned notes/tags linked to opaque event IDs. | New customer-write path, actor identity, permissions, moderation, retention, activity audit. |
| Bookmarked investigation handoff | Discovery | A colleague may need the exact view plus human context. | Saved context with owner, summary, and share controls. | Saved filters/deep links, identity, access control, customer-data handling. |
| Stable non-production acceptance ingress | In progress | Account-less quick tunnels have no uptime guarantee; hostname expiry interrupts acceptance and forces otherwise unnecessary redeployments. | Use the stable Cloud Run `run.app` origin emitted by staging Terraform. | Becomes the acceptance target only after separately authorized staging provisioning and HubSpot configuration; quick tunnels remain temporary until then. |
| Frontend tooling dependency maintenance | Backlog | The current Vitest/tooling dependency tree reports two moderate development-only advisories even though the production/runtime audit is clean. | Upgrade the affected test/tooling dependencies in a focused maintenance change and re-run frontend verification. | Avoid bundling dependency churn into feature work; confirm lockfile and Node compatibility and re-check the runtime-only audit. |

## P3 — exploratory / later

| Title | Status | Problem / user value | Possible scope | Dependencies / considerations |
|---|---|---|---|---|
| Investigation assistant | Discovery | Complex histories may benefit from guided summarization. | Evidence-linked summaries or question answering over a bounded selected history. | Product demand, strict grounding, privacy/subprocessors, cost, evaluation, and no unsupported claims. |
| Additional CRM/provider adapters | Discovery | Some customers may use another CRM. | Focused provider adapter for a proven shared module use case. | Concrete demand and capability spike; no universal CRM abstraction in advance. |
| Configurable anomaly heuristics | Discovery | Repeated reversals or unusual changes may deserve review. | Transparent tenant-configured heuristics with evidence links. | Adequate history/coverage, false-positive controls, analytics infrastructure, no universal severity. |

## Maintenance rule

When implementation or review reveals a valuable idea outside approved scope, add or update an item here with priority, status, user value, possible scope, and important dependencies/risks. Do not silently implement it or leave it only in chat or a TODO. Deduplicate against existing entries and link the relevant plan/ADR when the item becomes approved work.
