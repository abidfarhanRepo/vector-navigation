# CHANGELOG — vector-tile-server

## 2026-08-04 — GET /tiles/version + explicit tile caching (Session 52, issue 08)
- Added `GET /tiles/version`, reading the epoch from `VERSION.json` in the tile
  directory and returning it with `Cache-Control: no-store`. That header is
  load-bearing: this is the response that tells a client its cached tiles are
  stale, so caching it would silently disable the whole invalidation scheme.
  A missing or unreadable file is epoch 0, not an error.
- Tiles are now served with an explicit `Cache-Control: public, max-age=86400`.
  Caching hard is correct once URLs carry the epoch — a tile *is* immutable for
  its epoch, and a re-bake changes the URL rather than the bytes behind one.
- Two-segment route, so it cannot shadow the three-segment `/tiles/:z/:x/:y`.
- `scripts/dev_tile_server.py` mirrors both changes.
- **Not verified on this host:** no C linker is installed (no MSVC build tools,
  no gcc), so `cargo test` cannot link even a build script. The new pure function
  `parse_epoch` was type-checked standalone with `rustc --emit=metadata` and its
  behaviour cross-checked against the same cases; the handler mirrors the existing
  verified glyph handler. The Python dev server implements the same contract and
  is tested. Compile and run `cargo test` on a host with build tools before
  shipping the Rust binary.

## 2026-07-25 — Cache-layer guard (Session 50)
- Added `_cached_tile_ok()` in `serve.py`: a cached tile is validated before
  serving. Empty or wrong-layer (`basemap`) cached tiles are treated as a miss
  and regenerated, so a stale/poisoned cache can never silently blank the map.
- Added `tests/test_serve.py` (4 tests): proves a stale 0-byte marker and an
  old `vector`-layer marker are regenerated, not served, and a valid cached tile
  is served untouched.
