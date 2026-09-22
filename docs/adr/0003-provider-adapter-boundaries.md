# ADR 0003: Separate providers through focused adapter boundaries

- Status: Accepted
- Date: 2026-09-22

## Context

HubSpot is the first provider and the feasibility work uses its APIs, models, and events. The product's commercial audit behavior must remain understandable and testable without making HubSpot the internal business domain. Future providers may have related but non-identical semantics.

## Decision

Business/product logic depends on focused application-owned capabilities, with external provider adapters translating provider authentication, APIs, DTOs, events, errors, and semantics. Provider-backed work is resolved through an internal Platform Connection and Tenant before business processing. Provider-backed internal records retain tenant and connection provenance.

Dependency direction is inward:

```text
provider adapter -> application-owned boundary -> business/module logic
persistence adapter -> application/module-owned boundary -> business/module logic
```

HubSpot is the first adapter, not the domain architecture. Shared internal concepts are used only where semantics genuinely align.

## Consequences

- Module rules can be tested without HubSpot clients or webhook DTOs.
- Provider-specific behavior, scopes, rate limits, errors, and security treatment remain isolated and explicit.
- Adding a provider is distinct from adding a Product Module.
- Adapters and mapping introduce some deliberate code, but they protect ownership and dependency direction.

## Explicit non-decision

No universal CRM interface, generic provider factory, speculative port catalog, or exact Java/package API is being created. Focused interfaces will be defined only when a concrete use case requires implementation. Provider-specific semantics may remain provider-specific.
