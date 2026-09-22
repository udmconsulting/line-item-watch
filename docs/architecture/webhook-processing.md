# Webhook and event processing

## Target flow

```text
provider webhook
  -> HTTPS ingress
  -> signature/authenticity validation
  -> payload validation
  -> provider account identification
  -> Platform Connection and Tenant resolution
  -> durable receipt and deduplication
  -> entitlement-aware routing
  -> interested Product Module(s)
  -> completion, retry, or terminal failure state
```

Ingress and durable processing are shared Platform Core capabilities. Product Modules must not create separate copies of provider webhook infrastructure. One external event may be relevant to multiple entitled modules. The exact endpoint layout, job/queue mechanism, persistence model, deduplication keys, and transaction boundaries are TBD.

## Correctness rules

- Validate authenticity before customer business processing and treat all payload fields as untrusted.
- Resolve provider account to Platform Connection and Tenant before routing work.
- Persist enough non-sensitive context to retry, diagnose, and establish idempotency without retaining full payloads indefinitely by default.
- Scope deduplication to the provider/connection context; external event and object IDs are not globally unique.
- Assume duplicate, delayed, missing, and out-of-order deliveries.
- Retry only retryable failures. Expose terminal failures and stuck/backlogged work to operators.
- Do not force asynchronous provider work to finish inside the public HTTP request thread.

## Signal versus authoritative state

For HubSpot Line Item Watch, a webhook is a **change signal**. HubSpot object and property-history APIs are the authoritative source for reconstruction where available. Old and new state must not be inferred solely from webhook arrival order. Normal detection is event-driven; polling is not the primary change mechanism.

## Deletion: MODEL B

The feasibility spike established that a deleted Line Item cannot be reliably read after deletion, including with `archived=true`. Line Item Watch therefore requires:

1. an initial baseline;
2. maintenance of the latest known Line Item snapshot; and
3. use of the deletion event plus that latest known snapshot to record removal.

This decision is not open for redesign without new contradictory evidence and an explicit architecture decision.

## Reconciliation

Reconciliation is a safety net for missed signals or processing drift, not the normal primary detector. Its schedule, scope, rate-limit strategy, and recovery behavior are TBD and must be observable, retryable, tenant-scoped, and provider-aware.
