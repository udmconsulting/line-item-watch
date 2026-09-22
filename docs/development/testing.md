# Testing strategy

## Select the smallest meaningful level

- **Unit tests:** business/domain rules and application decisions without infrastructure where practical.
- **Integration tests:** provider clients/mappers, signature validation, persistence adapters, migrations, configuration, and external boundary behavior.
- **Focused end-to-end tests:** only critical customer and operational flows where the assembled behavior adds material confidence.

Avoid tests written only to increase coverage. The backend uses JUnit 6, Spring Boot test support, PostgreSQL 18.6 through Testcontainers, and ArchUnit. Run the full suite from `backend/` with `./mvnw verify`; Docker is required for persistence tests. H2 or mocked PostgreSQL behavior must not replace tests of migrations, constraints, or PostgreSQL-specific queries.

The foundation suite verifies Liquibase startup, Hibernate validation, Tenant and connection persistence, provider/account uniqueness, foreign keys, multiple connections per Tenant, tenant-scoped connection lookup, idempotent entitlements, cross-tenant entitlement isolation, deletion cascades, and package dependency rules.

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
