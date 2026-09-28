# ADR-0055: Wave 29 — real basemap tiles (buildings / parks / water / labels)

- **Status:** Accepted
- **Date:** 2026-07-16
- **Deciders:** Vector architecture & data
- **Supersedes/Relates:** ADR-0054 (tile encoder fix); ADR-0013 (tile pipeline); ADR-0007 (single-source)

## Context
The M1 web demo rendered only the **road network** (the routing graph) as a
street overlay on a flat background — no buildings, parks, water, or labels.
The user wanted the map to look like a *real* basemap. Additionally, Wave 28
revealed a latent mismatch: `build_m1_tiles.py` emitted the layer as
`vector` while the shipped viewer referenced `layers`, which blanked the map
after a tile regen.

Two things were needed:
1. Ingest richer OSM data (areas + labels), not just highways.
2. Standardize the tile layer name and render by feature `kind`.

## Decision
- **Ingestion (`vector-ingestion`):** `parse_osm` now classifies every way into
  a `kind` (`road` / `building` / `landuse` / `park` / `natural` / `water`) and
  emits `Polygon` geometry for areas (closed ring) and `LineString` for roads;
  `place=*` nodes become `Point` features with `kind="label"`. Roads keep their
  routing semantics. The committed **roads-only** fixture (`doha_qatar.osm`)
  stays roads-only so routing is unchanged; a new **rich** fixture
  (`doha_qatar_full.osm`) carries buildings/parks/water/labels. `convert`
  gains `--basemap` to emit a full-feature GeoJSON for the tile pipeline.
- **Tiles (`vector-tile-gen`):** `generate_tile` writes a single source-layer
  named **`basemap`** containing every feature with its `kind` tag. This is the
  canonical name; both encoder and viewer agree. (ADR-0054's encoder fix still
  applies — the bytes are spec-valid MVT.)
- **Viewer (`vector-web`):** `buildStyle` renders, in draw order, background,
  landuse/park/natural fills, water fill, building polygons, roads (line), and
  place labels (symbol). Source-layer is `basemap`; minzoom 11 / maxzoom 13.
- Routing continues to use the roads-only GeoJSON (`doha_network.geojson`),
  so the routing graph (38,970 nodes / 70,224 edges) is unaffected.

## Rationale
- One MVT layer with a `kind` tag keeps the tile pipeline simple (no multi-layer
  schema) while letting the viewer style each category. Multi-layer would also
  work but adds tile-pipeline complexity for no routing benefit.
- Keeping two OSM fixtures (roads-only for routing, rich for basemap) preserves
  the Wave 27a routing contract and avoids pulling buildings into the router.
- Standardizing the layer name to `basemap` closes the Wave 28 regression
  (the `vector`/`layers` mismatch).

## Consequences
- The Doha demo now renders buildings, parks, water, roads, and place labels —
  a recognizable basemap, not just streets.
- Routing is unchanged (separate roads-only extract).
- Tiles regenerated via the real pipeline carry `basemap` as the layer name.

## Validation
- `vector-ingestion` tests: 10 pass (basemap parse + routing regression guard).
- Basemap GeoJSON kinds: road 4389, building 2259, landuse 72, park 92,
  natural 9, label 9 (from `doha_qatar_full.osm`).
- Tiles decode with layer `basemap` and all kinds present (verified live).
- Routing graph still 38,970 nodes / 70,224 edges from roads-only extract.
- Full `act`+Docker gate green (32/32).
