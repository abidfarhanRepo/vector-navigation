# CHANGELOG — vector-geocoder

## 2026-08-07 — Overture places layer: the index was the problem, not the code

**The complaint:** "half the names that are searchable through Google Maps and
Waze don't come up in the app". The search code was fine; it was indexing an OSM
basemap that carries **6,298 named POIs for all of Qatar**. Google's index is an
order of magnitude denser. No amount of better matching fixes an empty index.

**Added**

- `scripts/fetch_overture_places.py` — offline ingest of the Overture Maps
  Foundation `places` theme (CDLA Permissive 2.0) for a bbox, emitted as a
  GeoJSON FeatureCollection in exactly the shape the indexer already accepts.
  Not importable from the package and not in `pyproject.toml`: it borrows duckdb
  via `uv run --with duckdb`, so `src/` stays stdlib-only. Caches the remote
  subset to Parquet so re-tuning the confidence floor costs no network.
- `GeocodeIndex.extend_from_geojson()` — merge another FeatureCollection into an
  existing index and report how many entries were added.
- `GeocodeService.load_places()` / `--places` / `VECTOR_PLACES_INDEX`, plus
  auto-detection of `<index>_places.geojson`. `health()` reports `poi_entries`.
- `bootstrap.sh` step 2b fetches the POI layer into the basemap volume
  (best-effort; `VECTOR_SKIP_OVERTURE=1` opts out).

**Changed — ranking.** Search now scores by explicit match tier (exact > prefix >
substring, native or transliterated), then name length, then places-over-roads on
a tie, then confidence. Previously an exact match had no privilege at all, so
"Villaggio Mall Access Road" could outrank the mall. Normalized keys are now
precomputed per entry instead of per query — the index is 44% larger and a scan
would otherwise have got proportionally slower.

**Overture alternate-language names** land in `alt_names` and are indexed as
extra searchable spellings, so an Arabic label makes a place findable in Arabic
*and* — through the existing transliteration — in Latin.

**Fixed (both latent, both meant the service could not start)**

- `serve.main()` called `srv.serve_forever()` on a name that was never bound.
  `python -m vector_geocoder.serve` raised `NameError` before binding a port.
- `docker/Dockerfile` used exec-form `ENTRYPOINT`, so docker-compose's `command:`
  override was *appended* rather than replacing it, handing argparse a duplicated
  command line. Now `CMD`.

**Result on the real Qatar basemap:** searchable entries 55,619 → 80,278; distinct
searchable names 13,571 → 35,413. Of ten hand-tested Doha landmarks a user would
plausibly type, 3 resolved to the right entity before and 10 after.

Tests: 56 → 87.

## 2026-08-04 — Learned POI layer wired into the service (Session 52, issue 09)

**Added**

- `GeocodeService` loads promoted `poi_candidate` facts from `learned_pois.json`
  (`VECTOR_LEARNED_POIS` / `--learned-pois`), read over the filesystem — the
  geocoder never imports the learning repo (adr-0003).
- `/reverse` surfaces learned places near the queried point, tagged
  `kind: learned_poi` and `learned: true` so a client can style them apart from
  surveyed OSM data. OSM hits come first, and a test enforces it: an inferred
  place must never outrank ground truth.
- `GET /learned` — the learned layer as GeoJSON, for display and audit. A separate
  endpoint so a client opts in to inferred places rather than receiving them mixed
  into search results.
- `POST /learned/withdraw` — the issue 09 rollback: remove a promoted POI and
  rebuild the index without it.
- `POST /learned/name` — the *only* way a learned POI gets a name, and only from a
  non-trace source. Movement can show that a place exists; it must never be used to
  infer what it is. A named POI becomes searchable; an unnamed one never does.
- `set_learned_enabled(False)` / `VECTOR_LEARNED_ENABLED=0` as the one-flag
  disable, restoring the pre-learning geocoder exactly.
- `health()` reports `learned_pois` and `learned_enabled`.

**The no-regression guarantee is now structural, and asserted.** Learned POIs live
in a *separate* index that the search path never reads, so name search cannot start
returning inferred places. Tests compare `search()` output byte-for-byte with and
without the learned layer loaded (including Arabic/Latin transliteration) and assert
`GeocodeIndex.size()` is unchanged.

Tests: 31 → 56.
