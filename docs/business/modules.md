# Product Modules

## Definition

A **Product Module** is an independently identifiable customer capability with its own business responsibility. It owns its domain and application logic, module-specific state, APIs or UI exposure, tests, and operational behavior. Activation is determined by a tenant/module entitlement, not by pricing-plan names embedded in product logic.

`LINE_ITEM_WATCH` is currently the only defined module. Its identity is implemented as the closed Platform Core enum `com.udmconsulting.platform.module.domain.ProductModule`; adding a module requires an explicit code change and schema migration. The module owns its Line Item identity, BASELINE/OBSERVED checkpoints, immutable durable `LineItemChangeSignal` evidence, deterministic reconstruction, derived LATEST projection, semantic audit events, source provenance, and Deal context under `com.udmconsulting.modules.lineitemwatch`. Presentation is not implemented. No additional modules are specified.

## Platform Core and Product Module responsibilities

The Platform Core supplies capabilities shared across products:

- internal tenant identity and Platform Connections;
- provider authentication and credential lifecycle;
- entitlements and a billing-source-independent activation boundary;
- authenticated external event ingress, durable jobs, and routing;
- configuration, security, observability, and operational lifecycle.

A Product Module consumes these capabilities. It must not implement tenant resolution, billing, provider credential storage, or duplicate webhook infrastructure.

The implemented OAuth installation flow enables `LINE_ITEM_WATCH` by creating the `(Tenant, ProductModule)` entitlement. Row presence means enabled and absence means disabled; no plan, billing source, trial, expiry, or usage state is recorded.

The implemented baseline flow requires that entitlement both before the HubSpot read and inside the short snapshot-persistence transaction. The commit guard holds a shared lock on the entitlement row so disable cannot commit between authorization and snapshot commit. It consumes a focused provider port; module business and persistence code contain no HubSpot DTOs or OAuth mechanics.

The implemented webhook adapter maps authenticated HubSpot payloads into module signals and groups writes by resolved Platform Connection. Each group independently locks and revalidates its Tenant-scoped active HubSpot connection and `LINE_ITEM_WATCH` entitlement in the same short transaction as insert. Database uniqueness makes retry deduplication authoritative. The module signal type has no dependency on HubSpot DTOs, HTTP, signature mechanics, or OAuth tokens.

The module's processing worker claims durable jobs with PostgreSQL leases and `SKIP LOCKED`, serializes projection writes per Tenant/connection/Line Item, and completes projection plus processing state in one transaction. Reconstruction has no HubSpot dependency. A terminal signal blocks only later signals for the same Line Item so history cannot silently skip a failed fact.

## Independence rules

- A module owns its persistence and must not access another module's repositories or tables directly.
- Collaboration between modules requires a concrete need and an explicit boundary or event owned by the appropriate party.
- Provider DTOs and provider infrastructure must not become module business models.
- Entitlement checks answer whether a capability is active; they do not expose or hard-code a plan such as `PRO`.
- A new module does not imply a new service, deployment, database, tenant system, or webhook endpoint.

The initial runtime is a modular monolith: hard internal boundaries with simple deployment. Extraction or independent scaling is an option only when actual load or operational requirements justify it.

See [Modularity](../architecture/modularity.md) and [Adding a module or provider](../development/adding-a-module.md).
