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

Provider-backed customer records retain `tenant_id`, `connection_id`, and external object or event identity as appropriate. The first module tables implement this invariant with `(tenant_id, id)` uniqueness on Platform Connection and composite foreign keys through Line Item identity, snapshots, and Deal associations. External IDs and future deduplication keys are not globally unique and must be connection/provider scoped.

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
- `GET /integrations/hubspot/oauth/callback` strictly bounds state/code/error input, consumes valid state once, exchanges the authorization code through `/oauth/2026-09/token`, introspects the transient access token through `/oauth/2026-09/token/introspect`, and only then creates or reconnects the Tenant/connection/entitlement transactionally. Browser outcomes are fixed non-reflective HTML with no-store/privacy headers and restrictive CSP.
- State expires after 10 minutes. Consumed/expired rows become eligible for deterministic, bounded opportunistic cleanup after the 24-hour replay-detection period.
- Refresh credentials use AES-256-GCM with a fresh 96-bit nonce and authenticated provider/connection context. The key and key ID are externally configured. Only ciphertext, nonce, key/cipher metadata, scopes, and credential generation are persisted.
- Token-issuance `hub_id` or `scopes` fields are neither required nor authoritative. Introspection must report an active bearer access token for the configured OAuth client, a valid `hub_id`, and a granted-scope set containing `crm.objects.deals.read` and `crm.objects.line_items.read`; additional scopes are allowed.
- Access tokens are created and introspected on demand for one operation and are never persisted or cached. Refresh locking, single-flight/coalescing, and automatic retry loops are deliberately absent until recurring provider reads justify them.
- Introspected account identity and required scopes are validated before installation activation or refreshed access-token use. Under-scoped installation grants are rejected and the newly issued refresh credential is revoked best-effort. Authoritative current-generation inactivity, account mismatch, or required-scope loss requires reauthentication, while structurally inconsistent metadata preserves the credential for operator diagnosis.
- Replacement refresh tokens use compare-and-advance on the durable Platform Connection credential generation before access-token introspection. A compare-and-advance conflict skips introspection; a later transient introspection failure preserves the committed replacement and `ACTIVE` lifecycle state. Confirmed invalid/revoked refresh credentials transition to `REAUTH_REQUIRED` only if the failed generation is still current.
- The internal uninstall service refreshes and introspects on demand, calls `/appinstalls/2026-09/external-install` only with the validated transient access token, and removes the credential/marks `DISCONNECTED` only for the operation's still-current generation. No public disconnect endpoint exists.
- Destructive stale provider responses return a retryable concurrent-change outcome and cannot mutate a newer credential installed by reconnect or another refresh.

Token issuance, token introspection, authenticated refresh-token revocation, and uninstall calls use the date-versioned `2026-09` HubSpot endpoints with bounded configurable network timeouts. Provider calls occur outside database transactions; only short state-consumption, installation-finalization, and generation-checked mutations are transactional.

## Implemented baseline read adapter

The focused `LineItemBaselineSource` implementation obtains one validated transient access token through the lifecycle above, verifies the requested Deal through `GET /crm/objects/2026-09/deals/{id}`, reads complete paginated Deal-to-Line-Item and Line-Item-to-Deal sets through `/crm/associations/2026-09/.../batch/read`, and reads each Line Item through `GET /crm/objects/2026-09/line_items/{id}`. Every association page must be an HTTP 200, `COMPLETE`, error-free batch response; HTTP 207, incomplete/canceled status, positive `numErrors`, non-empty `errors`, or a malformed envelope fails the synchronization without salvaging partial results. It does not enumerate the account or use a generic CRM client.

The selected direct property contract is `name`, `quantity`, `price`, `discount`, `hs_discount_percentage`, `recurringbillingfrequency`, `hs_recurring_billing_start_date`, `hs_billing_start_delay_days`, `hs_billing_start_delay_months`, and `hs_recurring_billing_period`. Text, decimals, dates, ISO-8601 periods, timestamps, identities, archived state, and association shapes are validated before module persistence. Provider object IDs remain opaque strings in the module/domain. At the HubSpot wire boundary only, CRM object IDs accept either non-blank textual JSON or exact integral JSON numbers and normalize both to decimal strings because live behavior observed in September 2026 returned a numeric association ID; this is compatibility with observed behavior, not a claim about HubSpot's documented schema. Other provider fields, including commercial properties and paging tokens, retain their strict parsing contracts. Unknown billing-frequency strings remain provider-controlled normalized text rather than a brittle enum.

Billing start derives only from the three direct date/day/month inputs, which must not conflict. `hs_billing_start_delay_type` is provider-calculated validation context and is not requested or persisted. The current public specification available during P.3 did not establish `hs_line_item_currency_code` as the exact authoritative Line Item currency property; no currency field is requested, invented, substituted, or included in the module contract.

A missing requested Deal is terminal. A Line Item disappearing after association discovery, association-state changes, rate limiting, server failures, timeouts, and network failures are retryable outcomes; authorization and malformed-contract failures are terminal. Messages are fixed and exclude tokens and provider payloads. P.3 supplies classification but no retry loop.

## Two different extension operations

### Adding a Product Module

Defines a new customer business capability. It may consume existing platform/provider capabilities and own new module state, but must not copy tenant, credential, or ingress infrastructure.

### Adding an External Provider

Adds authentication, account mapping, focused adapters, provider-specific event ingress/mapping, permissions, error handling, and operational/security treatment so an existing use case can work with that provider's genuine semantics.

One operation does not imply the other. Follow the separate checklists in [Adding a module or provider](../development/adding-a-module.md).

## Decisions deferred

Production key-management service/rotation, recurring HubSpot reads, webhook authentication/mapping, history-based audit reconstruction, access-token caching/coalescing, public disconnect UX, future providers, and provider-specific reconciliation limits remain deferred. HubSpot adapter code lives under `com.udmconsulting.integrations.hubspot` and depends inward on focused application/Platform Core boundaries.
