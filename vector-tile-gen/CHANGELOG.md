# CHANGELOG — vector-tile-gen

## 2026-09-19 — The pre-publication gate: nothing moves until a release proves itself (V7.7)
- `scripts/validate_release.py`: whole-release validation, run before a single
  byte is transferred. `validate_tiles.py` asks "is each tile a well-formed
  `basemap` tile?", which is necessary and not sufficient — **every** failure
  V7.7 exists to stop consists entirely of perfectly valid tiles. A transfer
  that died after 3,000 of 18,311 leaves 3,000 valid tiles; `cp -r` MERGES, so
  a tile from the previous bake survives and is valid; a copy that landed only
  z6–z13 contains nothing malformed at all and silently re-advertises
  `maxzoom: 13`. Those are properties of the release, checkable only against a
  statement of what it was supposed to be.
- **Exit 2 is not exit 1.** A failed release is a decision; an unrunnable gate
  is an outage. Conflating them is how a broken check starts reading as a
  passing one, so a missing directory or an unreadable manifest exits 2 while a
  genuinely bad release exits 1. The publisher will branch on this.
- `--expect-buildings` exists because "the manifest agrees with the tiles" is
  satisfied perfectly when BOTH say zero — which is production's actual state
  today. Shipping that again while believing 3D had been deployed is the exact
  mistake the flag makes impossible. It also refuses to certify a 3D claim when
  the census was skipped, rather than passing a check that did not run.
- `--require-publishable` fails a release baked from an uncommitted tree: fine
  on a bench and on the emulator, not reproducible from a commit and therefore
  not auditable in production.
- The gate never touches the network and never modifies the artifact it judges.
  Both are asserted by tests — one makes `socket.socket` raise, the other
  re-digests the tree after three different gate invocations.
- `release.scan_tree` gained `no_layer_tiles`, closing a gap found by feeding
  the same bytes to both checkers and watching only one object: a 4-byte payload
  carrying an unknown protobuf field decodes successfully to ZERO layers. On
  disk, counted, digested, consistent with its own manifest — and blank. That is
  the Session 50 "streets vanish" class, which `validate_tiles._classify` has
  always caught and the census did not. A companion test pins that a zero-byte
  tile is still legitimate empty ocean, because Qatar is mostly coastline.
- The empty-tile ceiling is measured, not invented: ZERO zero-byte tiles in
  either real 18k-tile tree, so the 10% default is a smoke alarm with three
  orders of magnitude of headroom.
- One scan feeds every check (16.2 s with the census, 0.3 s without). Re-walking
  18,311 files per question is how a gate becomes something people pass
  `--skip` to.
- Tests: 310 → 338. Every failure path exercised against a staged copy of the
  REAL production tree: partial transfer, `cp -r` merge, mutated tile, missing
  manifest, dirty release, and a 3D claim on a release with no buildings.

## 2026-09-19 — The tile release manifest: a tile set that can name itself (V7.7)
- `src/vector_tile_gen/release.py`: release identity, provenance, integrity and
  verification for a baked tile tree. This exists because production has served
  a basemap for months with **no release identity at all** — `GET /tiles/version`
  does not read `VERSION.json`, it `os.walk`s the tree and returns `max(mtime)`,
  and `cp -r` does not preserve mtimes, so the published number is the moment
  files were *copied*. It cannot survive a copy, it advances on a partial write,
  and the `?v=` built from it binds nothing (production returns identical bytes
  for `?v=1` and `?v=2`).
- `RELEASE.json` carries the source snapshot's sha256, the generator's commit
  **and the generating scripts' own hashes**, the full input config, the bake
  window, a **declared** zoom range, a layer inventory and a Merkle `tree_digest`
  over every tile. `TILE_DIGESTS.tsv` turns "the digest does not match" into
  "these four tiles are wrong".
- The zoom range is declared rather than inferred so the tile server can stop
  deriving it from directory names — the inference under which a partial copy
  landing only z6–z13 silently re-advertises `maxzoom: 13`, MapLibre overzooms
  for a camera at 16.5, and the driving view degrades with nothing reporting it.
- `verify_manifest` is a **gate**: 16 stable failure codes, machine-readable
  report, no network. It catches the removed tile (the died-halfway transfer),
  the *extra* tile (`cp -r` MERGES, so a tile from the previous bake survives
  forever), the mutated tile, the dropped zoom, the wrong layer name and the
  overstated 3D claim. `read_manifest` is the opposite — total, never raising —
  because a served tile set is not worth taking down over a version file.
- Building counts are named `building_features` / `distinct_building_ids`, not
  `buildings_total`. A footprint is emitted into every tile it touches at every
  zoom it is visible at: measured on a real bake, 1,382 instances for 798
  distinct ids. V7.6 reports source-level counts (975 heights; 189,866
  footprints), and the old name invited a comparison that would have looked like
  the pipeline losing or inventing buildings.
- The probe sample is deterministic across *processes* (ordered by
  `sha256(path)`, never `random` or salted `hash()`) and spans every zoom, with
  a test that runs the selection under two `PYTHONHASHSEED` values and requires
  identical output.
- Census is exhaustive, never sampled: 16.2 s to decode all 18,311 production
  tiles, 0.3 s to hash them. Cheap enough to always run — and running it at full
  scale is what caught a sampling error in V7.7's own committed reconnaissance.
- Tests: 263 → 310. Exercised end to end against the real 18,311-tile production
  tree pulled read-only over ssh: manifest built, verified clean, then broken
  the way a partial transfer breaks it and caught by six independent codes.

## 2026-09-19 — Buildings with a sourced height become 3D (V7)
- `osm_to_geojson`: a `building` way that **states a height** is emitted as a
  `kind=building` POLYGON with `height_m` (plus `min_height_m` and
  `building_levels` as provenance). The branch sits before the `_attrs`
  dispatch, because that dispatch returns nothing for an unnamed building. A
  building with no `height` is not emitted at all: 96% of Qatar's 189,871
  footprints state none, cannot be extruded, and would otherwise consume every
  tile's feature budget to draw nothing.
- `_building_height_m` / `_building_attrs`: the accepted grammar and every
  refusal. `"40"` and `"40 m"` are metres; `"30'"`, `"400+"`, `"abc"`, `0`,
  negatives and values past 400 m are refused rather than clamped. **`building:levels`
  is never converted** — the measured height/levels ratio on the 519
  dual-tagged Qatari buildings is min 2.50 / p25 3.97 / median 4.29 / p75 5.00
  / max 60.00, a distribution and not a constant, so there is no
  metres-per-level number anywhere in this repo.
- `fetch_qatar_pbf.want_way`: keeps a building that states a `height` as well
  as named buildings. The name-only rule made the extract structurally unable
  to answer "how tall is Doha" — two thirds of the extrude-able footprints
  were filtered out before any consumer saw them.
- `build_qatar_tiles`: `building` ranks **lowest** (`_KIND_ORDER` 9, below the
  default unnamed POIs take) and holds **no** `_KIND_FLOOR`. Both settings were
  inherited from a kind that never rendered, and together they cost 265 road
  features, 221 barriers and 26 parks across 51 over-budget z14 tiles before
  the correction. Buildings now take only leftover space, so a road can never
  be displaced by a decorative solid.
- Bake range extended to z14–15: the extrusion layer cannot be reached at any
  zoom the old z11–13 bake served.

## 2026-09-19 — Camera nodes carry the tag that says what they are (V7)
- `osm_to_geojson._attrs`: the `highway=speed_camera` branch now preserves
  `highway` itself and `enforcement`, alongside the `maxspeed`/`direction`
  provenance it already kept. These are the source's own statement of WHAT a
  device is — `enforcement=*` names the enforced function explicitly — and the
  routing classifier reads exactly them and nothing else. Without them the
  artifact could not justify the type it was classified as; the census came out
  `{unknown: 133}` and the whole feature went silent.

## 2026-08-04 — Learned geometry wired into the bake + tile epoch (Session 52, issue 08)
- `scripts/rebake_learned.py`: selective re-bake from promoted `road_candidate` /
  `geometry_correction` facts. Verify-before-publish on every tile (Session 50's
  rule), atomic writes, `--withdraw <fact_key>` / `--withdraw-all` as the
  one-command rollback, and `rejected-facts.json` naming the keys to revert in the
  fact store when a tile fails. Base features are pre-filtered to the
  neighbourhood of the change, because `generate_tile` rescans the whole feature
  list per tile and a "selective" re-bake would otherwise still cost millions of
  bbox tests.
- `build_qatar_tiles.py --learned-facts`: merges the learned overlay into a full
  bake. The OSM source is never edited; omitting the flag is a complete rollback.
  The full bake now also verifies every tile before writing it, which it did not.
- `src/vector_tile_gen/tile_version.py`: the tile epoch. Bumped after verified
  tiles land on disk; served at `GET /tiles/version` with `no-store`; clients put
  it in the tile URL as `?v=<epoch>`. This closes the Session 50 stale-tile
  masking failure — which was NOT the service worker (that already treats
  `/tiles` as network-only) but the browser HTTP cache and MapLibre's in-page
  cache, neither of which a service worker can evict. See adr-0066.
- Tests: 44 -> 71. `test_rebake_learned.py` runs the real script and decodes the
  tiles it wrote, asserting the learned road appears, disappears on withdrawal,
  the OSM source's sha256 is unchanged, and every published tile still has
  exactly one `basemap` layer.

## 2026-07-25 — Tile-integrity verifier + regression test (Session 50)
- Added `scripts/validate_tiles.py`: walk a baked-tile tree, decode every `.mvt`,
  classify VALID / EMPTY(0-byte ocean) / BAD(wrong layer name or corrupt).
  `--repair <geojson>` regenerates BAD tiles in place via `TileSource`.
- Added `tests/test_tile_integrity.py` (4 tests): asserts every baked tile is a
  valid `basemap`-layer MVT using `mapbox-vector-tile` as an independent oracle —
  locks in the fix for the "streets vanish because layer name is `vector`, not
  `basemap`" bug.
