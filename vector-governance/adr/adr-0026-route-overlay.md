# adr-0026 — M2 live route-overlay — M1 × M2 integration

## Status

Accepted.

## Context

The M2 Navigation Engine (`vector-routing`, adr-0025) computes shortest-path routes
(Dijkstra/A*) from GeoJSON way features in a self-contained Python package, but exposes
no network interface. The M1 vertical slice shipped a self-served Map Display (adr-0015)
with a Rust tile-server (`vector-tile-server`) serving vector tiles over HTTP, but the
deployed MapLibre viewer cannot show routes. To make the navigation product visible and
testable end-to-end, the routing engine must be reachable over HTTP from the deployed map
viewer so that live routes can be drawn on the M1 map.

## Decision

Expose the M2 routing engine as a stdlib-only HTTP route service (`routing-http-svc`) inside
`vector-routing`, wrapping the existing engine:

- `GET /route?from=LAT,LON&to=LAT,LON` returns a GeoJSON `FeatureCollection` whose single
  `LineString` feature is the computed shortest path (and, where present, the input way
  features). No third-party web framework is used — only the Python standard library
  (`http.server`), preserving the zero-sibling-import, self-contained constraint of adr-0025.
- `GET /healthz` returns a 200 OK with a trivial body for liveness/readiness probes.

The M1 tile-server viewer fetches the routing HTTP API and draws the returned GeoJSON as an
overlay layer on the MapLibre map (live route-overlay).

In the local Docker deployment (adr-0016), `nginx` proxies `/route` to the routing
container (port 8081), so the browser viewer reaches the route service through the same
origin as the tile-server.

## Consequences

- The deployed M1 map can now render live routes computed by the M2 engine, closing the
  M1 × M2 integration loop without modifying the engine's core algorithms.
- `vector-tile-server` gains a runtime dependency on `vector-routing` (registry edge
  `vector-tile-server -> vector-routing`, type runtime); the overlay is a viewer-side
  HTTP consumer, not a build-time coupling.
- The routing HTTP service is stdlib-only, so it adds no runtime dependencies and keeps
  `vector-routing` self-contained and gate-green.

## Alternatives considered

- **Bundle routing into the tile-server (Rust).** Rejected: it would couple the engine
  (Python) into the Rust tier, breaking the mixed-by-layer boundary (adr-0003) and the
  self-contained constraint of adr-0025.
- **Have the viewer call the engine directly via a non-HTTP transport (e.g., message bus).**
  Rejected: the deployed browser viewer cannot consume the E2 bus; a plain HTTP GeoJSON
  endpoint is the simplest cross-origin, browser-friendly contract.
- **Use a third-party web framework (Flask/FastAPI) for the route service.** Rejected:
  adds runtime dependencies and violates the zero-dependency, self-contained constraint of
  `vector-routing`; the stdlib `http.server` is sufficient for a single-endpoint service.

This ADR lifts nothing in adr-0005 — the route-overlay is a product slice (M1 × M2), not
an E1–E9 platform concern.
