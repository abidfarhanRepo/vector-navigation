# vector-traffic

> **Owner squad:** D2 (Product Engineering)
> **Secondary:** D6 (Data & ML Engineering)
> **Purpose:** `vector-traffic` is the **Crowdsourced Traffic bounded context** for the M6 product vertical slice. It estimates per-segment speed and congestion (free/light/moderate/heavy/jammed) from crowdsourced GPS probe points over a road network (map-matching probes to the nearest road segment, then aggregating mean speed) and emits a GeoJSON `FeatureCollection`.
> **Canonical standards:** vector-governance. **Cross-language truth:** vector-contracts.

Python (CPython 3.11+). A trivial `health()` entrypoint and a small in-code `main()` are committed; they run once the Python toolchain is provisioned (adr-0006).

## Bounded context
`vector-traffic` is the **Crowdsourced Traffic bounded context** for the M6 product vertical slice. It owns exactly: estimation of per-segment speed and congestion from crowdsourced GPS probe points over a road network (map-matching probes to the nearest road segment within a radius, then aggregating mean speed into congestion buckets); a `TrafficModel` facade; and `health()` + `main()` demos. It consumes contract Coordinate/BoundingBox / GeoJSON normalized shapes.

## Dependency posture
- Consumes contract types from `vector-contracts` (Coordinate / BoundingBox / GeoJSON normalized shapes) at runtime per registry edges.
- Fed by `vector-ingestion` (GeoJSON probes) and `vector-map-store` (normalized road features) at runtime; this repo is **self-contained** and reimplements a minimal GeoJSON parser (no direct sibling import — isolated-CI rule).
- Native tests run under the provisioned Python toolchain (adr-0006).

## Health endpoint (committed; runs via provisioned toolchain)
`src/vector_traffic/__main__.py` exposes a health check and a small demo traffic estimation. Run with the Python toolchain (provisioned in Session 4).

## Traffic engine
The engine works in three self-contained stages:
- **Map-matching:** `match_probes` matches each probe to the nearest road segment (minimum point-to-sub-segment distance), rejecting probes further than `max_match_radius_m` (default 100 m). Deterministic ties break by earliest segment, then earliest sub-segment.
- **Aggregation:** `aggregate` computes per-segment `mean_speed_kmh` (rounded to 2 decimals), `probe_count`, and a `congestion` bucket (`free`/`light`/`moderate`/`heavy`/`jammed`) derived from the ratio of mean speed to `free_flow_kmh`.
- **Facade:** `TrafficModel.estimate` runs match + aggregate and returns `TrafficSegment`s (segments with no probes get `mean_speed_kmh=None`, `probe_count=0`, `congestion='unknown'`); `estimate_geojson` / `to_geojson` emit a GeoJSON `FeatureCollection` of `LineString` features with `{segment_id, free_flow_kmh, mean_speed_kmh, probe_count, congestion}`.

## Tests
`npm test` runs the Node test runner. Python unit tests run with:
`PYTHONPATH=src python -m unittest discover -s tests -v`

## HTTP traffic service
`src/vector_traffic/serve.py` exposes the traffic engine over HTTP (stdlib-only, `http.server`) so a web map can draw live per-segment overlays. It mirrors the vector-reconstruction serve layer.

- `GET /traffic` — returns a GeoJSON `FeatureCollection` of per-segment traffic `LineString` features. Query params:
  - `probes` — optional `LON,LAT[,SPEED];...` inline probe string. When omitted, the bundled `traffic-data/sample_probes.geojson` is used.
  - `segments` — segments file name inside the data dir (default `sample_segments.geojson`).
  - `max_match_radius_m` — map-match radius in meters (default `100.0`).
  - Each feature `properties` carries `segment_id`, `free_flow_kmh`, `mean_speed_kmh`, `probe_count`, and `congestion` ∈ `free`/`light`/`moderate`/`heavy`/`jammed`/`unknown`.
- `GET /healthz` — returns `ok` (text/plain, 200).

CORS is enabled (`Access-Control-Allow-Origin: *`). Run with:
`uv run python -m vector_traffic.serve --port 8084`
(or `PYTHONPATH=src python -m vector_traffic.serve --port 8084`).
