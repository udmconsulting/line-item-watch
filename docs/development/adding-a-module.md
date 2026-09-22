# Adding a Product Module or external provider

These are separate extension operations. Complete the applicable checklist during design and implementation; do not create speculative code for the other operation.

## A. Adding a Product Module

Product Module identity is the closed Platform Core enum `com.udmconsulting.platform.module.domain.ProductModule`. Adding a value requires both a code change and a Liquibase migration that updates the `tenant_entitlement.product_module` check constraint. This explicit extension point is not a module registry or plugin framework.

- [ ] Define a stable module identity and its customer-visible business responsibility.
- [ ] Confirm the requirement is not already owned by Platform Core or an existing module.
- [ ] Define the tenant/module entitlement the platform will evaluate; keep pricing-plan names out of module logic.
- [ ] Describe business/domain concepts, rules, use cases, and meaningful application boundaries without provider DTOs.
- [ ] Identify the provider capabilities and events actually required; reuse shared connection, credential, ingress, and job capabilities.
- [ ] Define module-owned persistence and tenant/connection provenance needs without accessing another module's tables or repositories.
- [ ] Define necessary API, App Card, timeline, or other exposure and its authorization boundary.
- [ ] Define structured context, processing status, failure alerts, and operational runbooks.
- [ ] Add domain tests, boundary integration tests, tenant-isolation tests, and focused critical-flow tests.
- [ ] Update the data inventory, minimization, retention/deletion, logging, and security/privacy review.
- [ ] Update business, architecture, development, operations, and trust documentation in the same task.
- [ ] Add an ADR only if the work materially changes architecture or its accepted boundaries.

Do **not** directly access another module's persistence, hard-code subscription plans, copy provider/platform infrastructure, create a new tenant/auth system, or create a new service/runtime by default.

## B. Adding an external provider

Provider identity is the closed Platform Core enum `com.udmconsulting.platform.connection.domain.Provider`. Adding a value requires both a code change and a Liquibase migration that updates the `platform_connection.provider` check constraint.

- [ ] Define provider identity and which concrete existing business use case it serves.
- [ ] Add Platform Connection support for the provider and document external account identity and lifecycle.
- [ ] Define external-account-to-connection-to-tenant resolution; never map an external object ID directly to a tenant.
- [ ] Design authentication, token/credential lifecycle, revocation, least-privilege permissions, and managed secret protection.
- [ ] Implement focused provider adapters for application-owned use cases; do not introduce a universal CRM abstraction or factory without a demonstrated shared need.
- [ ] Design authenticated provider event/webhook ingress and mapping through shared durable processing.
- [ ] Map provider concepts to internal concepts only where semantics genuinely align; preserve provider-specific behavior otherwise.
- [ ] Document permissions/scopes, supported API versions, provider assumptions, and unknown/external-value handling.
- [ ] Classify API errors and rate limits, define retry behavior, and preserve idempotency.
- [ ] Add logs, metrics, health signals, alerts, and diagnostic context without secrets or unnecessary customer data.
- [ ] Review data categories, location, retention, deletion, logging, credential exposure, and any new subprocessor impact.
- [ ] Add contract/boundary tests, provider integration tests, webhook-authenticity tests, tenant-isolation tests, and failure/rate-limit tests.
- [ ] Update provider, security, operations, data inventory, subprocessor, and local-development documentation.

Adding a provider does not create a new Product Module, entitlement model, tenant implementation, runtime, or database by default.
