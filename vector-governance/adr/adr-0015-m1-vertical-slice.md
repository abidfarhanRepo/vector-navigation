# ADR-0015 — M1 vertical slice: self-served Map Display

- **Status:** Accepted
- **Date:** 2026-07-12
- **Deciders:** D1-Platform / D7-Product
- **Supersedes:** none
- **Superseded by:** none

## Context
The Vector platform's first product milestone (M1, "Map Display") must prove the full
data path end-to-end: **ingest → store → tile generation → tile serving** with a real,
renderable map — not a mock. The Vertical Slice Plan (Session 6) deferred M1 to Session 7
and left the data source open (small GeoJSON sample vs. OSM extract). We need a concrete,
verifiable slice that exercises every bounded context and the cross-language contract
(`tiles/{z}/{x}/{y}.mvt`) without pulling in heavy external datasets or cloud infra.

## Decision
Build M1 as a **small, self-contained vertical slice** driven by a **small GeoJSON sample**
(Berlin: 4 features — Brandenburg Gate, TV Tower, a named park polygon, and one feature at
`lon -240` to prove longitude wrap-around to `120.0`). The slice wires the four product
repos through the filesystem tile contract:

- **vector-ingestion** (`src/vector_ingestion/geojson.py`): load + normalize GeoJSON into a
  common feature shape (Point/Polygon, property normalization, lon-wrap). Pure Python, no deps.
- **vector-map-store** (`src/vector_map_store/store.py`): in-memory `FeatureStore` with
  `insert` / `query_bbox` / `save` / `load`. Pure Python, no deps.
- **vector-tile-gen** (`src/vector_tile_gen/encode.py`, `pipeline.py`): a **dependency-free
  pure-Python MVT encoder/decoder** (Web-Mercator, command/id encoding, ring-area nesting) and
  `generate_tile` that clips features to a tile bbox. `scripts/build_m1_tiles.py` emits
  `tiles/{z}/{x}/{y}.mvt` for the sample at zoom 12 (produces 3 tiles).
- **vector-tile-server** (Rust/axum, ADR-0009): serves `/`, `/healthz`, and
  `/tiles/{z}/{x}/{y}.mvt`; `static/index.html` is a MapLibre GL viewer that fetches the
  self-served MVT tiles (no external tile provider).

The slice is deployed locally via Terraform (see ADR-0016): nginx serves the viewer + tiles,
proving M1 renders in a browser.

## Consequences
+ Every bounded context is exercised by real, tested code; the cross-language contract is proven.
+ Pure-Python MVT (zero deps) keeps the dependency-free CI (ADR-0006) green; no new runtime deps.
+ Small sample means fast tests and a reproducible demo; the lon-wrap feature validates projection.
+ Rust native build is blocked on the host (no `link.exe`); verified inside the `act` container
  (ADR-0014) instead — same as the rest of CI.
- A 4-feature sample is not a "real map"; it validates the pipeline, not coverage or scale.
- Tile-server is nginx-backed in M1 (Terraform/Docker); the Rust axum server remains the
  canonical implementation and is covered by `act`-run integration tests.

## Alternatives considered
- **OSM extract as M1 data source:** rejected for M1 — large, slower tests, masks pipeline bugs;
  reserved for a later scale milestone. The slice is intentionally small.
- **Mock tile server returning canned bytes:** rejected — defeats the purpose of proving the
  encode→serve→render path; the GeoJSON sample is cheap enough to do it for real.
- **Single combined repo instead of four:** rejected — violates ADR-0001 polyrepo + ADR-0003
  bounded contexts; the filesystem tile contract is exactly the integration seam we want to prove.

## References
- ADR-0001 (polyrepo), ADR-0003 (mixed stack / bounded contexts), ADR-0006 (Node-first runtime,
  dependency-free), ADR-0009 (Rust tile-server), ADR-0014 (local CI via act), ADR-0016
  (Terraform local activation)
- `vector-tile-gen/scripts/build_m1_tiles.py`, `vector-tile-server/static/index.html`
- Vector Engineering Bible §11 A2 (ADR format)
