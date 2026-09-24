# Testing strategy

## Select the smallest meaningful level

- **Unit tests:** business/domain rules and application decisions without infrastructure where practical.
- **Integration tests:** provider clients/mappers, signature validation, persistence adapters, migrations, configuration, and external boundary behavior.
- **Focused end-to-end tests:** only critical customer and operational flows where the assembled behavior adds material confidence.

Avoid tests written only to increase coverage. The backend uses JUnit 6, Spring Boot test support, PostgreSQL 18.6 through Testcontainers, and ArchUnit. Run the full suite from `backend/` with `./mvnw verify`; Docker is required for persistence tests. H2 or mocked PostgreSQL behavior must not replace tests of migrations, constraints, or PostgreSQL-specific queries.

The suite verifies Liquibase/Hibernate startup, foundation isolation and constraints, OAuth state hashing/expiry/replay/bounded cleanup, AES-GCM authenticated encryption and tamper rejection, current `2026-09` token and introspection paths/forms, hardened fixed MVC outcomes, install/reinstall, introspected client/account/scope validation, entitlement activation, encrypted-only persistence, on-demand refresh, generation compare-and-advance, wrong-Tenant rejection, and uninstall. The provider regression fixture deliberately omits `hub_id` and `scopes` from token issuance and supplies them only through introspection. PostgreSQL race tests cover ordinary generation advancement plus invalid-grant, replacement, stale introspection, scope-loss, and uninstall responses after credential replacement or deletion/reinstall.

Live HubSpot acceptance is intentionally opt-in and is not part of Maven verification. It requires an approved isolated account, environment-supplied secrets, matching callback configuration, and an explicit cleanup plan.

### Live on-demand refresh acceptance

`HubSpotLiveRefreshAcceptanceIT` is a test-only harness for one controlled refresh of the existing local HubSpot installation. Maven Surefire does not select its `*IT` name during normal `./mvnw verify`; it runs only when explicitly selected with `-Dtest=HubSpotLiveRefreshAcceptanceIT` and fails before provider access unless `HUBSPOT_LIVE_ACCEPTANCE=true` is also present.

Run it only from `backend/` with the `local` Spring profile, the real environment-supplied HubSpot OAuth and credential-encryption configuration, the approved local PostgreSQL installation, and explicit authorization for a live refresh:

```shell
SPRING_PROFILES_ACTIVE=local \
HUBSPOT_LIVE_ACCEPTANCE=true \
./mvnw -Dtest=HubSpotLiveRefreshAcceptanceIT test
```

The harness resolves HubSpot account `149377304` through Platform Core, invokes the production on-demand access-token provider exactly once, validates the resulting token only as a nonblank transient value, and checks the connection, entitlement, encrypted credential, and generation state before and after the call. HubSpot may return a replacement refresh credential, in which case the production compare-and-advance path increases `credential_generation`. The harness never prints access or refresh tokens and must not be run casually or against production customer installations.

## Required critical coverage

As applicable to a change, verify:

- installation, connection identity, and entitlement gates;
- initial baseline and latest-snapshot maintenance;
- deterministic audit reconstruction from authoritative provider state;
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
