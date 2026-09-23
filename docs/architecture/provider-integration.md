# Provider integration

## Principle

HubSpot is the first external provider, not the business domain. Provider adapters translate authentication, API models, events, errors, and semantics into focused application-owned capabilities. Business logic uses internal concepts where semantics genuinely align—for example, a `CommercialItem`, snapshot, or change—not HubSpot SDK types or webhook DTOs.

Provider-specific behavior remains provider-specific when a shared model would erase important meaning. The project will not build one universal CRM interface, a generic provider factory, or unused provider ports. Focused boundaries are introduced from real use cases. See [ADR 0003](../adr/0003-provider-adapter-boundaries.md).

## Identity and tenant resolution

An internal **Tenant** is independent of an external account. A **Platform Connection** associates a Tenant with an application-generated UUID, provider, non-blank external account identity, and lifecycle status. `HUBSPOT` is the only implemented provider. A Tenant may own multiple connections, while `(provider, external_account_id)` is globally unique. OAuth installation creates or reuses this mapping and never uses HubSpot account identity as the internal Tenant ID.

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

## Implemented HubSpot OAuth lifecycle

- `GET /integrations/hubspot/oauth/install` issues 256 bits of random state, stores only its SHA-256 digest, and redirects to HubSpot with Deal-read and Line Item-read scopes.
- `GET /integrations/hubspot/oauth/callback` strictly bounds state/code/error input, consumes valid state once, exchanges the authorization code through `/oauth/2026-09/token`, and creates or reconnects the Tenant/connection/entitlement transactionally. Browser outcomes are fixed non-reflective HTML with no-store/privacy headers and restrictive CSP.
- State expires after 10 minutes. Consumed/expired rows become eligible for deterministic, bounded opportunistic cleanup after the 24-hour replay-detection period.
- Refresh credentials use AES-256-GCM with a fresh 96-bit nonce and authenticated provider/connection context. The key and key ID are externally configured. Only ciphertext, nonce, key/cipher metadata, scopes, and credential generation are persisted.
- Access tokens are created on demand for one operation and are never persisted or cached. Refresh locking, single-flight/coalescing, and automatic retry loops are deliberately absent until recurring provider reads justify them.
- Returned account identity and required scopes are validated before installation activation or refreshed access-token use. Under-scoped installation grants are rejected and revoked best-effort; authoritative current-generation scope loss requires reauthentication.
- Replacement refresh tokens use compare-and-advance on the durable Platform Connection credential generation. Confirmed invalid/revoked refresh credentials transition to `REAUTH_REQUIRED` only if the failed generation is still current.
- The internal uninstall service refreshes on demand, calls `/appinstalls/2026-09/external-install`, and removes the credential/marks `DISCONNECTED` only for the operation's still-current generation. No public disconnect endpoint exists.
- Destructive stale provider responses return a retryable concurrent-change outcome and cannot mutate a newer credential installed by reconnect or another refresh.

Token, authenticated refresh-token revocation, and uninstall calls use the date-versioned `2026-09` HubSpot endpoints with bounded configurable network timeouts. Provider calls occur outside database transactions; only short state-consumption, installation-finalization, and generation-checked mutations are transactional.

## Two different extension operations

### Adding a Product Module

Defines a new customer business capability. It may consume existing platform/provider capabilities and own new module state, but must not copy tenant, credential, or ingress infrastructure.

### Adding an External Provider

Adds authentication, account mapping, focused adapters, provider-specific event ingress/mapping, permissions, error handling, and operational/security treatment so an existing use case can work with that provider's genuine semantics.

One operation does not imply the other. Follow the separate checklists in [Adding a module or provider](../development/adding-a-module.md).

## Decisions deferred

Production key-management service/rotation, recurring HubSpot reads, webhook authentication/mapping, access-token caching/coalescing, public disconnect UX, future providers, and provider-specific reconciliation limits remain deferred. HubSpot adapter code lives under `com.udmconsulting.integrations.hubspot` and depends inward on focused application/Platform Core boundaries.
