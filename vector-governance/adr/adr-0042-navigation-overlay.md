# adr-0042 — M2 turn-by-turn navigation overlay — M1 × M2 integration

## Status

Accepted.

## Context

The M2 Navigation Engine (`vector-routing`, adr-0025) computes shortest-path routes
(Dijkstra/A*) and already exposes the `routing-http-svc` endpoint `GET /route` over
HTTP (adr-0026), returning a GeoJSON `LineString` with aggregate `distance_km` /
`duration_s`. The M1 vertical slice (adr-0015) renders that route as a live
route-overlay on the MapLibre viewer. What is missing is a user-facing **navigation**
experience: a turn-by-turn plan with per-step maneuvers (`turn-left`, `slight-right`,
`arrive`, …), distances, durations, cumulative progress, and bearing, surfaced as a
**Nav panel** in the deployed viewer so the product can be driven end-to-end as an
ETA navigation feature rather than a single drawn line.

## Decision

Extend the existing stdlib-only `routing-http-svc` inside `vector-routing` (the same
`http.server` service that already serves `/route`) with a new turn-by-turn endpoint:

- `GET /navigate?from=LAT,LON&to=LAT,LON[&via=LAT,LON;LAT,LON][&profile=shortest]`
  returns a GeoJSON `FeatureCollection` whose single `LineString` feature is the
  computed route. The feature `properties` are:

  - `type`: `"route"`
  - `distance_km`: total route distance (number)
  - `duration_s`: total estimated travel time (number)
  - `from`: `[LON,LAT]` origin
  - `to`: `[LON,LAT]` destination
  - `profile`: routing profile (e.g. `"shortest"`)
  - `steps`: an ordered array of turn-by-turn maneuvers, each:
    - `index` (number)
    - `type`: one of `depart | continue | slight-left | slight-right | turn-left | turn-right | uturn | arrive`
    - `instruction` (string)
    - `location`: `[LON,LAT]`
    - `distance_m` (number)
    - `duration_s` (number)
    - `cumulative_distance_m` (number)
    - `cumulative_duration_s` (number)
    - `bearing` (number, degrees)

  The endpoint reuses the existing A* routing graph and duration model already used by
  `/route`; it adds no new algorithm and no new dependency, preserving the
  zero-sibling-import, self-contained constraint of adr-0025. The optional `via`
  parameter is a `;`-separated list of intermediate `LAT,LON` waypoints that the route
  is snapped through in order; `profile=shortest` mirrors the existing `/route` cost.

- In the local Docker deployment (adr-0016), `nginx` proxies `/navigate` to the routing
  container at `http://vector-routing:8081`, so the browser viewer reaches the
  navigation service through the same origin as the tile-server (nginx `:8080`).

- The M1 tile-server viewer gains a **Nav panel** that calls `/navigate`, draws the
  returned GeoJSON `LineString` as the live route overlay, and renders `steps` as a
  turn-by-turn ETA list with cumulative distance/time and maneuver icons.

The service-local ADR for the endpoint contract lives at
`vector-routing/adr/adr-navigate.md`.

## Consequences

- The deployed M1 map now supports turn-by-turn ETA navigation built on the M2 engine,
  closing the M1 × M2 integration loop for the navigation product without modifying the
  engine's core algorithms.
- `vector-tile-server` gains a runtime dependency on the new `/navigate` capability of
  `vector-routing` (registry edge `vector-tile-server -> vector-routing`, type runtime);
  the Nav panel is a viewer-side HTTP consumer, not a build-time coupling.
- The navigation endpoint is stdlib-only and reuses the `/route` plumbing, so it adds no
  runtime dependencies and keeps `vector-routing` self-contained and gate-green.

## Alternatives considered

- **Derive steps client-side from the `/route` LineString in the viewer.** Rejected: the
  geometry-only `/route` response carries no maneuver types, bearings, or per-step
  distances; reconstructing turn-by-turn semantics in the browser would duplicate the
  engine's graph knowledge and drift from the authoritative cost model.
- **Stand up a separate navigation service instead of extending `routing-http-svc`.**
  Rejected: it would fork the A* graph and duration model, breaking the single-source
  of truth for routing and violating the self-contained, lowest-risk extension intent of
  M2 (adr-0025/adr-0026).
- **Use a third-party web framework for the navigate service.** Rejected: adds runtime
  dependencies and violates the zero-dependency, self-contained constraint of
  `vector-routing`; the stdlib `http.server` already serving `/route` is sufficient.

This ADR lifts nothing in adr-0005 — the navigation overlay is a product slice
(M1 × M2), not an E1–E9 platform concern.
