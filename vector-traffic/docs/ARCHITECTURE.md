# Architecture Overview — vector-traffic

## Bounded context
`vector-traffic` is the **Crowdsourced Traffic bounded context** for the M6 product vertical slice. It estimates per-segment speed and congestion from crowdsourced GPS probe points over a road network, and serves them as a GeoJSON `FeatureCollection` to the M6 product surface.

## Ownership & dependency posture
- **Primary squad:** D2 (Product Engineering). **Secondary:** D6 (Data & ML Engineering).
- **Upstream contracts:** consumes language-neutral types from `vector-contracts`
  (Coordinate / BoundingBox / GeoJSON normalized shapes). Fed at runtime by
  `vector-ingestion` (GeoJSON probes) and `vector-map-store` (normalized road features) per the registry edges.
- **Runtime posture:** Python (CPython 3.11+). Toolchain provisioned in Session 4 (adr-0006, Python 3.11 via uv); health entrypoint now runs.

## Modules & dependencies
- `geometry` — `haversine_meters`, `bearing_deg`, `point_to_segment_distance_m`.
- `probe` — `Probe`, `normalize_probe`, `load_probes`: parse crowdsourced GPS points (incl. GeoJSON Point).
- `segments` — `RoadSegment`, `normalize_segment`, `load_segments`: parse road network LineStrings + free-flow speed.
- `match` — `match_probe`, `match_probes`, `MatchResult`: map-match probes to nearest segment within a radius (deterministic tie-break).
- `aggregate` — `congestion_level`, `aggregate`: bucket mean speed into free/light/moderate/heavy/jammed.
- `traffic` — `TrafficModel`, `TrafficSegment`, `estimate_traffic`: facade over match + aggregate, emits GeoJSON.
- No circular dependencies; keep the bounded context strictly within this repo's owned paths (Blueprint §5).

## Design rules (Bible §3)
- Explicit validation at boundaries; fail loud (Bible §16 ER1).
- Self-contained: no direct sibling import; reimplements minimal GeoJSON I/O (isolated-CI rule).
- Add a benchmark under `vector-playground` before any perf optimization (Bible §5 P1).
