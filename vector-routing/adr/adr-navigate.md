# ADR — Turn-by-turn ETA navigation (`/navigate`)

- **Status:** Accepted
- **Date:** 2026-07-14
- **Deciders:** D2 (Product Engineering)
- **Supersedes:** none
- **Superseded by:** none

## Context
The M2 Navigation engine (`vector-routing`) serves shortest-path routes via
`GET /route` but returns only a bare `LineString` with aggregate distance/duration.
Turn-by-turn navigation (Wave 24) needs step-by-step maneuvers plus a per-step
ETA so a web map / client can render "Head north on residential road", "Turn left
onto primary road", "Arrive at destination". The feature must stay self-contained
(Python 3.11 stdlib only, no sibling imports) and must not change `/route` output.

## Decision
Add `GET /navigate` to `serve.py`, reusing the existing `RoutingGraph`,
`Router.nearest_node` snapping and A* (`astar`) exactly as `/route` does. Each
leg (from -> via1 -> ... -> to) is routed independently and concatenated (the
shared join node is dropped so vertices are not duplicated). Maneuvers and ETA
are computed from the concatenated route.

### Query contract
- `from=LAT,LON` — required origin.
- `to=LAT,LON` — required destination.
- `via=LAT,LON;LAT,LON` — optional semicolon-separated intermediate waypoints
  for multi-leg routes.
- `profile=shortest` — optional, default `shortest` (accepted verbatim; routing
  always uses A* shortest-path, matching `/route`).

### Response schema
GeoJSON `FeatureCollection` with one `LineString` feature whose `coordinates`
are `[[lon, lat], ...]`. `properties`:

```json
{
  "type": "route",
  "distance_km": <float>,
  "duration_s": <float>,
  "from": "LAT,LON",
  "to": "LAT,LON",
  "profile": "shortest",
  "steps": [
    {
      "index": <int>,
      "type": "depart" | "continue" | "slight-left" | "slight-right"
            | "turn-left" | "turn-right" | "uturn" | "arrive",
      "instruction": <str>,
      "location": [<lon>, <lat>],
      "distance_m": <float>,
      "duration_s": <float>,
      "cumulative_distance_m": <float>,
      "cumulative_duration_s": <float>,
      "bearing": <float>
    }
  ]
}
```

### Turn classification
For each interior vertex, `delta = normalize(outgoing_bearing - incoming_bearing)`
to `(-180, 180]`:
- `|delta| < 20`          → `continue`
- `20 <= |delta| < 45`    → `slight-left` (delta<0) / `slight-right` (delta>0)
- `45 <= |delta| < 135`   → `turn-left` (delta<0) / `turn-right` (delta>0)
- `|delta| >= 135`        → `uturn`

The first vertex is `depart`, the last is `arrive`. Bearing uses the standard
initial forward azimuth (great-circle) between consecutive (lon, lat) points.
Instruction text uses the outgoing segment's `highway` property, e.g.
`"Head north on residential road"`, `"Turn left onto primary road"`,
`"Arrive at destination"` (falls back to "the road" when `highway` is absent).

### ETA model
Per segment: `distance_m` via haversine between node coordinates; speed from the
edge `maxspeed_kmh` property (default `50` km/h, matching `/route`), converted to
m/s by `/ 3.6`; `duration_s = distance_m / speed_mps`. Per step we carry
`distance_m`, `duration_s` and running `cumulative_distance_m` /
`cumulative_duration_s`. Route totals equal the sum of segments.

### Reuse
`Router.navigate` calls `RoutingGraph.nearest_node` + `route_by_node` (A*)
per leg — the same snapping/graph path as `/route` — so behavior is shared and
`/route` is byte-for-byte unchanged.

## Consequences
- Turn-by-turn navigation available with zero new dependencies and no sibling
  imports; `/route` and `/healthz` are unaffected.
- Multi-leg routes via `via` are supported by concatenating per-leg A* paths.
- ETA is a simple haversine + posted-speed model (no traffic); acceptable for M2.

## Alternatives considered
- **Extend `/route` with steps:** rejected — would change the existing response
  shape and risk regressions for current consumers.
- **Client-side maneuver computation:** rejected — keeps routing logic out of the
  engine and duplicates graph knowledge in the client.

## References
- ADR-0042 (Vector governance: Navigation/North-star contracts).
- adr-0025 (vector-routing bounded context + Python stack), adr-0006 (runtime).
- `src/vector_routing/router.py` (`Router.navigate`, `Router._build_steps`).
