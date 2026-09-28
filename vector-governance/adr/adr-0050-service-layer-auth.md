# ADR-0050: Service-Layer Bearer-Token Auth for Engine HTTP Services

- **Status:** Accepted
- **Date:** 2026-07-16
- **Deciders:** Vector architecture & security
- **Supersedes/Relates:** ADR-0049 (bus authentication), ADR-0007 (shared source of truth / vendoring)

## Context

Wave 24 (ADR-0049) secured the **event bus** with scoped bearer-token auth and
CORS. But the eight Python engine HTTP services (`vector-routing`,
`vector-traffic`, `vector-hdmaps`, `vector-global`, `vector-offline-maps`,
`vector-reconstruction`, `vector-vision`, `vector-logistics`) still served every
endpoint with `Access-Control-Allow-Origin: *` and **no authentication** — anyone
who can reach the port can read live traffic/route/HD-map data. This is the
remaining gap in Pillar 8 (Security).

## Decision

Add a uniform **service-layer bearer-token enforcement** to every engine HTTP
service, using a single shared `vector-auth` package (ADR-0007: one source of
truth, vendored into each engine so it stays self-contained for its per-repo CI
gate):

- `vector-auth.Auth` enforces `Authorization: Bearer <token>` on every
  non-`/healthz` request **when a shared secret is configured**
  (`VECTOR_SERVICE_TOKEN`, falling back to `VECTOR_BUS_ROOT_SECRET`).
- Missing / malformed / wrong token → `401`.
- CORS is **scoped** to `VECTOR_CORS_ORIGINS` (comma-separated) in secured mode,
  and `*` in dev-anonymous mode (no secret set), replacing the hard-coded `*`.
- **Dev-anonymous fallback**: when no secret is configured the service runs
  unauthenticated with open CORS, so local development and the CI gate stay
  green without secrets. Enforcement is opt-in via environment configuration —
  never on by default with no way to disable.

### Wiring pattern (identical across all 8 engines)
```python
from vector_auth import Auth
# in make_server:  Handler.auth = Auth()
# in do_GET (after /healthz):  if not self.auth.enforce(self): return
# in _send:  for k, v in self.auth.cors_headers().items(): self.send_header(k, v)
```

### Out of scope (deferred)
- The Rust `vector-tile-server` (axum) — sits behind nginx in M1 (ADR-0015);
  a parallel axum auth layer is a follow-up.
- Token *issuance* at a composition root remains a documented DI boundary
  (as in ADR-0049); operators wire `VECTOR_SERVICE_TOKEN` per deployment.

## Consequences

**Positive**
- All eight Python engine services now enforce bearer-token auth and scoped
  CORS, closing the Pillar 8 service-layer gap.
- Single source of truth (`vector-auth`); vendored + drift-checked per engine.
- Zero behavior change in dev mode (no secret) — no breaking change for local
  dev or the gate.

**Negative / trade-offs**
- Every deployment must configure `VECTOR_SERVICE_TOKEN` to get enforcement;
  unconfigured services remain open by design (documented, not a silent hole).
- Adds a vendored copy per engine (justified by ADR-0007 isolation requirement).

## Validation
- `vector-auth` unit tests (7): dev-anonymous, secured 401/200, scoped CORS,
  bus-secret fallback.
- Per-engine `Secured*ServeTest` (live HTTP): no-token→401, wrong-token→401,
  valid-token→not-401, `/healthz`→200. All 8 engines green in dev mode
  (647 tests) and under the `act`+Docker gate.
