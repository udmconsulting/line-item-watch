# Deployment principles

## Initial direction

Private Beta should use simple managed infrastructure and a modular-monolith deployment. Kubernetes, service mesh, and a microservice topology are not initial requirements.

The same codebase/artifact may expose logical runtime roles:

- **API:** public HTTPS endpoints and synchronous application interactions;
- **worker:** durable asynchronous provider, baseline, event, and reconciliation work.

Logical roles permit independent scaling or failure isolation later without requiring separate services now. The implemented foundation is one non-web Spring Boot 4.1.1 artifact built with Java 25 and Maven. It has no API/worker role configuration; that logical split remains deferred until asynchronous processing exists.

## Infrastructure expectations

- PostgreSQL 18 is the implemented database baseline. Liquibase is the sole schema-management mechanism, and Hibernate validates rather than creates or updates the schema.
- Every public endpoint requires HTTPS/TLS.
- Secrets and OAuth credentials require managed protection and encryption at rest.
- Production data requires managed backups, defined retention, and verified restores.
- Services require health monitoring, centralized logs, error tracking, and actionable alerts.
- Access is least-privilege, individual rather than shared, controlled, and auditable where appropriate.
- An EU processing region is preferred where practical, subject to provider evaluation.

## Deferred decisions

Docker Compose supplies PostgreSQL 18.6 for local development only. Hosting provider, managed database provider, region, network topology, job mechanism, secret/key-management service, observability vendors, backup retention, RPO, RTO, capacity, and deployment pipeline are TBD. These decisions are due before the affected production capability or Private Beta environment is approved; local Compose is not a production topology decision.
