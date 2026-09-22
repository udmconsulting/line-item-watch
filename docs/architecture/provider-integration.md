# Provider integration

## Principle

HubSpot is the first external provider, not the business domain. Provider adapters translate authentication, API models, events, errors, and semantics into focused application-owned capabilities. Business logic uses internal concepts where semantics genuinely align—for example, a `CommercialItem`, snapshot, or change—not HubSpot SDK types or webhook DTOs.

Provider-specific behavior remains provider-specific when a shared model would erase important meaning. The project will not build one universal CRM interface, a generic provider factory, or unused provider ports. Focused boundaries are introduced from real use cases. See [ADR 0003](../adr/0003-provider-adapter-boundaries.md).

## Identity and tenant resolution

An internal **Tenant** is independent of an external account. A **Platform Connection** currently associates a Tenant with an application-generated UUID, an owned provider value, and a non-blank external provider account identity. `HUBSPOT` is the only implemented provider value. A Tenant may own multiple connections, while `(provider, external_account_id)` is globally unique so provider-account resolution identifies exactly one connection and Tenant. Connection lifecycle/status is deferred until OAuth and disconnect behavior give it concrete semantics.

HubSpot `portalId` must not be used as the internal tenant ID. Inbound traffic is validated and resolved in this order:

```text
provider + external account identity
  -> Platform Connection
  -> Tenant
  -> explicit tenant context
  -> business processing
```

Provider-backed customer records will retain `tenant_id`, `connection_id`, and external object or event identity as appropriate. The foundation contains no provider-backed business records yet. When the first such table is added, its migration must prevent pairing a Tenant with another Tenant's Platform Connection through an appropriate supporting uniqueness and composite foreign-key structure. External IDs and deduplication keys are not globally unique and must be connection/provider scoped.

## HubSpot adapter requirements

- Use supported current APIs; do not silently fall back to legacy endpoints.
- Request least-privilege scopes and document them with each capability.
- Protect OAuth credentials throughout creation, refresh, revocation, and removal.
- Validate webhook signatures and treat payloads as untrusted input.
- Treat a webhook as a signal and provider object/history APIs as authoritative reconstruction where available.
- Handle errors, rate limits, token failures, and unknown provider-controlled values explicitly and observably.
- Avoid retaining complete payloads unless a defined product, diagnostic, and retention need justifies it.

## Two different extension operations

### Adding a Product Module

Defines a new customer business capability. It may consume existing platform/provider capabilities and own new module state, but must not copy tenant, credential, or ingress infrastructure.

### Adding an External Provider

Adds authentication, account mapping, focused adapters, provider-specific event ingress/mapping, permissions, error handling, and operational/security treatment so an existing use case can work with that provider's genuine semantics.

One operation does not imply the other. Follow the separate checklists in [Adding a module or provider](../development/adding-a-module.md).

## Decisions deferred

Authentication implementation, provider mappings, credentials/key management, future providers, and provider-specific reconciliation limits remain TBD. Future HubSpot code belongs under `com.udmconsulting.integrations.hubspot` and must depend inward on focused application boundaries; no provider adapter package exists yet.
