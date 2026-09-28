"""Tests for the Overture places layer.

The user complaint behind this layer was "half the names that are searchable in
Google Maps and Waze don't come up in the app". The index was nearly empty, not
wrong. These tests pin the four things that could quietly undo the fix:

* Overture rows are projected into features the existing indexer accepts.
* Arabic/Latin transliteration still applies to Overture names (the original
  index's best feature must not become OSM-only).
* Ranking puts an exactly-named place above a road that merely contains the
  string.
* A deployment with no Overture file behaves exactly as it did before.
"""

import importlib.util
import json
import os
import sys
import tempfile
import unittest

from vector_geocoder.index import GeocodeIndex
from vector_geocoder.serve import GeocodeService, default_places_path

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)


def _load_fetch_script():
    """Import the offline ingest script by path.

    It lives in ``scripts/`` on purpose -- it is not part of the package and
    must never be importable from ``vector_geocoder``. Its top-level imports are
    stdlib only (duckdb is imported inside ``main``), so the projection logic is
    testable here without pulling in a third-party dependency.
    """
    path = os.path.join(REPO, "scripts", "fetch_overture_places.py")
    spec = importlib.util.spec_from_file_location("_fetch_overture_places", path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = mod
    spec.loader.exec_module(mod)
    return mod


fetch = _load_fetch_script()


# A basemap standing in for the OSM layer: one road whose name *contains*
# "Villaggio", so ranking has something to get wrong.
BASEMAP = {
    "type": "FeatureCollection",
    "features": [
        {"type": "Feature",
         "geometry": {"type": "LineString",
                      "coordinates": [[51.44, 25.25], [51.45, 25.26]]},
         "properties": {"name": "Villaggio Mall Access Road", "kind": "road",
                        "highway": "service"}},
        {"type": "Feature",
         "geometry": {"type": "LineString",
                      "coordinates": [[51.53, 25.28], [51.54, 25.29]]},
         "properties": {"name": "Salwa Road", "kind": "road", "maxspeed": "80"}},
    ],
}

# What fetch_overture_places.py writes.
PLACES = {
    "type": "FeatureCollection",
    "properties": {"source": "overture", "release": "2026-07-22.0",
                   "confidence_floor": 0.2},
    "features": [
        {"type": "Feature",
         "geometry": {"type": "Point", "coordinates": [51.4433, 25.2561]},
         "properties": {"name": "Villaggio", "kind": "poi", "source": "overture",
                        "category": "shopping_mall", "confidence": 0.95}},
        {"type": "Feature",
         "geometry": {"type": "Point", "coordinates": [51.5310, 25.2870]},
         "properties": {"name": "مطعم سلوى", "kind": "poi", "source": "overture",
                        "category": "restaurant", "confidence": 0.61}},
        {"type": "Feature",
         "geometry": {"type": "Point", "coordinates": [51.5200, 25.3000]},
         "properties": {"name": "Museum of Islamic Art", "kind": "poi",
                        "source": "overture", "confidence": 0.99,
                        "alt_names": ["متحف الفن الإسلامي"]},
         },
    ],
}


class OvertureProjectionTest(unittest.TestCase):
    """The ingest script's row -> feature projection."""

    @staticmethod
    def row(**kw):
        base = {"id": "ovt:1", "name": "Test Cafe", "name_common": None,
                "category": "cafe", "brand": None, "confidence": 0.8,
                "operating_status": "open", "addr_freeform": "Al Sadd St",
                "addr_locality": "Doha", "lon": 51.5, "lat": 25.28}
        base.update(kw)
        return base

    def test_projects_into_indexable_shape(self):
        feats, stats = fetch.build_features([self.row()], 0.2)
        self.assertEqual(stats["written"], 1)
        f = feats[0]
        self.assertEqual(f["geometry"]["type"], "Point")
        props = f["properties"]
        self.assertEqual(props["name"], "Test Cafe")
        self.assertEqual(props["kind"], "poi")
        self.assertEqual(props["source"], "overture")
        self.assertEqual(props["category"], "cafe")
        self.assertEqual(props["locality"], "Doha")
        # The whole point: GeocodeIndex must accept it unchanged.
        idx = GeocodeIndex.from_geojson(
            {"type": "FeatureCollection", "features": feats})
        self.assertEqual(idx.size(), 1)

    def test_confidence_floor_drops_rows_and_counts_them(self):
        rows = [self.row(confidence=0.05), self.row(confidence=0.5),
                self.row(confidence=None)]
        feats, stats = fetch.build_features(rows, 0.2)
        self.assertEqual(len(feats), 1)
        self.assertEqual(stats["dropped_confidence"], 2)
        self.assertEqual(stats["rows_in_bbox"], 3)

    def test_unnamed_and_closed_rows_are_dropped(self):
        rows = [self.row(name=None), self.row(name="   "),
                self.row(operating_status="closed")]
        feats, stats = fetch.build_features(rows, 0.2)
        self.assertEqual(feats, [])
        self.assertEqual(stats["dropped_no_name"], 2)
        self.assertEqual(stats["dropped_closed"], 1)

    def test_missing_geometry_is_dropped_not_crashed(self):
        feats, stats = fetch.build_features([self.row(lon=None, lat=None)], 0.2)
        self.assertEqual(feats, [])
        self.assertEqual(stats["dropped_no_geometry"], 1)

    def test_alt_language_names_are_carried_through(self):
        feats, _ = fetch.build_features(
            [self.row(name_common={"ar": "مقهى", "en": "Test Cafe"})], 0.2)
        # The 'en' entry equals the primary name and is not duplicated.
        self.assertEqual(feats[0]["properties"]["alt_names"], ["مقهى"])

    def test_confidence_histogram_covers_every_row(self):
        rows = [self.row(confidence=c) for c in (0.05, 0.15, 0.95)]
        _, stats = fetch.build_features(rows, 0.2)
        self.assertEqual(sum(stats["confidence_histogram"].values()), 3)

    def test_write_atomic_leaves_no_partial_file(self):
        with tempfile.TemporaryDirectory() as d:
            out = os.path.join(d, "places.geojson")
            fetch.write_atomic(out, {"type": "FeatureCollection", "features": []})
            with open(out, encoding="utf-8") as fh:
                self.assertEqual(json.load(fh)["type"], "FeatureCollection")
            self.assertEqual([n for n in os.listdir(d) if n.endswith(".partial")], [])


class OvertureIndexingTest(unittest.TestCase):
    """Overture entries ADD to the basemap index rather than replacing it."""

    def setUp(self):
        self.svc = GeocodeService(GeocodeIndex.from_geojson(BASEMAP))
        self.added = self.svc._index.extend_from_geojson(PLACES)

    def test_extend_reports_how_many_it_added(self):
        self.assertEqual(self.added, 3)

    def test_basemap_entries_survive(self):
        names = [f["properties"]["name"] for f in self.svc.search("Salwa Road", 5)["features"]]
        self.assertIn("Salwa Road", names)

    def test_overture_entries_are_searchable(self):
        names = [f["properties"]["name"] for f in self.svc.search("Villaggio", 5)["features"]]
        self.assertIn("Villaggio", names)

    def test_provenance_reaches_the_client(self):
        top = self.svc.search("Villaggio", 5)["features"][0]
        self.assertEqual(top["properties"]["source"], "overture")
        self.assertEqual(top["properties"]["category"], "shopping_mall")

    def test_osm_features_carry_no_source_field(self):
        # Existing clients must see an unchanged payload for basemap features.
        top = self.svc.search("Salwa Road", 5)["features"][0]
        self.assertNotIn("source", top["properties"])
        self.assertNotIn("confidence", top["properties"])

    def test_reverse_finds_overture_places(self):
        res = self.svc.reverse(25.2561, 51.4433, 1)
        self.assertEqual(res["features"][0]["properties"]["name"], "Villaggio")


class OvertureTransliterationTest(unittest.TestCase):
    """Arabic <-> Latin matching must work on Overture names, not just OSM ones."""

    def setUp(self):
        self.idx = GeocodeIndex.from_geojson(BASEMAP)
        self.idx.extend_from_geojson(PLACES)

    def test_latin_query_finds_arabic_overture_name(self):
        hits = self.idx.search("salwa")
        self.assertIn("مطعم سلوى", [h.name for h in hits])

    def test_arabic_query_finds_arabic_overture_name(self):
        hits = self.idx.search("سلوى")
        self.assertIn("مطعم سلوى", [h.name for h in hits])

    def test_arabic_alias_makes_a_latin_named_place_findable_in_arabic(self):
        hits = self.idx.search("متحف الفن")
        self.assertIn("Museum of Islamic Art", [h.name for h in hits])

    def test_alias_does_not_replace_the_display_name(self):
        hit = [h for h in self.idx.search("متحف الفن") if h.name == "Museum of Islamic Art"][0]
        self.assertEqual(hit.to_geojson()["properties"]["label"], "Museum of Islamic Art")


class RankingTest(unittest.TestCase):
    def setUp(self):
        self.idx = GeocodeIndex.from_geojson(BASEMAP)
        self.idx.extend_from_geojson(PLACES)

    def test_exact_poi_outranks_road_containing_the_string(self):
        # "Villaggio Mall Access Road" is a prefix match; "Villaggio" is exact.
        # Exact must win, or the mall is buried under its own car-park road.
        hits = self.idx.search("Villaggio")
        self.assertEqual(hits[0].name, "Villaggio")

    def test_prefix_outranks_substring(self):
        idx = GeocodeIndex.from_geojson({
            "type": "FeatureCollection",
            "features": [
                {"type": "Feature",
                 "geometry": {"type": "Point", "coordinates": [51.5, 25.3]},
                 "properties": {"name": "Al Centre Pharmacy", "kind": "poi"}},
                {"type": "Feature",
                 "geometry": {"type": "Point", "coordinates": [51.5, 25.3]},
                 "properties": {"name": "Centre Point", "kind": "poi"}},
            ]})
        self.assertEqual([h.name for h in idx.search("centre")],
                         ["Centre Point", "Al Centre Pharmacy"])

    def test_shorter_name_wins_within_a_tier(self):
        idx = GeocodeIndex.from_geojson({
            "type": "FeatureCollection",
            "features": [
                {"type": "Feature",
                 "geometry": {"type": "Point", "coordinates": [51.5, 25.3]},
                 "properties": {"name": "City Center Doha Parking Level 3", "kind": "poi"}},
                {"type": "Feature",
                 "geometry": {"type": "Point", "coordinates": [51.5, 25.3]},
                 "properties": {"name": "City Center Doha", "kind": "poi"}},
            ]})
        self.assertEqual(idx.search("city center")[0].name, "City Center Doha")

    def test_place_beats_road_on_an_exact_tie(self):
        idx = GeocodeIndex.from_geojson({
            "type": "FeatureCollection",
            "features": [
                {"type": "Feature",
                 "geometry": {"type": "LineString",
                              "coordinates": [[51.5, 25.3], [51.51, 25.31]]},
                 "properties": {"name": "Al Corniche", "kind": "road"}},
                {"type": "Feature",
                 "geometry": {"type": "Point", "coordinates": [51.5, 25.3]},
                 "properties": {"name": "Al Corniche", "kind": "poi",
                                "source": "overture", "confidence": 0.9}},
            ]})
        hits = idx.search("al corniche")
        self.assertEqual(hits[0].kind, "poi")
        self.assertEqual(hits[1].kind, "road")

    def test_higher_confidence_wins_an_otherwise_perfect_tie(self):
        idx = GeocodeIndex.from_geojson({
            "type": "FeatureCollection",
            "features": [
                {"type": "Feature",
                 "geometry": {"type": "Point", "coordinates": [51.5, 25.3]},
                 "properties": {"name": "Kahwa", "kind": "poi",
                                "source": "overture", "confidence": 0.3}},
                {"type": "Feature",
                 "geometry": {"type": "Point", "coordinates": [51.6, 25.4]},
                 "properties": {"name": "Kahwa", "kind": "poi",
                                "source": "overture", "confidence": 0.9}},
            ]})
        self.assertEqual([h.raw["confidence"] for h in idx.search("kahwa")], [0.9, 0.3])

    def test_osm_feature_without_confidence_is_not_demoted(self):
        # An OSM feature has no `confidence`; it must not lose to a
        # low-confidence Overture row purely for lacking the field.
        idx = GeocodeIndex.from_geojson({
            "type": "FeatureCollection",
            "features": [
                {"type": "Feature",
                 "geometry": {"type": "Point", "coordinates": [51.6, 25.4]},
                 "properties": {"name": "Kahwa", "kind": "poi",
                                "source": "overture", "confidence": 0.3}},
                {"type": "Feature",
                 "geometry": {"type": "Point", "coordinates": [51.5, 25.3]},
                 "properties": {"name": "Kahwa", "kind": "poi"}},
            ]})
        self.assertIsNone(idx.search("kahwa")[0].raw.get("confidence"))


class GracefulDegradationTest(unittest.TestCase):
    """No Overture file must mean exactly today's behaviour, not an error."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.base = os.path.join(self.tmp, "qatar.geojson")
        with open(self.base, "w", encoding="utf-8") as fh:
            json.dump(BASEMAP, fh)

    def _baseline_names(self):
        return [f["properties"]["name"]
                for f in GeocodeService.from_geojson(self.base).search("Salwa", 5)["features"]]

    def test_absent_places_file_is_a_no_op(self):
        svc = GeocodeService.from_geojson(
            self.base, places_path=os.path.join(self.tmp, "nope.geojson"))
        self.assertEqual(svc.health()["poi_entries"], 0)
        self.assertEqual(
            [f["properties"]["name"] for f in svc.search("Salwa", 5)["features"]],
            self._baseline_names())

    def test_none_places_path_is_a_no_op(self):
        svc = GeocodeService.from_geojson(self.base, places_path=None)
        self.assertEqual(svc.health()["poi_entries"], 0)

    def test_corrupt_places_file_does_not_break_the_service(self):
        bad = os.path.join(self.tmp, "bad.geojson")
        with open(bad, "w", encoding="utf-8") as fh:
            fh.write("{not json at all")
        svc = GeocodeService.from_geojson(self.base, places_path=bad)
        self.assertEqual(svc.health()["poi_entries"], 0)
        self.assertEqual(
            [f["properties"]["name"] for f in svc.search("Salwa", 5)["features"]],
            self._baseline_names())

    def test_places_file_that_is_not_a_feature_collection_is_ignored(self):
        odd = os.path.join(self.tmp, "odd.geojson")
        with open(odd, "w", encoding="utf-8") as fh:
            json.dump({"type": "Feature", "properties": {}}, fh)
        svc = GeocodeService.from_geojson(self.base, places_path=odd)
        self.assertEqual(svc.health()["poi_entries"], 0)

    def test_loading_places_reports_the_count_in_health(self):
        good = os.path.join(self.tmp, "qatar_places.geojson")
        with open(good, "w", encoding="utf-8") as fh:
            json.dump(PLACES, fh)
        svc = GeocodeService.from_geojson(self.base, places_path=good)
        self.assertEqual(svc.health()["poi_entries"], 3)
        self.assertEqual(svc.health()["entries"], 5)

    def test_default_places_path_found_by_convention(self):
        good = os.path.join(self.tmp, "qatar_places.geojson")
        with open(good, "w", encoding="utf-8") as fh:
            json.dump(PLACES, fh)
        self.assertEqual(default_places_path(self.base), good)

    def test_default_places_path_absent(self):
        self.assertIsNone(default_places_path(self.base))
        self.assertIsNone(default_places_path(""))

    def test_learned_layer_still_works_alongside_places(self):
        good = os.path.join(self.tmp, "qatar_places.geojson")
        with open(good, "w", encoding="utf-8") as fh:
            json.dump(PLACES, fh)
        facts = os.path.join(self.tmp, "learned_pois.json")
        with open(facts, "w", encoding="utf-8") as fh:
            json.dump([{"fact_type": "poi_candidate", "fact_key": "k1",
                        "lng": 51.44, "lat": 25.25, "evidence_count": 7,
                        "confidence": 0.8}], fh)
        svc = GeocodeService.from_geojson(self.base, learned_facts_path=facts,
                                          places_path=good)
        health = svc.health()
        self.assertEqual(health["poi_entries"], 3)
        self.assertEqual(health["learned_pois"], 1)


if __name__ == "__main__":
    unittest.main()
