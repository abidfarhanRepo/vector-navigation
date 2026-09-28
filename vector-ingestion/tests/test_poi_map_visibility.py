"""The MAP / SEARCH split (V1.2).

    OSM  ->  POI quality/rank  ->  DRIVER MAP (visible labels)
    Overture Places  ->  SEARCH / ENRICHMENT ONLY  (never a map label)

The V1.1 bake put 18,753 Overture-derived POIs onto a map that previously had
8,272 named OSM POIs. Within 400 m of a dense residential point in Al
Mansoura the map carried 124 labels — 88 Overture-only — including
"Doha moving services", "Movers & Packers Qatar", "Washing Machine and AC
Repair Doha Qatar" and a record whose name is a phone number.

These tests pin the decision in both directions:

* the MAP loses Overture-only records, no-category records, the
  "phone-them" categories and everything below the DESTINATION bucket;
* SEARCH loses NOTHING. `<region>_places.geojson` and
  `canonical_pois.geojson` still carry every canonical record, and
  `search-input/<region>.geojson` — what the geocoder indexes — carries them
  merged into the basemap.

The unit-level cases run everywhere. The corpus cases run against the real
Qatar data when it is present (`VECTOR_POI_AUDIT_DIR`), like the other
real-data regressions in this suite.
"""

import json
import math
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src"))

from vector_ingestion.poi import score as score_mod
from vector_ingestion.poi.model import CanonicalPoi
from vector_ingestion.poi.pipeline import run_pipeline
from vector_ingestion.poi.visibility import (
    MAP_HIDDEN_NOT_A_DESTINATION,
    MAP_HIDDEN_NO_CATEGORY,
    MAP_HIDDEN_OVERTURE_ONLY,
    MAP_HIDDEN_PHONE_THEM_CATEGORY,
    MAP_VISIBLE_SOURCES,
    MAP_VISIBLE_USEFULNESS,
    PHONE_THEM_CATEGORIES,
    assign_map_visibility,
    is_map_visible,
    map_hidden_reason,
)

_HERE = os.path.dirname(os.path.abspath(__file__))
_ROOT = os.path.dirname(os.path.dirname(_HERE))

AL_MANSOURA = (51.5305, 25.2660)   # lon, lat — the driver's real position
RADIUS_M = 400.0


def _metres(lon, lat, lon0=AL_MANSOURA[0], lat0=AL_MANSOURA[1]):
    dx = (lon - lon0) * 111320.0 * math.cos(math.radians(lat0))
    dy = (lat - lat0) * 110540.0
    return math.hypot(dx, dy)


def _poi(name, **kw):
    kw.setdefault("lon", 51.53)
    kw.setdefault("lat", 25.27)
    kw.setdefault("family", "FOOD")
    kw.setdefault("usefulness", "DESTINATION")
    kw.setdefault("category", "restaurant")
    kw.setdefault("source", "osm")
    return CanonicalPoi(canonical_id=f"poi:t:{name}", name=name, **kw)


# ---------------------------------------------------------------------------
# The policy itself
# ---------------------------------------------------------------------------

class MapVisibilityPolicyTest(unittest.TestCase):

    def test_osm_destination_is_map_visible(self):
        self.assertTrue(is_map_visible(source="osm", category="restaurant",
                                       usefulness="DESTINATION"))

    def test_cross_source_destination_is_map_visible(self):
        self.assertTrue(is_map_visible(source="both", category="fuel",
                                       usefulness="DESTINATION"))

    def test_overture_only_is_never_map_visible(self):
        """R1 — the whole point. A directory row is not a map label."""
        self.assertEqual(
            map_hidden_reason(source="overture", category="restaurant",
                              usefulness="DESTINATION"),
            MAP_HIDDEN_OVERTURE_ONLY)

    def test_overture_only_stays_hidden_however_good_it_looks(self):
        """No promotion rule: a perfect-looking Overture row is still hidden.

        Cross-source agreement is the only promotion signal that means
        anything, and a record with it is already `source="both"`.
        """
        for category in ("restaurant", "hospital", "gas_station"):
            self.assertFalse(is_map_visible(source="overture",
                                            category=category,
                                            usefulness="DESTINATION"),
                             category)

    def test_no_category_is_hidden_even_when_osm(self):
        for category in (None, "", "   ", "yes", "YES", "unknown"):
            self.assertEqual(
                map_hidden_reason(source="osm", category=category,
                                  usefulness="DESTINATION"),
                MAP_HIDDEN_NO_CATEGORY, repr(category))

    def test_phone_them_categories_are_hidden_even_when_osm(self):
        for category in sorted(PHONE_THEM_CATEGORIES):
            self.assertEqual(
                map_hidden_reason(source="both", category=category,
                                  usefulness="DESTINATION"),
                MAP_HIDDEN_PHONE_THEM_CATEGORY, category)

    def test_support_and_none_are_hidden_even_when_osm(self):
        for bucket in ("SUPPORT", "NONE", "UNKNOWN"):
            self.assertEqual(
                map_hidden_reason(source="osm", category="laundry",
                                  usefulness=bucket),
                MAP_HIDDEN_NOT_A_DESTINATION, bucket)

    def test_the_destination_bucket_name_matches_the_scorer(self):
        """The policy hard-codes the string; it must be the scorer's."""
        self.assertEqual(MAP_VISIBLE_USEFULNESS,
                         score_mod.USEFULNESS_DESTINATION)

    def test_source_set_is_exactly_the_osm_primary_sources(self):
        self.assertEqual(MAP_VISIBLE_SOURCES, frozenset({"osm", "both"}))

    def test_assign_stamps_every_record_and_counts_each_rule(self):
        pois = [
            _poi("OSM Restaurant"),                                   # visible
            _poi("Overture Restaurant", source="overture"),           # R1
            _poi("Classless Tower", category="yes"),                  # R2
            _poi("A One Events", category="party_and_event_planning"),  # R3
            _poi("Yousef Laundry", category="laundry",
                 usefulness="SUPPORT"),                               # R4
            # fails R1, R2 and R4 at once — first reason is R1, and all three
            # rules must still be counted independently.
            _poi("qlassic", source="overture", category=None,
                 usefulness="SUPPORT"),
        ]
        stats = assign_map_visibility(pois)
        self.assertEqual(stats["map_visible"], 1)
        self.assertEqual([p.map_visible for p in pois],
                         [True, False, False, False, False, False])
        self.assertEqual([p.map_hidden_reason for p in pois], [
            None,
            MAP_HIDDEN_OVERTURE_ONLY,
            MAP_HIDDEN_NO_CATEGORY,
            MAP_HIDDEN_PHONE_THEM_CATEGORY,
            MAP_HIDDEN_NOT_A_DESTINATION,
            MAP_HIDDEN_OVERTURE_ONLY,
        ])
        standalone = stats["map_hidden_by_rule_standalone"]
        self.assertEqual(standalone[MAP_HIDDEN_OVERTURE_ONLY], 2)
        self.assertEqual(standalone[MAP_HIDDEN_NO_CATEGORY], 2)
        self.assertEqual(standalone[MAP_HIDDEN_PHONE_THEM_CATEGORY], 1)
        self.assertEqual(standalone[MAP_HIDDEN_NOT_A_DESTINATION], 2)
        # the quality ledger counts only records that PASSED the source rule
        cost = stats["map_hidden_quality_cost_over_osm_primary"]
        self.assertEqual(cost[MAP_HIDDEN_NO_CATEGORY], 1)
        self.assertEqual(cost[MAP_HIDDEN_PHONE_THEM_CATEGORY], 1)
        self.assertEqual(cost[MAP_HIDDEN_NOT_A_DESTINATION], 1)

    def test_the_decision_is_on_the_record_and_in_the_geojson(self):
        visible, hidden = _poi("Kainan"), _poi("Movers", source="overture")
        assign_map_visibility([visible, hidden])
        self.assertIs(visible.to_geojson()["properties"]["map_visible"], True)
        self.assertIs(hidden.to_geojson()["properties"]["map_visible"], False)
        self.assertEqual(hidden.to_geojson()["properties"]["map_hidden_reason"],
                         MAP_HIDDEN_OVERTURE_ONLY)
        # ... and in the SEARCH shape too (include_provenance=False), so the
        # split is inspectable in every file the pipeline writes.
        self.assertIs(hidden.to_geojson(False)["properties"]["map_visible"],
                      False)


# ---------------------------------------------------------------------------
# The pipeline honours it: bake input filtered, search files not
# ---------------------------------------------------------------------------

def _feature(name, lon, lat, **props):
    p = {"kind": "poi", "name": name}
    p.update(props)
    return {"type": "Feature",
            "geometry": {"type": "Point", "coordinates": [lon, lat]},
            "properties": p}


class PipelineSplitTest(unittest.TestCase):
    """A synthetic corpus that exercises every rule end to end."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        osm = {"type": "FeatureCollection", "features": [
            {"type": "Feature",
             "geometry": {"type": "LineString",
                          "coordinates": [[51.53, 25.26], [51.531, 25.261]]},
             "properties": {"kind": "road", "name": "Al Mansoura St",
                            "highway": "primary"}},
            _feature("Qutba restaurant", 51.5300, 25.2660, poi_class="restaurant"),
            _feature("Arab Bank", 51.5310, 25.2661, poi_class="bank"),
            _feature("Yousef Laundry", 51.5311, 25.2662, poi_class="laundry"),
            _feature("Black Tower", 51.5312, 25.2663, poi_class="yes"),
            _feature("Kanoo Travel", 51.5313, 25.2664, poi_class="travel_services"),
        ]}
        places = {"type": "FeatureCollection", "features": [
            _feature("Movers & Packers Qatar", 51.5320, 25.2665,
                     category="transportation", confidence=0.2235),
            _feature("Washing Machine and AC Repair Doha Qatar", 51.5321, 25.2666,
                     category="appliance_repair_service", confidence=0.2793),
            _feature("Thara Restaurant", 51.5322, 25.2667,
                     category="restaurant", confidence=0.71),
            # same place as the OSM restaurant -> reconciles to source="both"
            _feature("Qutba restaurant", 51.53001, 25.26601,
                     category="restaurant", confidence=0.62),
        ]}
        self.osm_path = os.path.join(self.tmp, "qatar.geojson")
        self.places_path = os.path.join(self.tmp, "qatar_places.geojson")
        with open(self.osm_path, "w", encoding="utf-8") as fh:
            json.dump(osm, fh)
        with open(self.places_path, "w", encoding="utf-8") as fh:
            json.dump(places, fh)

        self.bake = os.path.join(self.tmp, "bake-input", "qatar.geojson")
        self.search_basemap = os.path.join(self.tmp, "search-input", "qatar.geojson")
        self.places_out = os.path.join(self.tmp, "qatar_places.out.geojson")
        self.canonical = os.path.join(self.tmp, "canonical_pois.geojson")
        self.audit = run_pipeline(
            self.osm_path, self.places_path,
            out_canonical=self.canonical, out_places=self.places_out,
            out_bake_input=self.bake, out_search_basemap=self.search_basemap,
            out_audit=os.path.join(self.tmp, "poi-audit.json"))

    def tearDown(self):
        import shutil
        shutil.rmtree(self.tmp, ignore_errors=True)

    @staticmethod
    def _pois(path):
        with open(path, encoding="utf-8") as fh:
            return [f for f in json.load(fh)["features"]
                    if (f.get("properties") or {}).get("kind") == "poi"]

    @staticmethod
    def _names(features):
        return {f["properties"].get("name") for f in features}

    # -- the map ----------------------------------------------------------

    def test_bake_input_has_no_overture_only_poi(self):
        sources = {(f["properties"].get("name"), f["properties"].get("source"))
                   for f in self._pois(self.bake)}
        offenders = sorted(n for n, s in sources if s not in MAP_VISIBLE_SOURCES)
        self.assertEqual(offenders, [],
                         f"Overture-only POIs reached the map: {offenders}")

    def test_the_al_mansoura_offenders_are_off_the_map(self):
        names = self._names(self._pois(self.bake))
        for gone in ("Movers & Packers Qatar",
                     "Washing Machine and AC Repair Doha Qatar",
                     "Thara Restaurant",     # Overture-only, perfectly fine row
                     "Black Tower",          # OSM, no category
                     "Kanoo Travel",         # OSM, phone-them category
                     "Yousef Laundry"):      # OSM, SUPPORT
            self.assertNotIn(gone, names)

    def test_real_osm_destinations_keep_their_label(self):
        names = self._names(self._pois(self.bake))
        self.assertIn("Qutba restaurant", names)   # reconciled to "both"
        self.assertIn("Arab Bank", names)

    def test_bake_input_keeps_the_basemap(self):
        with open(self.bake, encoding="utf-8") as fh:
            kinds = {(f.get("properties") or {}).get("kind")
                     for f in json.load(fh)["features"]}
        self.assertIn("road", kinds)

    # -- search -----------------------------------------------------------

    def test_search_file_keeps_every_canonical_record(self):
        search = self._pois(self.places_out)
        canonical = self._pois(self.canonical)
        self.assertEqual(len(search), len(canonical))
        self.assertEqual(len(search), self.audit["canonical"]["total"])

    def test_an_overture_only_business_is_searchable_but_not_on_the_map(self):
        """The acceptance case, in one assertion."""
        search = self._names(self._pois(self.places_out))
        mapped = self._names(self._pois(self.bake))
        self.assertIn("Movers & Packers Qatar", search)
        self.assertNotIn("Movers & Packers Qatar", mapped)

    def test_search_basemap_carries_every_canonical_poi(self):
        """bootstrap step 2c copies THIS over $GEO, so it must not be filtered."""
        self.assertEqual(len(self._pois(self.search_basemap)),
                         self.audit["canonical"]["total"])
        self.assertIn("Movers & Packers Qatar",
                      self._names(self._pois(self.search_basemap)))

    def test_search_basemap_is_strictly_bigger_than_the_bake_input(self):
        self.assertGreater(len(self._pois(self.search_basemap)),
                           len(self._pois(self.bake)))

    def test_map_layer_ledger_reconciles(self):
        m = self.audit["map_layer"]
        self.assertEqual(m["searchable"], self.audit["canonical"]["total"])
        self.assertEqual(
            m["map_visible"] + sum(m["map_hidden_by_first_reason"].values()),
            m["searchable"])
        self.assertEqual(m["map_visible"], len(self._pois(self.bake)))
        self.assertEqual(set(m["map_visible_by_source"]) - set(MAP_VISIBLE_SOURCES),
                         set())


# ---------------------------------------------------------------------------
# The real corpus: the Al Mansoura acceptance case
# ---------------------------------------------------------------------------

class AlMansouraRealCorpusTest(unittest.TestCase):
    """The measurement the owner will judge this on."""

    @classmethod
    def setUpClass(cls):
        audit_dir = os.environ.get("VECTOR_POI_AUDIT_DIR", "")
        osm = audit_dir and os.path.join(audit_dir, "qatar.geojson")
        places = (audit_dir and os.path.join(audit_dir, "qatar_places.geojson")) \
            or os.path.join(_ROOT, ".bootstrap-cache", "qatar_places.geojson")
        if not (osm and os.path.exists(osm) and os.path.exists(places)):
            raise unittest.SkipTest(
                "Qatar corpus not present (set VECTOR_POI_AUDIT_DIR)")
        cls.tmp = tempfile.mkdtemp()
        bake = os.path.join(cls.tmp, "bake-input", "qatar.geojson")
        search_basemap = os.path.join(cls.tmp, "search-input", "qatar.geojson")
        places_out = os.path.join(cls.tmp, "qatar_places.geojson")
        cls.audit = run_pipeline(osm, places, out_bake_input=bake,
                                 out_places=places_out,
                                 out_search_basemap=search_basemap)
        with open(bake, encoding="utf-8") as fh:
            cls.bake_pois = [f for f in json.load(fh)["features"]
                             if (f.get("properties") or {}).get("kind") == "poi"]
        with open(places_out, encoding="utf-8") as fh:
            cls.search_pois = [f for f in json.load(fh)["features"]
                               if (f.get("properties") or {}).get("kind") == "poi"]

    @classmethod
    def tearDownClass(cls):
        import shutil
        if getattr(cls, "tmp", None):
            shutil.rmtree(cls.tmp, ignore_errors=True)

    def _near(self, features):
        return [f for f in features
                if _metres(*f["geometry"]["coordinates"]) <= RADIUS_M]

    def test_the_driver_on_al_mansoura_sees_a_readable_map(self):
        """124 labels within 400 m was the complaint. Cap it hard."""
        near = self._near(self.bake_pois)
        names = sorted(f["properties"].get("name") or "" for f in near)
        self.assertLess(len(near), 30,
                        f"{len(near)} labels within {RADIUS_M:.0f} m of the "
                        f"driver: {names}")

    def test_no_overture_only_label_near_the_driver(self):
        bad = [f["properties"].get("name") for f in self._near(self.bake_pois)
               if f["properties"].get("source") not in MAP_VISIBLE_SOURCES]
        self.assertEqual(bad, [], f"Overture-only labels on Al Mansoura: {bad}")

    def test_the_named_offenders_are_gone_from_the_map(self):
        names = {f["properties"].get("name") for f in self.bake_pois}
        for gone in ("Doha moving services", "Movers & Packers Qatar",
                     "HriAn Creative Solutions",
                     "Washing Machine and AC Repair Doha Qatar"):
            self.assertNotIn(gone, names, f"{gone} is still a map label")

    def test_those_offenders_are_still_findable_by_search(self):
        names = {f["properties"].get("name") for f in self.search_pois}
        for kept in ("Doha moving services", "Movers & Packers Qatar",
                     "Washing Machine and AC Repair Doha Qatar"):
            self.assertIn(kept, names, f"{kept} vanished from SEARCH")

    def test_search_still_answers_the_queries_that_mattered(self):
        blob = [(f["properties"].get("name") or "").casefold()
                for f in self.search_pois]
        for needle in ("villaggio", "woqod", "shater abbas",
                       "ministry of commerce", "holy rosary"):
            self.assertTrue(any(needle in n for n in blob),
                            f"search corpus lost {needle!r}")

    def test_the_map_is_no_longer_mostly_overture(self):
        m = self.audit["map_layer"]
        self.assertEqual(m["map_visible_by_source"].get("overture", 0), 0)
        self.assertLessEqual(m["map_visible"], m["searchable"])

    def test_search_keeps_every_canonical_record(self):
        self.assertEqual(len(self.search_pois),
                         self.audit["canonical"]["total"])


# ---------------------------------------------------------------------------
# bootstrap wiring: the two merged files must not be swapped
# ---------------------------------------------------------------------------

class BootstrapWiringTest(unittest.TestCase):
    """Step 2c must copy the SEARCH basemap over $GEO, never the bake input.

    $GEO becomes the geocoder's index. Copying the (map-filtered) bake input
    there would delete ~17,850 Overture records from SEARCH as a side effect of
    a MAP decision — the exact failure this change exists to prevent.
    """

    @classmethod
    def setUpClass(cls):
        path = os.path.join(_ROOT, "bootstrap.sh")
        if not os.path.exists(path):
            raise unittest.SkipTest("bootstrap.sh not in this checkout")
        with open(path, encoding="utf-8") as fh:
            cls.sh = fh.read()

    def test_the_pipeline_emits_a_search_basemap(self):
        self.assertIn('SEARCH_GEO="$WORK/search-input/${REGION}.geojson"', self.sh)

    def test_step_2c_copies_the_search_basemap_over_geo(self):
        self.assertIn('cp "$SEARCH_GEO" "$GEO"', self.sh)

    def test_the_search_basemap_is_not_copied_into_the_basemap_volume(self):
        """It lives in a subdirectory so `cp /src/*.geojson` cannot pick it up."""
        self.assertIn("search-input/", self.sh)
        self.assertNotIn('SEARCH_GEO="$WORK/${REGION}', self.sh)

    def test_search_geo_has_a_default_because_set_u_is_on(self):
        self.assertIn("set -euo pipefail", self.sh)
        self.assertIn('SEARCH_GEO=""', self.sh)

    def test_the_tile_bake_still_reads_the_bake_input(self):
        self.assertIn('--geojson "$BAKE_GEO"', self.sh)


if __name__ == "__main__":
    unittest.main()
