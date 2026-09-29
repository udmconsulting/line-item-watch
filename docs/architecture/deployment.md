# Deployment principles

## Initial direction

Private Beta should use simple managed infrastructure and a modular-monolith deployment. Kubernetes, service mesh, and a microservice topology are not initial requirements.

The same codebase/artifact may expose logical runtime roles:

- **API:** public HTTPS endpoints and synchronous application interactions;
- **worker:** durable asynchronous provider, baseline, event, and reconciliation work.

Logical roles permit independent scaling or failure isolation later without requiring separate services now. The implemented foundation is one Spring Boot 4.1.1 HTTP artifact built with Java 25 and Maven. It exposes only the public HubSpot OAuth install/callback endpoints; no worker role or public administration/disconnect API exists.

## Infrastructure expectations

- PostgreSQL 18 is the implemented database baseline. Liquibase is the sole schema-management mechanism, and Hibernate validates rather than creates or updates the schema.
- Every public endpoint requires HTTPS/TLS.
- Secrets and OAuth credentials require managed protection and encryption at rest.
- Production data requires managed backups, defined retention, and verified restores.
- Services require health monitoring, centralized logs, error tracking, and actionable alerts.
- Access is least-privilege, individual rather than shared, controlled, and auditable where appropriate.
- An EU processing region is preferred where practical, subject to provider evaluation.
- OAuth client credentials and the Base64-encoded AES-256 credential key are mandatory environment-supplied configuration. The application fails startup when these are absent or invalid.
- HubSpot provider traffic requires HTTPS outside explicit localhost/loopback development stubs and uses externally configurable bounded connect/read timeouts.

## Deferred decisions

Docker Compose supplies PostgreSQL 18.6 for local development only. The current external key configuration is suitable for controlled development/acceptance, not a production key-management decision. P.5 uses PostgreSQL processing rows plus an opt-in in-process scheduled poller; no external queue is required. Hosting provider, managed database provider, region, network topology, production worker role/topology, managed secret/key service and rotation, observability vendors, backup retention, RPO, RTO, capacity, and deployment pipeline are TBD.

## Deal App Card acceptance deployment

P.7 live acceptance may temporarily resolve the project-profile variable `LINE_ITEM_WATCH_API_ORIGIN` to an authorized Cloudflare quick-tunnel HTTPS origin. The same canonical origin must configure backend signature validation through `HUBSPOT_UI_EXTENSION_PUBLIC_BASE_URI`; the app manifest derives its least-privilege fetch prefix from the profile variable. The ignored acceptance profile contains an account ID and non-secret origin only, never OAuth or encryption credentials.

This is an acceptance-only exception, not the permanent hosting architecture. Quick-tunnel hostnames are ephemeral and must not be committed. Project validation is read-only, but `hs project upload --profile=acceptance` is an explicitly authorized HubSpot mutation that auto-deploys a build. Any origin change must be validated and uploaded again because it changes the deployed `permittedUrls.fetch` resolution.

Acceptance cleanup requires an intentional deployment decision before the temporary backend or tunnel is stopped. Otherwise the deployed card remains configured for an unreachable origin. The operator must choose an authorized rollback, replacement deployment, or explicitly accepted unavailable-card state, then stop local processes and remove the ignored profile. Permanent public hosting, managed secrets, and the production deployment pipeline remain deferred.
