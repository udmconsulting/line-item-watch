# ADR 0001: Start with a modular monolith

- Status: Accepted
- Date: 2026-09-22

## Context

The project has a small-team/solo-development profile, one defined Product Module, and a Private Beta objective. It needs clear platform/module/provider boundaries and a path to later independent scaling without taking on distributed-system operations before evidence requires them.

## Decision

Start with one codebase and a modular monolith with explicit Platform Core, Product Module, provider adapter, and persistence boundaries. Prefer simple deployment and PostgreSQL. API and background processing may be logical runtime roles in the same codebase. Preserve structural extraction options but do not physically separate components by default.

## Consequences

- Operations, testing, deployment, and local development remain comparatively simple.
- Boundary ownership, dependency rules, and tests must prevent an unstructured monolith.
- A module or worker can be extracted later when measured scaling, isolation, or operational needs justify it.
- Internal calls and transactions can remain simple initially; teams must not use that convenience to bypass module ownership.

## Alternatives rejected for now

- **Microservices:** rejected for now because the operational and consistency cost is not justified by Beta scope or measured load.
- **Unstructured monolith:** rejected because it would couple provider, platform, and product concerns and make future change unsafe.
