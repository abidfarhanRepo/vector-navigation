# adr-0031 — Road Reconstruction live overlay — M1 × M4 integration

## Status

Accepted.

## Context

The Road Reconstruction engine (`vector-reconstruction`, adr-0030) reconstructs road
centerlines (a `LineString` network) from sparse road observations (GPS/probe points)
and/or GeoJSON way features into a `RoadGraph`, but exposes no network interface. The M1
vertical slice shipped a self-served Map Display (adr-0015) with a Rust tile-server
serving vector tiles and a MapLibre viewer, but the deployed viewer cannot show
reconstructed roads. To make the reconstruction product visible and testable end-to-end,
the reconstruction engine must be reachable over HTTP from the deployed map viewer so live
reconstructed roads can be drawn on the M1 map.

## Decision

Expose the reconstruction engine as a stdlib-only HTTP reconstruction service
(`reconstruction-http-svc`) inside `vector-reconstruction`, wrapping the existing engine:

- `GET /reconstruct?points=LON,LAT;...[&road_class=...][&ways=...][&max_gap_m=...]`
  returns a GeoJSON `FeatureCollection` whose `LineString` features are the reconstructed
  road centerlines (CORS `*`). Only the Python standard library (`http.server`) is used,
  preserving the zero-sibling-import, self-contained constraint of adr-0030.
- `GET /healthz` returns a 200 OK with a trivial body for liveness/readiness.

The M1 tile-server viewer fetches the reconstruction HTTP API and draws the returned
GeoJSON as an overlay layer on the MapLibre map (live road-overlay).

In the local Docker deployment (adr-0016), `nginx` proxies `/reconstruct` to the
reconstruction container (port 8083), so the browser viewer reaches the reconstruction
service through the same origin as the tile-server.

## Consequences

- The deployed M1 map can now render live reconstructed roads computed by the
  reconstruction engine, closing the M1 × reconstruction integration loop without
  modifying the engine's core algorithms.
- `vector-tile-server` gains a runtime dependency on `vector-reconstruction` (registry edge
  `vector-tile-server -> vector-reconstruction`, type runtime); the overlay is a
  viewer-side HTTP consumer, not a build-time coupling.
- The reconstruction HTTP service is stdlib-only, so it adds no runtime dependencies and
  keeps `vector-reconstruction` self-contained and gate-green.

## Alternatives considered

- **Bundle reconstruction into the tile-server (Rust).** Rejected: it would break the
  mixed-by-layer boundary (adr-0003) and the self-contained constraint of adr-0030.
- **Have the viewer consume the E2 bus directly.** Rejected: the browser viewer cannot
  consume the bus; a plain HTTP GeoJSON endpoint is the simplest cross-origin,
  browser-friendly contract.
- **Use a third-party web framework (Flask/FastAPI).** Rejected: adds runtime dependencies
  and violates the zero-dependency, self-contained constraint; the stdlib `http.server`
  suffices.

This ADR lifts nothing in adr-0005 — the road-overlay is a product slice (M1 ×
reconstruction), not an E1–E9 platform concern.
