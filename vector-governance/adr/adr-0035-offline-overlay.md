# adr-0035 — Offline maps live overlay — M1 × M7 integration

## Status
Accepted.

## Context
The `vector-offline-maps` engine (adr-0034) computes offline package footprints and size estimates but exposes no network interface. The M1 map viewer cannot show offline coverage.

## Decision
Expose the offline engine as a stdlib-only HTTP service (`offline-http-svc`) inside `vector-offline-maps`, wrapping the engine:
- `GET /offline?bbox=MINLON,MINLAT,MAXLON,MAXLON[&zoom_min=10][&zoom_max=14][&avg_tile_kb=25]` returns a GeoJSON `FeatureCollection` whose `Polygon` feature is the requested bbox with properties `bbox`, `zoom_min`, `zoom_max`, `tile_count`, `size_mb`, `status=estimate` (CORS `*`). Malformed bbox → 400.
- `GET /offline` (no bbox) returns a GeoJSON `FeatureCollection` of the bundled region packages (`region_id`, `name`, `status`, `zoom_min`, `zoom_max`, `tile_count`, `size_mb`).
- `GET /healthz` returns 200 `ok`.

The M1 tile-server viewer fetches the offline HTTP API and draws the returned GeoJSON as a status-colored polygon overlay (live offline-overlay). In the local Docker deployment (adr-0016), nginx proxies `/offline` to the offline container (port 8085), so the browser reaches it same-origin.

## Consequences
- The M1 map can render offline coverage/size estimates, closing the M1 × offline integration loop without modifying the engine core.
- `vector-tile-server` gains a runtime dependency on `vector-offline-maps` (registry edge `vector-tile-server -> vector-offline-maps`, type runtime); the overlay is a viewer-side HTTP consumer.
- The service is stdlib-only, so it adds no runtime dependencies and keeps `vector-offline-maps` self-contained and gate-green.

## Alternatives considered
- Bundle offline into the tile-server (Rust): rejected — breaks mixed-by-layer (adr-0003) and self-contained constraint.
- Have the viewer consume the E2 bus directly: rejected — browser cannot consume the bus; a plain HTTP GeoJSON endpoint is the simplest browser-friendly contract.
- Third-party framework (Flask/FastAPI): rejected — adds runtime dependencies; stdlib `http.server` suffices.

This ADR lifts nothing in adr-0005.
