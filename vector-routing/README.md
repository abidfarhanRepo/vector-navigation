# vector-routing

> **Owner squad:** D2 (Product Engineering)
> **Secondary:** D6 (Data & Analytics)
> **Purpose:** `vector-routing` is the **Navigation/Routing bounded context** for the M2 product vertical slice. It builds a routing graph from GeoJSON/normalized features and serves shortest-path routes via Dijkstra / A*.
> **Canonical standards:** vector-governance. **Cross-language truth:** vector-contracts.

Python (CPython 3.11+). A trivial `health()` entrypoint and a small in-code `main()` are committed; they run once the Python toolchain is provisioned (adr-0006).

## Bounded context
`vector-routing` is the **Navigation/Routing bounded context** for the M2 product vertical slice. It owns exactly: graph build from GeoJSON/normalized features, shortest-path routing (Dijkstra / A*), and a `RoutingService` facade. It consumes contract Coordinate/BoundingBox / GeoJSON normalized shapes.

## Dependency posture
- Consumes contract types from `vector-contracts` (Coordinate / BoundingBox / GeoJSON normalized shapes) at runtime per registry edges.
- Fed by `vector-ingestion` (GeoJSON) and `vector-map-store` (normalized features) at runtime; this repo is **self-contained** and reimplements a minimal GeoJSON parser (no direct sibling import — isolated-CI rule).
- Native tests run under the provisioned Python toolchain (adr-0006).

## Health endpoint (committed; runs via provisioned toolchain)
`src/vector_routing/__main__.py` exposes a health check and a small demo route. Run with the Python toolchain (provisioned in Session 4).

## Route HTTP service
The engine can be exposed over HTTP (stdlib `http.server`, zero runtime
dependencies) so a web map can draw routes. Run:

```
python -m vector_routing.serve --port 8081
```

It loads a GeoJSON road network. The path is resolved as
`--graph <path>` → `$ROUTING_GRAPH` env var → the default
`routing-data/sample_network.geojson` (a connected grid around Berlin).

Endpoints:

- `GET /healthz` → `200 text/plain` body `ok` (with CORS header).
- `GET /route?from=LAT,LON&to=LAT,LON[&profile=shortest]` →
  `200 application/json` returning a GeoJSON `FeatureCollection` with a single
  `LineString` feature whose `coordinates` are `[[lon, lat], ...]`. The
  `properties` object contains `profile`, `distance_km`, `duration_s`,
  `nodes`, `from` (`[lat, lon]`) and `to` (`[lat, lon]`).

- `GET /navigate?from=LAT,LON&to=LAT,LON[&via=LAT,LON;LAT,LON][&profile=shortest]` →
  `200 application/json` returning a GeoJSON `FeatureCollection` with a single
  `LineString` feature for the full multi-leg route (same `[[lon, lat], ...]`
  coordinate ordering). The `properties` object contains `type` (`"route"`),
  `distance_km`, `duration_s`, `from` (`"LAT,LON"`), `to` (`"LAT,LON"`),
  `profile`, and a `steps` array of turn-by-turn maneuvers. Each step carries
  `index`, `type` (`depart`/`continue`/`slight-left`/`slight-right`/`turn-left`/
  `turn-right`/`uturn`/`arrive`), `instruction`, `location` (`[lon, lat]`),
  `distance_m`, `duration_s`, `cumulative_distance_m`, `cumulative_duration_s`
  and `bearing`. `via` is an optional semicolon-separated list of intermediate
  waypoints for multi-leg routing. See `adr/adr-navigate.md`.

- `GET /foot?from=LAT,LON&to=LAT,LON` →
  `200 application/json` returning a GeoJSON `FeatureCollection` with a single
  `LineString` feature (same `[[lon, lat], ...]` ordering), routed over the
  PEDESTRIAN graph. The `properties` object contains `profile` (`"foot"`),
  `distance_m`, `duration_s`, `steps_m` (metres of `highway=steps` on the
  route), `nodes`, `snap`/`snap_max_m`, `walk_speed_ms`, `from` and `to`.

  Metres rather than kilometres because a walk is metres. `duration_s` is
  `distance_m / 1.35` — a flat modelled pace, slower over stairs — and carries
  no traffic or learned layer, because both are statements about vehicles.

  The pedestrian graph is loaded from `--foot-graph`, `$VECTOR_FOOT_GRAPH`, or
  the `<region>_foot.geojson` that `bootstrap.sh` bakes beside the car graph.
  When none is present `/foot` answers `503` with the reason and every other
  endpoint is unaffected.

- `GET /footz` → `200 application/json` with `available`, `nodes`, `edges`,
  `walk_speed_ms`, `steps_speed_ms`. The counterpart of `/overlay`, `/learned`
  and `/restrictions`: "no pedestrian graph baked" and "a graph that loaded but
  is empty" look identical from a failed walk, and these numbers separate them.

Status/error codes:

- `400` with `{"error": ...}` when `from`/`to` are missing or malformed.
- `404` with `{"error": "no route found between the requested points"}` when no
  route exists.
- `503` with `{"error": ...}` when the routing graph is unavailable or another
  routing error occurs.

All `/route` responses include `Access-Control-Allow-Origin: *`.
The console script `vector-routing-serve` is also installed.

## Tests
`npm test` runs the Node test runner. Python unit tests run with:
`PYTHONPATH=src python -m unittest discover -s tests -v`

