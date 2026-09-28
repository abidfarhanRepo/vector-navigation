# OSM XML test fixtures

Regenerated 2026-09-08. The originals were referenced by `tests/test_osm.py` but
had never been committed, so three test classes errored in `setUpClass` and the
whole `parse_osm` surface was effectively untested. That is how `parse_osm`
came to only support one of the two OSM XML shapes without anyone noticing.

## `doha_qatar.osm` — roads only

83 real Doha ways inside `51.48,25.26 → 51.55,25.34`, taken from the baked
basemap GeoJSON and re-serialised as standard OSM XML (`<nd ref>` plus separate
`<node>` elements). Includes three segments of **شارع الكورنيش**, which the
suite asserts on to prove Arabic names survive the parse.

Geometry, names, `highway` classes, `maxspeed` and `oneway` values are all real.

## `doha_qatar_full.osm` — mixed kinds

The same 83 roads, plus 20 real parks, 10 real place labels, and **120 synthetic
building polygons**.

The buildings are synthetic and that is deliberate. The production Overpass
query does not request `way["building"]` at all (see the comment in
`bootstrap.sh`), so the extract genuinely contains zero buildings and none could
be sampled. These 120 squares exist purely to exercise the parser's
closed-way → `Polygon` path, including ring closure. **They are not a stand-in
for real building data and must not be used as one** — the missing-buildings gap
in acquisition is a separate open item.

The parks carry an explicit `leisure=park` tag. The two parsers in this project
disagree on taxonomy — `osm_to_geojson.py` maps `landuse=*` to `kind=park`,
while `vector_ingestion.parse_osm` maps `landuse` to `kind=landuse` and reserves
`park` for `leisure`. The fixture tags them the way `parse_osm` classifies. That
divergence is real and worth reconciling; it is recorded here rather than
papered over.

## Regenerating

These are derived from the `vector-data-basemap` volume, so a fresh bootstrap is
required first. The generator is inline in the session that produced them; if
they need rebuilding, sample from `qatar.geojson`, keep the bbox above, and keep
at least one `شارع الكورنيش` way.
