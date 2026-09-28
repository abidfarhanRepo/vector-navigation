# Vector — POI Quality Pipeline V1 (runbook)

Spec & investigation: `.scratch/vector-poi-quality/spec.md`.

The pipeline turns raw OSM + Overture POI locations into a **canonical,
driver-useful POI dataset before** map/search/tile layers consume them. It
lives in this repo (`vector-ingestion`, the ingestion bounded context,
ADR-0011) and is invoked by `bootstrap.sh` step 2b.

## Why it exists

The Android renderer (`VectorStyle.kt`) accumulated category/name/confidence
denylists because the tiles and search carried *everything*: worker housing,
office registrations, building ids, benches, phantom pins and typo duplicates.
The renderer is the wrong layer — the map/search still contained the junk, and
every new junk class needed a renderer edit. V1 moves the quality decision
upstream; the renderer's ranking (symbol-sort-key) remains, its denylists
become defense-in-depth.

## Running it

```bash
# from the workspace root, on the real corpus:
PYTHONPATH=vector-ingestion/src python3 vector-ingestion/scripts/build_canonical_pois.py \
    --osm     /tmp/qatar.geojson \            # OSM basemap (osm_to_geojson.py output)
    --places  .bootstrap-cache/qatar_places.geojson \  # Overture places
    --region  qatar --out /tmp/poi-out
```

Emits under `--out`:

| file | purpose |
|---|---|
| `canonical_pois.geojson` | canonical dataset (`kind=poi`, provenance retained) — ALL |
| `<region>_places.geojson` | canonical POIs in the geocoder's shape (search) — ALL |
| `bake-input/<region>.geojson` | OSM basemap (all kinds) + **map-visible** canonical POIs — the ONE file `build_qatar_tiles.py` wants |
| `search-input/<region>.geojson` | OSM basemap (all kinds) + **all** canonical POIs — copied over `$GEO` by bootstrap step 2c, so the geocoder indexes everything |
| `poi-audit.json` | the exclusion ledger + the `map_layer` split ledger |

## The map / search split (V1.2)

```
   OSM  ──►  POI quality/rank + density limits  ──►  DRIVER MAP (visible labels)

   Overture Places  ──►  SEARCH / ENRICHMENT ONLY  (never a map label)
```

Overture Places is a **directory-derived** dataset. Searching "AC repair" and
getting Overture rows is the point; painting six apartment-based service
businesses along Al Mansoura while someone is driving down it is not. The V1.1
bake put 18,753 Overture-derived POIs onto a map that had 8,272 named OSM POIs,
and within 400 m of a driver in Al Mansoura the map carried 124 labels — 88 of
them Overture-only.

The policy lives in **one** documented module,
`vector_ingestion/poi/visibility.py`, and is stamped on each record as
`map_visible` / `map_hidden_reason` so it travels into every output file:

| rule | hidden reason | what it means |
|---|---|---|
| R1 | `MAP_HIDDEN_OVERTURE_ONLY` | `source` not in {`osm`, `both`} — a human mapper never put it on a map |
| R2 | `MAP_HIDDEN_NO_CATEGORY` | no usable category (missing / `yes` / `unknown`) — no icon, no rank, nothing to tell the driver |
| R3 | `MAP_HIDDEN_PHONE_THEM_CATEGORY` | `party_and_event_planning`, `travel_services`, `transportation`, `financial_service` — you ring them, you don't drive there |
| R4 | `MAP_HIDDEN_NOT_A_DESTINATION` | `usefulness` below `DESTINATION` (the SUPPORT and NONE buckets) |

R2–R4 apply **on top of** R1, to the OSM-primary records: being on OSM is
necessary, not sufficient.

**No promotion rule for high-quality Overture records.** The only promotion
signal worth having is cross-source agreement, and such a record is already
`source="both"`, which R1 admits. The remaining signals do not measure
map-worthiness: `confidence` is Overture's *conflation* confidence, and
`quality_score` is destination usefulness — it ranks "Doha moving services"
(0.7502) above a real OSM grocery (0.6100). A score threshold would admit
exactly the records this change exists to remove.

**Search is never narrowed.** `<region>_places.geojson`,
`canonical_pois.geojson` and `search-input/<region>.geojson` all carry every
canonical record; only `bake-input/` is filtered. An Overture-only business
like "Movers & Packers Qatar" stays findable and stays off the map. Pinned by
`vector-ingestion/tests/test_poi_map_visibility.py`.

Measured on the 2026-09-14 corpus (39,015 raw → 25,925 canonical):

```
Searchable POIs:        25925   (osm 7171, both 904, overture 17850)
Map-visible POIs:        5328   (osm 4492, both 836, overture 0)
  hidden MAP_HIDDEN_OVERTURE_ONLY            17850
  hidden MAP_HIDDEN_NOT_A_DESTINATION         1399
  hidden MAP_HIDDEN_NO_CATEGORY               1328
  hidden MAP_HIDDEN_PHONE_THEM_CATEGORY         20
Al Mansoura, 400 m: 125 labels -> 17
```

Exit codes: 0 ok, 2 usage, 1 pipeline error. A missing `--places` file is
allowed (OSM-only canonical set).

`bootstrap.sh` step 2b calls this and stages `poi-audit.json` into the tile
volume. If the module is absent or the run fails, bootstrap falls back to the
pre-V1 byte-splice merge (raw), so a bootstrap host can never be blocked by
this feature.

## What the numbers mean (measured on the 260912 snapshot)

```text
Total raw POIs:         39015  (OSM 15816, Overture 23199)
Canonical POIs:         24317  (807 cross-source reconciled, 3414 unknown retained)
Excluded EXCLUDED_UNNAMED             6549   (unnamed furniture/infrastructure)
Excluded EXCLUDED_NON_DESTINATION     4437   (residential, office, industry, furniture)
Excluded EXCLUDED_LOW_CONFIDENCE      2310   (Overture conflation < 0.4)
Excluded EXCLUDED_DUPLICATE           1094   (reconciled/merged, aliases preserved)
Excluded EXCLUDED_LOW_QUALITY_NAME     308   (accommodation, Bldg ids, bare place names)
```

The ledger always reconciles exactly: `raw = canonical + excluded`.
Every excluded record carries one `EXCLUDED_*` reason (`kind=excluded_poi` in
the audit-side export); nothing is silently dropped.

## Design constraints (how V1 stays safe)

* **No giant allowlist.** Unknown categories map to family `UNKNOWN`, are
  **retained**, low-ranked, never deleted. Being wrong about a category costs
  a POI its rank, never its existence.
* **Chains are protected by construction.** Four Woqod stations, 93 Starbucks
  records and the Shater Abbas chain survive untouched: far-copy merging only
  applies to *singleton institutions* (RELIGIOUS, embassy, museum, airport,
  hospital-by-name); brand merges stop at 100 m; same-name merges stop at
  40 m within a source. Two different churches 60 m apart in the Abu Hamour
  Religious Complex are never merged (the "same position + both churches"
  rule is deliberately catholic-only — the measured pair).
* **`confidence` is conflation, not freshness.** The floor is 0.4 (0.5
  wrongly removed Shater Abbas at 0.4922). No threshold detects closed
  businesses; structural quality (this pipeline) and business status (a
  future freshness layer) are separate concerns. Overture `operating_status`
  is only used at fetch time for `closed`; nothing else in the data says a
  shop shut.
* **Singletons resolve from evidence, not radii.** "Churh Of The Holy
  Rosary", "Holy Rosary Church of Doha Qatar.." and "Our Lady of Holy Rosary
  Church, Doha" merge into the ONE real church (conf 0.9563) because their
  names are contained variants; they become **aliases**, so a driver typing a
  typo still finds the church. "Seraphic Hall, Catholic Church of Our Lady
  of the Rosary" keeps its own record — it adds substantive tokens ("seraphic",
  "hall") that are evidence of a different building. The Italian alias at the
  same position merges via the same-position catholic pair.
* **Cross-source reconciliation** (795 same-name pairs within 250 m) yields
  ONE canonical record carrying `osm_ids[]` + `overture_ids[]`; provenance is
  never discarded. `source` is `"both"` for a merged record.

## Known V1 limitations

* **Named non-POI basemap features stay searchable.** A `landuse=residential`
  area named "Ramada Staff Accommodation" renders as landuse (correct map
  data) but the geocoder still indexes the name (2 such records measured).
  Full search hygiene needs a `searchable` flag consumed by `GeocodeIndex` —
  V2.
* **OSM lifecycle tags.** `disused:*`/`abandoned:*` never enter the POI layer
  (they are not POI keys), but nothing records "closed" for a surveyed Open
  business either.
* **The Overture fetch's `alt_names` is empty** (latent struct-parsing bug in
  `fetch_overture_places.py`); aliases currently come from the pipeline's
  duplicate resolution, not from Overture's multi-language names.
* **office/industry rescue is cross-source-only** — a brand-name corporate
  office present ONLY in Overture is excluded like a registry address.

## Stage 0 — display-name repair (`poi/display_names.py`)

Runs on the RAW features of both sources, before `build_records`, so
classification, scoring and duplicate detection all see the repaired name.
It is a separate axis from usefulness: given a record we have decided to
KEEP, is the string fit for a driver to read and to search?

Nothing here deletes. Every rewrite keeps the original spelling as an alias,
and the one structural operation marks copies `EXCLUDED_DUPLICATE` of a
surviving sibling.

* **`clean_text`** — NFKC-folds presentation forms, strips bidi/zero-width
  marks, cuts pasted phone numbers and URLs, turns underscores and repeated
  dots into spaces, collapses whitespace, trims decorative edge punctuation,
  and title-cases shouted multiword names (≥2 words and ≥12 chars, acronyms
  preserved — `BVLGARI` and `VIP GYM` are left alone).
* **`split_bilingual`** — parenthesis, then separator, then single script
  boundary. A label that interleaves the two languages more than once is left
  exactly as it is; guessing there produces a worse name than the original.
* **`resolve_names`** — English leads the display name whenever an English
  form of at least `MIN_LEADING_LATIN_LETTERS` (3) can be recovered; the
  halves become `name:en` / `name:ar`; every discarded spelling becomes an
  alias. The floor exists because two OSM records whose `name:en` was a
  two-letter stub had a good Arabic name replaced by an unsearchable one.
* **`strip_marketing_tail`** — drops "… in Doha/Qatar[/Dubai/…]" only when a
  two-word non-generic name survives (`Yoga in Doha` keeps its tail).
* **`repeated_label_groups`** + `pipeline._collapse_repeated_labels` — ≥5
  identical normalised names among CLASSLESS ANONYMOUS records (UNKNOWN
  family, no category, no address, no locality, no brand) are copies of one
  building label, not N destinations. The best-scoring one survives; the rest
  become `EXCLUDED_DUPLICATE`. Measured: Administration 26, Mechanical Draft
  Cooling 14, Hangar 6, Barwa Commercial 5, District Cooling 5, Ezdan Villa 5.

### `normalize_name` understands Arabic

Previously it kept only `[a-z0-9]`, so **1,687 of 26,096 deployed records
(6.5%) normalised to the empty string**. `name_quality` returned 0.0 for all
of them (mean `quality_score` 0.465 vs 0.670 for Latin names; 22.5% in the
DESTINATION bucket vs 82.9%), `short_name` suppressed their labels, and
`_name_groups` skips empty norms so no two Arabic records could ever be
recognised as the same place. NFKD + the combining strip already unify
أ/إ/آ → ا, ؤ → و, ئ → ي; ة → ه, ى → ي, the Persian ی/ک, the tatweel and the
Arabic-Indic digits are folded explicitly.

### Measured effect

| metric | before | after |
|---|---:|---:|
| records with `name:en` | 5,830 | 24,692 |
| records with `name:ar` | 0 | 2,331 |
| jammed bilingual display name | 790 | 12 |
| Arabic-leading display name | 204 | 4 |
| doubled whitespace / ALL-CAPS / phone-in-name / bidi | 107 / 100 / 10 / 4 | 0 / 2 / 0 / 0 |
| Arabic-only records in DESTINATION | 376 of 1,669 | 1,037 of 1,181 |
| canonical POIs | 26,103 | 25,920 |

Of the 183-record reduction, 180 are duplicate collapses retained in the audit
ledger. Three records lost every representation, all to PRE-EXISTING rules
that cleaning made reachable: `Qatar قطر` → `Qatar` and `Doha - الدوحة` →
`Doha` (`bare-place-name`), `al_noor_decor` → `al noor decor` (`registration`;
the underscore had hidden the word boundary).

## Tests

`tests/test_poi_pipeline.py` — unit + golden dataset:

* must NOT appear: Barwa Al Baraha Workers Accommodation, Toyota accomodation,
  QP Junior Bachelor Accomodation, Building 12, Sks 1, Ezdan Accomodation-1,
  "Doha" (as a motel), the Rosary typo pins, shelters/benches.
* must appear: Shater Abbas (conf 0.4922), Tornado Tower, Holiday Villa,
  Woqod, Green Tea Garden Restaurant, exactly one Holy Rosary canonical.
* chains: 4 Woqod branches / 2 near-Starbucks behaviour pinned.
* `VECTOR_POI_SMOKE=1` runs the full pipeline over the real local corpus
  (builds the OSM basemap from `qatar.osm` on demand) and re-asserts the
  ledger + Rosary resolution.

`tests/test_poi_names.py` — 41 display-name regressions, every fixture a real
string from the deployed corpus: Arabic normalisation and folding, the nine
bilingual split shapes, the weak-English-stub floor, cosmetic repair
(whitespace / bidi / phone / shouting / presentation forms) with the
deliberate-branding cases pinned negative, the marketing tail in both
directions, the repeated-classless-label collapse with chains pinned negative,
and end-to-end: the MOCI Lusail label, an Arabic-only name reaching
DESTINATION, Shater Abbas, 20 Starbucks + 20 Woqod staying separate, the two
60 m churches, the Rosary cluster, and "nothing findable becomes unfindable".
With the repair disabled, 24 of the 41 fail.