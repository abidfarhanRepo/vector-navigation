# ADR-0052: Web composition root (vector-web)

- **Status:** Accepted
- **Date:** 2026-07-16
- **Deciders:** Vector architecture & security
- **Supersedes/Relates:** ADR-0003 (no sibling imports across repos),
  ADR-0007 (single source of truth / vendoring), ADR-0050 (service-layer
  auth), ADR-0009/0015 (Rust tile-server M1 slice), Wave 26c/26d (live traffic
  flow + bus wiring).

## Context

The web pillar (Pillar 5) was at 0%. A MapLibre viewer existed
(`vector-tile-server/static/index.html`) that calls `/route`, `/navigate`,
`/traffic` against `location.origin`, but **no single origin fulfilled those**
— the Rust tile-server only served `/`, `/healthz`, and `/tiles`. So the
viewer's routing/traffic calls would CORS-fail unless the user wired separate
backend origins via query params. There was no composition root presenting the
map and the engines behind one authenticated edge.

## Decision

Create a new **`vector-web`** repo: a stdlib-only composition root
(ADR-0003 — it proxies to engines over HTTP, never imports them) that:

- Serves the MapLibre M1 viewer at `/` (map + route + live traffic overlay).
- Proxies `/route` → `VECTOR_ROUTING_URL` (default `:8081`),
  `/navigate`/`/traffic` → `VECTOR_TRAFFIC_URL` (default `:8084`),
  `/tiles/{z}/{x}/{y}.mvt` → `VECTOR_TILE_SERVER_URL` (default `:3000`, the Rust
  `vector-tile-server`),
  resolving to the **same origin** the browser already uses — eliminating CORS.
- Enforces edge auth via the **vendored `vector_auth`** `Auth` (ADR-0007),
  gated by `VECTOR_WEB_TOKEN` (dev-anonymous fallback). `/healthz` stays open.
- **Forwards** the caller's bearer token to the backends so the engines' own
  auth (ADR-0050) also passes — one token, one edge.
- Vendors `vector_auth` and `vector_bus_client` (single-source, drift-checked
  like the engines). `vector_bus_client` is vendored for future live-bus UI
  wiring; not yet consumed.

The viewer is intentionally a focused M1 slice (route + nav + traffic) sharing
the same same-origin contract. The Rust `vector-tile-server` remains the MVT
tile source; `vector-web` can proxy `/tiles` to it (or be placed behind the
same ingress) — this wave wires routing/traffic; tile proxy is a follow-up.

## Consequences

**Positive**
- The web pillar has a working, production-shaped entry point: one origin, one
  auth token, live routing + traffic reroute visible on the map.
- Closes the CORS gap that made the existing viewer non-functional against a
  real backend.
- Composition root is isolated (ADR-0003): it depends on engines only via HTTP
  + their public API, so engines stay independently deployable.

**Negative / trade-offs**
- Adds a proxy hop (latency) between browser and engines; acceptable at M1 and
  behind a real ingress later.
- Tiles are still served by the Rust server; `vector-web` does not yet proxy
  `/tiles` (the viewer points at `location.origin + /tiles`, which only works
  if the web root also serves tiles — a follow-up wires `/tiles`→tile-server).

## Validation
- `vector-web` unit tests: proxy forwards `/route`, `/traffic`, and `/tiles`
  to a fake backend; `/healthz` open; with `VECTOR_WEB_TOKEN`, unauthenticated
  → 401, bearer → 200 and token forwarded (8 tests).
- Full `act`+Docker gate green (32/32 incl. vector-web).
- Live proof (manual, pre-commit): start routing + traffic + vector-web with
  `VECTOR_WEB_TOKEN`; `curl /route` through the web port returns a GeoJSON
  route; without the token → 401.
