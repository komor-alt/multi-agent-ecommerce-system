# Shopify After-Sales Connector

## Scope

The after-sales connector is selected with `ECOM_AFTERSALES_CONNECTOR`:

| Mode | Behavior |
|---|---|
| `mock` (default) | Deterministic local fixtures and an in-process idempotent execution ledger for tests/demo only. |
| `shopify` | Shopify Admin GraphQL read-through for orders and fulfillment status/events, plus a live granted-scope preflight. No Shopify mutation is implemented. |

Shopify requests use `https://{shop}.myshopify.com/admin/api/{version}/graphql.json` and the `X-Shopify-Access-Token` header. API version defaults to `2026-10` and is configurable. The transport uses bounded connect/read timeouts and can be replaced with a fake transport in contract tests. Tokens, response bodies, customer addresses, and raw upstream errors are not logged or written to reports.

Order identifiers are accepted as a numeric Shopify Order GID (`gid://shopify/Order/{id}`) or a Shopify order name such as `#1001`, `EN1001`, or `1001-A`. An optional `name:` prefix is accepted. Names are restricted to a small safe character set and passed as GraphQL variables to an exact `name:` search; user input is never interpolated into GraphQL source. Results are checked for an exact name match. `OrderSnapshot` and `ShipmentSnapshot` are read-through snapshots; this implementation does not create a local Shopify mirror or change the database schema.

## Scopes And Access Window

Before order or fulfillment reads, the connector queries `currentAppInstallation.accessScopes`. Effective order-read capability is `read_orders || write_orders`, because Shopify's `write_orders` scope includes order read capability; `AccessScopeStatus.grantedReadOrders` separately records whether `read_orders` itself was granted, while `effectiveCanReadOrders`/`readOrders()` report the effective capability. `read_all_orders` is reported separately as a capability only; without it, Shopify exposes at most the default recent-order window (60 days). A missing order is therefore reported as `SHOPIFY_ORDER_NOT_FOUND_OR_SCOPE_LIMITED`, not represented as an authorized historical lookup. `write_orders` never enables a mutation in this implementation.

The connector maps only returned Shopify values: received amount/currency, payment and fulfillment states, destination country, tracking IDs, fulfillment timestamps, and tracking events. Absent estimated-delivery data maps to zero/unknown rather than a fabricated promise. Carrier claim, delivery-proof, product-evidence and damage-photo methods fail closed until their sources and scopes are explicitly integrated.

## Write Safety

`ECOM_SHOPIFY_WRITE_ENABLED` defaults to `false`, and `ECOM_SHOPIFY_ALLOWED_WRITE_ACTIONS` defaults to empty. The existing `ExecutionService` still owns the approval snapshot check, current order precondition, persisted execution job, lease and idempotency key. However, Shopify `execute` and `findExecution` currently return terminal fail-closed errors because no supported mutation plus external result-reconciliation contract is implemented. Even if the flag, allow-list and `write_orders` scope are set, the connector does not issue a mutation or claim a successful result. In particular, this project does not implement `refundCreate`; no real payment/refund action is made.

The Mock/Sandbox connector remains the only implementation of the full `execute`/`findExecution` idempotency contract. Its process-local ledger is not evidence of a real Shopify side effect or durable external exactly-once behavior.

## Error Classification

| Condition | Category | Stable code pattern |
|---|---|---|
| 401/403, missing required scope, invalid identifier, GraphQL validation/access error | `TERMINAL` | `SHOPIFY_HTTP_AUTHORIZATION_FAILED`, `SHOPIFY_READ_ORDERS_SCOPE_MISSING`, `SHOPIFY_GRAPHQL_*` |
| 408, 429, 5xx, timeout, transport failure, Shopify throttling/internal error | `RETRYABLE` | `SHOPIFY_HTTP_*`, `SHOPIFY_TIMEOUT`, `SHOPIFY_TRANSPORT_FAILURE`, `SHOPIFY_GRAPHQL_RETRYABLE_ERROR` |
| Non-empty GraphQL `userErrors` | `TERMINAL` | `SHOPIFY_USER_ERROR` |

Only stable codes are surfaced. Upstream error messages and payloads are intentionally discarded.

## Configuration

```dotenv
ECOM_AFTERSALES_CONNECTOR=mock
ECOM_SHOPIFY_SHOP_DOMAIN=
ECOM_SHOPIFY_ADMIN_ACCESS_TOKEN=
ECOM_SHOPIFY_API_VERSION=2026-10
ECOM_SHOPIFY_CONNECT_TIMEOUT_MS=2000
ECOM_SHOPIFY_READ_TIMEOUT_MS=5000
ECOM_SHOPIFY_WRITE_ENABLED=false
ECOM_SHOPIFY_ALLOWED_WRITE_ACTIONS=
```

To select `shopify`, provide the store domain and a secret-managed Admin API access token. Missing or malformed configuration fails application startup closed. Directly constructed connector instances also fail closed before making a request. Never place the token in source, committed environment files, test output or logs.

## Verification Boundary

Connector contract tests use a fake GraphQL transport; execution recovery tests use the Mock connector and fault injection. No real Shopify credentials or API calls are used by the default Java test suite. These tests verify request construction, scope handling, mapping, error classification and local idempotency/recovery contracts only. They do not prove that a real development store accepted the query or that any Shopify write succeeds. Real store validation and any future write/mutation support require separate authorization and review.
