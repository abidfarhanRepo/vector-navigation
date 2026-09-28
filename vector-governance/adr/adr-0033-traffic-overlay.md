# adr-0033 — Crowdsourced traffic live overlay — M1 × M5 integration

## Status

Accepted.

## Context

The Crowdsourced traffic engine (`vector-traffic`, adr-0032) estimates per-segment speed and
congestion (free/light/moderate/heavy/jammed, plus unknown for unmatched segments) from
crowdsourced GPS probe points over a road network (RoadSegments), but exposes no network
interface. The M1 vertical slice shipped a self-served Map Display (adr-0015) with a Rust
tile-server serving vector tiles and a MapLibre viewer, but the deployed viewer cannot show
live traffic. To make the traffic product visible and testable end-to-end, the traffic engine
must be reachable over HTTP from the deployed map viewer so live congestion can be drawn on
the M1 map.

## Decision

Expose the traffic engine as a stdlib-only HTTP traffic service (`traffic-http-svc`) inside
`vector-traffic`, wrapping the existing engine:

- `GET /traffic?probes=LON,LAT[,SPEED];...[&segments=...][&max_match_radius_m=...]`
  returns a GeoJSON `FeatureCollection` whose `LineString` features are the road segments with
  per-segment `congestion` (free/light/moderate/heavy/jammed/unknown) and mean speed (CORS `*`).
  Only the Python standard library (`http.server`) is used, preserving the zero-sibling-import,
  self-contained constraint of adr-0032. When `probes` is omitted the service uses the bundled
  Berlin sample probe/segment data.
- `GET /healthz` returns a 200 OK with a trivial body for liveness/readiness.

The M1 tile-server viewer fetches the traffic HTTP API and draws the returned GeoJSON as a
congestion-colored overlay layer on the MapLibre map (live traffic-overlay).

In the local Docker deployment (adr-0016), `nginx` proxies `/traffic` to the traffic container
(port 8084), so the browser viewer reaches the traffic service through the same origin as the
tile-server.

## Consequences

- The deployed M1 map can now render live per-segment congestion computed by the traffic engine,
  closing the M1 × traffic integration loop without modifying the engine's core algorithms.
- `vector-tile-server` gains a runtime dependency on `vector-traffic` (registry edge
  `vector-tile-server -> vector-traffic`, type runtime); the overlay is a viewer-side HTTP
  consumer, not a build-time coupling.
- The traffic HTTP service is stdlib-only, so it adds no runtime dependencies and keeps
  `vector-traffic` self-contained and gate-green.

## Alternatives considered

- **Bundle traffic into the tile-server (Rust).** Rejected: it would break the mixed-by-layer
  boundary (adr-0003) and the self-contained constraint of adr-0032.
- **Have the viewer consume the E2 bus directly.** Rejected: the browser viewer cannot consume
  the bus; a plain HTTP GeoJSON endpoint is the simplest cross-origin, browser-friendly contract.
- **Use a third-party web framework (Flask/FastAPI).** Rejected: adds runtime dependencies and
  violates the zero-dependency, self-contained constraint; the stdlib `http.server` suffices.

This ADR lifts nothing in adr-0005 — the traffic-overlay is a product slice (M1 × traffic), not
an E1–E9 platform concern.
