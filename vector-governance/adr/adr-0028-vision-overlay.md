# adr-0028 — M3 live CV overlay — M1 × M3 integration

## Status

Accepted.

## Context

The M3 Computer Vision engine (`vector-vision`, adr-0027) runs a classic CV pipeline
(grayscale → blur → threshold → connected-component detection → georeference) and emits a
GeoJSON `FeatureCollection` of detected blobs, but exposes no network interface. The M1
vertical slice shipped a self-served Map Display (adr-0015) with a Rust tile-server
serving vector tiles and a MapLibre viewer, but the deployed viewer cannot show detected
features. To make the CV product visible and testable end-to-end, the vision engine must be
reachable over HTTP from the deployed map viewer so live detected features can be drawn on
the M1 map.

## Decision

Expose the M3 engine as a stdlib-only HTTP vision service (`vision-http-svc`) inside
`vector-vision`, wrapping the existing engine:

- `GET /detect?image=&bbox=MIN_LON,MIN_LAT,MAX_LON,MAX_LAT&threshold=&blur=&min_area=&connectivity=`
  returns a GeoJSON `FeatureCollection` whose `Point` features are the detected,
  georeferenced blobs (CORS `*`); default image is `sample_scene.pgm` with a Berlin bbox.
  Only the Python standard library (`http.server`) is used, preserving the
  zero-sibling-import, self-contained constraint of adr-0027.
- `GET /healthz` returns a 200 OK with a trivial body for liveness/readiness.

The M1 tile-server viewer fetches the vision HTTP API and draws the returned GeoJSON as an
overlay layer on the MapLibre map (live CV-overlay).

In the local Docker deployment (adr-0016), `nginx` proxies `/detect` to the vision
container (port 8082), so the browser viewer reaches the vision service through the same
origin as the tile-server.

## Consequences

- The deployed M1 map can now render live CV-detected features computed by the M3 engine,
  closing the M1 × M3 integration loop without modifying the engine's core algorithms.
- `vector-tile-server` gains a runtime dependency on `vector-vision` (registry edge
  `vector-tile-server -> vector-vision`, type runtime); the overlay is a viewer-side HTTP
  consumer, not a build-time coupling.
- The vision HTTP service is stdlib-only, so it adds no runtime dependencies and keeps
  `vector-vision` self-contained and gate-green.

## Alternatives considered

- **Bundle vision into the tile-server (Rust).** Rejected: it would break the
  mixed-by-layer boundary (adr-0003) and the self-contained constraint of adr-0027.
- **Have the viewer consume the E2 bus directly.** Rejected: the browser viewer cannot
  consume the bus; a plain HTTP GeoJSON endpoint is the simplest cross-origin,
  browser-friendly contract.
- **Use a third-party web framework (Flask/FastAPI).** Rejected: adds runtime dependencies
  and violates the zero-dependency, self-contained constraint; the stdlib `http.server`
  suffices.

This ADR lifts nothing in adr-0005 — the CV-overlay is a product slice (M1 × M3), not an
E1–E9 platform concern.
