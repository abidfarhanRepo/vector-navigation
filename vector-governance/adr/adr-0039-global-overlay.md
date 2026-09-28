# adr-0039 — Global basemap live overlay — M1 × phase-9 integration (Global HTTP API behind the tile-server)

## Status
Accepted.

## Context
The Vector roadmap (phase 9) needs a user-facing global view that ties the per-city capability services together.

## Decision
Add a tile-server 'Global mode' (toggle + Show global / Clear global) that calls the vector-global HTTP `/global` API (nginx-proxied) and draws returned region Polygons as fills colored by tier (free green `#34c759` / pro blue `#0a84ff` / enterprise purple `#af52de`) with region-name labels at centroids; clicking a region flies the map to its centroid at its zoom_max. Preserves all existing overlays.

## Consequences
- The M1 map now offers a world region picker that references the existing per-city capability services (vector-hdmaps, vector-routing, etc.) under a global basemap.
- A same-origin `/global` proxy is added to nginx (port 8087, container vector-global-maps).
- The global-http-svc runtime edge `vector-tile-server -> vector-global` is recorded in the registry.

## Alternatives considered
- A separate global viewer app: rejected — reuses the existing M1 MapLibre viewer and overlay pattern (adr-0015/0016).

This ADR lifts nothing in adr-0005 (product slice, not E1–E9).
