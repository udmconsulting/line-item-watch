# Product overview

## Purpose

Line Item Watch is the first module of a modular B2B SaaS. It addresses a gap in commercial recordkeeping: a user reviewing a Deal should be able to understand what changed on its commercial items, the previous and new values, when the change occurred, who made it when that identity is available, and when an item was created, associated, disassociated, or removed.

The initial users are commercial, revenue-operations, sales-operations, and account-management teams that need a useful audit history without manually reconstructing changes from current CRM values.

## Product direction

`LINE_ITEM_WATCH` is the only defined Product Module. The intended customer value is a reliable, understandable history of Deal Line Item changes, exposed in the user's workflow through a Deal App Card or timeline. A tenant may eventually activate one or several independently entitled modules, but no future module requirements are defined yet.

HubSpot is the first external provider, not the product's business domain. The architecture allows another provider to be integrated later through focused provider adapters when there is a concrete need. Adding a provider and adding a Product Module are separate operations.

## Validation state

### Technically proven

The feasibility spike against HubSpot platform `2026.09` passed:

- current Deal Line Item values and Deal associations are readable;
- `propertiesWithHistory` provides deterministic property history, including observed quantity `10 -> 8` and discount `10 -> 20` changes;
- property-change, creation, deletion, and association-change webhooks work;
- association creation and removal can be identified; and
- a deleted Line Item could not be recovered through the tested current API, including an `archived=true` read.

The accepted deletion approach is **MODEL B**: establish an initial baseline, retain the latest known item snapshot, and use that snapshot when a deletion signal arrives. See the [feasibility evidence](../../README.md#technical-feasibility-evidence).

### Not yet commercially proven

Product-market fit, willingness to pay, pricing, onboarding conversion, customer retention, Marketplace launch readiness, and the final App Card experience are not yet proven. Private Beta is intended to validate real external use. The technical spike proves provider capabilities, not commercial demand or production readiness.

## Current status

The Platform Core foundation and HubSpot OAuth installation/credential lifecycle are implemented. The target Private Beta architecture and remaining scope are documented; recurring provider reads, webhook processing, Line Item Watch audit behavior, and customer-facing Deal UI are not yet implemented.
