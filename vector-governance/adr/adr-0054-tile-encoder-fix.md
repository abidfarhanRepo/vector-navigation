# ADR-0054: vector-tile-gen uses a spec-valid MVT encoder

- **Status:** Accepted
- **Date:** 2026-07-16
- **Deciders:** Vector architecture & data
- **Supersedes/Relates:** ADR-0015 (M1 slice); ADR-0013 (tile pipeline)

## Context
The M1 web demo rendered a **blank (grey) map** even though the viewer, auth,
and tile-serving were all correct. Root cause: `vector-tile-gen`'s
hand-rolled protobuf encoder (`encode.py`) emitted **malformed MVT** — the
bytes failed to parse in `mapbox-vector-tile` (the reference decoder) and
MapLibre silently drew nothing. The repo's own `decode_tile` was
self-consistent, so the unit tests passed while real tooling rejected the
output — a classic "green tests, broken artifact" trap.

## Decision
Replace the hand-rolled encoder with **`mapbox-vector-tile`** (a spec-valid,
well-tested MVT implementation) as a declared dependency. `encode_tile` now
converts the feature dicts (lon/lat `coordinates`, `_z/_x/_y` tile coords) into
GeoJSON features and calls `mapbox_vector_tile.encode(..., quantize_bounds,
y_coord_down=False, extents=EXTENT_DEFAULT)`. `decode_tile` uses
`mapbox_vector_tile.decode`, with y flipped to the y-DOWN convention used by
`lonlat_to_local` / MapLibre, so the legacy test contract is preserved. The
Web-Mercator projection helpers and `FeatureStore` selection logic are
unchanged.

The spec-anchor test was changed from fragile raw wire-type walking to an
authoritative check: a standard decoder must parse the tile and recover the
layer + feature + geometry. This is what actually catches "blank map" regressions.

## Rationale
- A spec-valid encoder is the only way to guarantee MapLibre (and every other
  consumer) renders the tiles. Hand-rolling protobuf for MVT is high-risk and
  was already proven wrong once.
- `mapbox-vector-tile` is the de-facto reference; depending on it removes a
  whole class of encoder bugs.
- The dependency is added to `pyproject.toml` so CI installs it.

## Consequences
- `vector-tile-gen` now emits tiles that render in MapLibre (verified end to
  end with the Doha street network demo).
- The broken-encoder workaround (regenerating tiles with an external script)
  is no longer needed — `build_m1_tiles.py` produces correct tiles directly.
- Round-trip tests tolerate <=1 unit MVT quantization error at extent 4096.

## Validation
- `vector-tile-gen` unit tests: 19 pass (1 skipped — building integration
  test, which needs a building tile set).
- `build_m1_tiles.py --geojson doha_network.geojson --zooms 11,12,13` ->
  valid MVT tiles; decoded with mapbox-vector-tile (6108 road features in one
  Doha tile).
- Live: web container serves these tiles; MapLibre renders the Doha street
  network.
- Full `act`+Docker gate green (32/32).
