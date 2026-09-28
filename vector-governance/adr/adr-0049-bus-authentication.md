# ADR-0049 — Authenticated, CORS-scoped E2 Event Bus

- Status: Accepted
- Supersedes: (none)
- Superseded by: (none)

## Context

The networked E2 Event Bus (`vector-bus`, ADR-0029) shipped with **no
authentication** and `Access-Control-Allow-Origin: *`. Any client able to
reach port 8090 could publish/subscribe to any channel. That is acceptable for
local dev but is a production security gap (Pillar 8). `vector-security` already
ships a complete, tested `TokenBroker` (HMAC-scoped short-lived credentials,
ADR-0021) — it was simply **unwired** into the bus.

## Decision

Secure the bus at the wire boundary using the existing `TokenBroker`, with a
**backward-compatible dev mode**:

- `vector-bus` vendors `token-broker.js` (ADR-0007 / E5 DI rule forbids
  cross-repo imports, so the stable, stdlib-only broker is vendored with a
  provenance header; the bus test suite asserts the issue/validate contract so
  drift is caught locally).
- `Auth` enforces a scoped bearer token (header `Authorization: *** on
  `/publish` + `/ack`; query `?token=` on `/subscribe` SSE) **only when
  `VECTOR_BUS_ROOT_SECRET` (or `options.authSecret`) is set**. Without a secret
  the bus is anonymous (dev mode) — existing tests and local dev stay green.
- Scopes: `publish` for `/publish`, `subscribe` for `/subscribe` + `/ack`. Wrong
  scope → 403. Missing/invalid/expired token → 401.
- CORS is scoped to `VECTOR_BUS_CORS_ORIGINS` (comma list) in secured mode;
  `*` only in dev mode. The `Authorization` header is added to
  `Access-Control-Allow-Headers`.
- `NetworkBusClient` (vector-security) is made **auth-aware**: optional `token`
  is sent as `Authorization: *** (publish/ack) or `?token=` (subscribe). No
  token ⇒ anonymous (dev mode). Token *issuance* stays at the security
  composition root (DI boundary); the bus only validates.

## Consequences

- Positive: real authn + scope enforcement + scoped CORS on the bus; dev mode
  keeps CI/local-dev green without a secret; client backward-compatible.
- Positive: proven by bus tests (401/202/403/CORS) running against a bus
  constructed with a secret in-process.
- Negative: production MUST set `VECTOR_BUS_ROOT_SECRET` and issue tokens at the
  composition root; the bus does not self-issue tokens over HTTP (by design —
  issuance is the security service's job).
- Negative: token *revocation* requires shared broker state across instances;
  single-bus deployments are covered, multi-instance revocation is future work.

## Alternatives considered

- **mTLS / full API gateway**: stronger but far heavier; overkill for the
  internal bus at this stage. Rejected in favor of scoped bearer tokens.
- **Always-on auth (break dev)**: rejected — gate/local-dev must stay green
  without a secret; dev-anonymous mode satisfies that.
- **Import vector-security TokenBroker**: rejected — the E2/E5 DI rule forbids
  cross-repo imports; vendoring the stable broker is the compliant choice.

## Status

Accepted (Wave 24). Implemented in `vector-bus` (server.js + auth.js +
auth/token-broker.js + test/auth.test.js) and `vector-security`
(network-bus-client.js). Bus suite 55/55; security suite 56/56.
