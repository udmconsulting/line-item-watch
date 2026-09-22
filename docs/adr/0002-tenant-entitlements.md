# ADR 0002: Activate modules through tenant entitlements

- Status: Accepted
- Date: 2026-09-22

## Context

Tenants may use one or multiple Product Modules, and activation may come from Private Beta access, a trial, manual approval, an enterprise contract, or a future billing system. Product logic must not be coupled to pricing-plan names or a billing vendor.

## Decision

Represent activation as a tenant/module capability entitlement. A Product Module asks whether its capability is entitled; it does not inspect plan names or the entitlement's commercial source. The entitlement source is provider- and billing-independent.

## Consequences

- Multiple modules can be activated independently for one tenant.
- Pricing and billing can evolve without rewriting module business rules.
- Platform Core owns entitlement resolution and enforcement boundaries.
- The exact entitlement schema, lifecycle, administrative workflow, and billing integration are TBD and are not implemented by this ADR.

## Alternatives rejected

- **Plan-name checks inside modules:** rejected because they couple business capability to mutable commercial packaging and duplicate activation rules.
- **No explicit activation boundary:** rejected because it cannot safely support different tenant/module combinations.
