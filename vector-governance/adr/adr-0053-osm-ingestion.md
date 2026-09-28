# ADR-0053: Real OSM ingestion (vector-ingestion)

- **Status:** Accepted
- **Date:** 2026-07-16
- **Deciders:** Vector architecture & data
- **Supersedes/Relates:** ADR-0011 (ingestion bounded context); ADR-0007 (single-source vendoring)

## Context
The routing engine and tile pipeline consumed **synthetic sample data**; the
map/route were not on real geography. `vector-ingestion` only had coordinate
normalization helpers — no real ingest path. We needed a bounded, offline-safe
MVP that turns a **real OpenStreetMap extract** into the GeoJSON road
LineString features the routing graph and MVT tile pipeline already consume.

## Decision
1. `vector-ingestion` gains a dependency-free OSM XML parser (`osm.py`) that
   reads Overpass `out body geom;` extracts and emits GeoJSON `LineString`
   features carrying OSM road semantics (`highway`, `maxspeed`, `name`,
   `oneway`, `lanes`). Only `highway=*` ways become edges.
2. A `convert.py` `osm-to-graph` converter writes the routing-graph GeoJSON and
   can validate it builds a real `RoutingGraph` via the **vendored**
   `vector_geo` (single-source, ADR-0007).
3. A real **Doha, Qatar** OSM extract (6,696 ways, fetched from Overpass) is
   committed as a fixture so the gate + demo are offline-safe and reproducible.
4. The generated GeoJSON feeds routing directly (`ROUTING_GRAPH`/`--graph`) and
   `vector-tile-gen/scripts/build_m1_tiles.py` turns it into MVT tiles for the
   tile-server — closing the "sample data" gap for the M1 web demo.

## Rationale
- Stdlib-only parse keeps the gate offline-safe once the extract is committed.
- Reusing the existing GeoJSON contract means **zero changes** to routing/tile
  consumers — the ingested OSM data flows through the exact same path as the
  prior sample data, proving the contract end-to-end.
- Committing a real extract (vs. live fetch in CI) makes the pipeline
  deterministic and network-independent in the gate.

## Consequences
- `vector-ingestion` is no longer a stub for the parse path; it ingests real
  OSM.
- Demo/CI can run on genuine Doha geography; larger regional extracts are a
  later scale wave (streaming PBF, sharding, PostGIS bulk load).

## Validation
- `vector-ingestion` unit tests: parse Doha fixture -> 6,696 road features with
  real coords + named streets; build a real `RoutingGraph` (38,970 nodes /
  70,224 edges) via vendored `vector_geo` (18 tests green).
- Live: Doha OSM -> GeoJSON -> MVT tiles -> tile-server, and Doha GeoJSON ->
  routing `/route` returns a 218-point, 2.55 km route on real streets.
- Full `act`+Docker gate green (32/32).
