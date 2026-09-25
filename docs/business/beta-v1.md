# Private Beta scope

## Intended outcome

Private Beta should let an external customer install and connect the product, activate `LINE_ITEM_WATCH`, establish a reliable initial state, process later provider changes, and view a useful audit history in a HubSpot Deal context. This is a high-level scope boundary, not an implementation backlog.

## In scope

- an external/customer installation path and OAuth-based HubSpot connection;
- creation or resolution of an internal Tenant and Platform Connection from the provider account;
- a `LINE_ITEM_WATCH` tenant/module entitlement;
- an initial Line Item baseline and maintenance of the latest known snapshot;
- shared, authenticated webhook ingress with durable, idempotent, retryable processing;
- reconstruction of property changes using authoritative provider history where available;
- creation, deletion, association, and disassociation handling, including MODEL B deletion behavior;
- an appropriate Deal App Card or timeline experience (exact UX is TBD);
- structured operational visibility, health monitoring, error tracking, actionable alerts, retry and terminal-failure state, and stuck/backlog detection;
- reconciliation as a safety net, while normal change detection remains event-driven;
- real external Private Beta usage and feedback; and
- the security, privacy, data-lifecycle, access, and backup baseline required for handling customer data.

## Out of scope unless separately approved

- paid billing implementation;
- AI features;
- implementation of multiple CRMs or providers;
- a complex administration portal;
- advanced analytics;
- unrelated Product Modules;
- a generic SaaS framework or dynamic plugin system;
- microservices; and
- Kubernetes.

## Success and readiness

Commercial Beta success criteria, service levels, pricing, retention periods, hosting, and exact UX remain TBD. Private Beta must not begin until the required security, observability, recovery, deletion, and operator-alerting controls are implemented and verified; this document does not assert that they exist today.

## Current implementation progress

HubSpot OAuth install/callback, account-to-Tenant/Platform Connection resolution, encrypted refresh-credential storage, on-demand refresh, connection lifecycle state, `LINE_ITEM_WATCH` entitlement activation, an internal uninstall service, and explicit one-Deal baseline/latest snapshot persistence are implemented. The baseline use case is an internal application service rather than a public API or scheduled scan. A customer-facing disconnect surface, webhook ingress, recurring reconciliation, audit reconstruction, deletion processing, and Deal UI remain future slices.
