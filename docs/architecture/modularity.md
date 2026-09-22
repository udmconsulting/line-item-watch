# Modularity

## Decision

The system starts as a **modular monolith**: one codebase and simple deployment, with explicit ownership and dependency boundaries. See [ADR 0001](../adr/0001-modular-monolith.md).

The design goal is **hard boundaries, simple runtime**. Architectural separability does not require immediate physical separation.

## Ownership and dependencies

- Platform Core owns tenant identity, Platform Connections, credential lifecycle, entitlements, generic event ingress/jobs, configuration, security, observability, and operational lifecycle.
- Each Product Module owns its business/application logic, state, exposure, tests, and operational behavior.
- A module may depend on explicit Platform Core application contracts, not infrastructure implementations.
- A module must not directly query or mutate another module's tables or repositories.
- Cross-module contracts or events are introduced only for a demonstrated collaboration requirement.
- Core business rules stay out of controllers, webhook handlers, provider adapters, and persistence adapters.

The implemented Java root is `com.udmconsulting`. Platform Core capabilities use `platform.tenant`, `platform.connection`, `platform.module`, and `platform.entitlement`; each persistence adapter sits below its owner in `infrastructure.persistence`. Product implementations will use `modules.<module>` and provider adapters will use `integrations.<provider>` only when real behavior exists. ArchUnit enforces the currently meaningful inward-dependency and cycle rules.

## Activation

Platform Core determines whether a tenant is entitled to a module. A module consumes a capability decision and remains independent of whether entitlement came from a beta grant, trial, contract, manual activation, or future billing system. See [ADR 0002](../adr/0002-tenant-entitlements.md).

## Scaling and extraction

API and background processing may be logical runtime roles within the same codebase. A module or worker may later be extracted or scaled independently when observed load, isolation, deployment cadence, or operational requirements justify the cost. Boundary ownership and tests should make that change possible; no speculative distributed infrastructure is built in advance.

## Anti-overengineering constraints

The initial design does not introduce microservices, Kubernetes, brokers without a selected need, service mesh, distributed event sourcing, separate databases per module, generic SaaS frameworks, provider factories, or dynamic plugin systems. New abstractions must serve a current use case and have clear ownership.
