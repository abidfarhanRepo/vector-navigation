"""Tests for the learned-POI geocoder layer (issue 09)."""

import unittest

from vector_geocoder.index import GeocodeIndex
from vector_geocoder.learned_poi import (
    LEARNED_KIND,
    LearnedPoi,
    LearnedPoiIndex,
    pois_from_facts,
)

MALL = (51.5310, 25.2854)


def poi_fact(key="poi_candidate:51.531:25.285", lng=MALL[0], lat=MALL[1],
             evidence=7, confidence=0.9):
    return {
        "fact_type": "poi_candidate",
        "fact_key": key,
        "lng": lng,
        "lat": lat,
        "evidence_count": evidence,
        "confidence": confidence,
        "payload": {"distinct_trips": evidence},
    }


def osm_fixture():
    """A small OSM-sourced index including an Arabic-named feature."""
    return GeocodeIndex.from_geojson({
        "type": "FeatureCollection",
        "features": [
            {
                "type": "Feature",
                "geometry": {"type": "Point", "coordinates": [51.5300, 25.2800]},
                "properties": {"name": "Souq Waqif", "kind": "poi"},
            },
            {
                "type": "Feature",
                "geometry": {"type": "Point", "coordinates": [51.5250, 25.2900]},
                "properties": {"name": "الدوحة", "kind": "poi"},
            },
        ],
    })


class BuildTest(unittest.TestCase):
    def test_builds_from_promoted_facts(self):
        pois = pois_from_facts([poi_fact()])
        self.assertEqual(len(pois), 1)
        self.assertEqual(pois[0].evidence_count, 7)

    def test_ignores_other_fact_types(self):
        self.assertEqual(pois_from_facts([dict(poi_fact(), fact_type="road_candidate")]), [])

    def test_learned_pois_are_distinguishable_from_osm_entries(self):
        props = pois_from_facts([poi_fact()])[0].to_geojson()["properties"]
        self.assertEqual(props["kind"], LEARNED_KIND)
        self.assertIs(props["learned"], True)


class NamingTest(unittest.TestCase):
    def test_a_learned_poi_starts_unnamed(self):
        poi = pois_from_facts([poi_fact()])[0]
        self.assertIsNone(poi.name)
        self.assertFalse(poi.searchable)

    def test_unnamed_pois_are_excluded_from_searchable_features(self):
        idx = LearnedPoiIndex(pois_from_facts([poi_fact()]))
        self.assertEqual(idx.searchable_features(), [])

    def test_naming_from_a_non_trace_source_makes_it_searchable(self):
        idx = LearnedPoiIndex(pois_from_facts([poi_fact()]))
        self.assertTrue(idx.name_poi("poi_candidate:51.531:25.285", "City Center"))
        self.assertEqual(len(idx.searchable_features()), 1)

    def test_blank_name_is_rejected(self):
        idx = LearnedPoiIndex(pois_from_facts([poi_fact()]))
        self.assertFalse(idx.name_poi("poi_candidate:51.531:25.285", "   "))

    def test_naming_an_unknown_poi_is_a_no_op(self):
        self.assertFalse(LearnedPoiIndex().name_poi("nope", "X"))


class NoSearchRegressionTest(unittest.TestCase):
    """Existing search behaviour must be provably unaffected (issue 09)."""

    def test_osm_search_results_are_identical_with_a_learned_layer_present(self):
        idx = osm_fixture()
        before = [h.name for h in idx.search("souq")]
        # Building the learned layer must not touch the OSM index at all.
        LearnedPoiIndex(pois_from_facts([poi_fact()]))
        after = [h.name for h in idx.search("souq")]
        self.assertEqual(before, after)

    def test_arabic_transliteration_search_still_works(self):
        idx = osm_fixture()
        LearnedPoiIndex(pois_from_facts([poi_fact()]))
        self.assertTrue(idx.search("الدوحة"), "Arabic query still matches")

    def test_learned_pois_never_enter_the_osm_index(self):
        idx = osm_fixture()
        size_before = idx.size()
        LearnedPoiIndex(pois_from_facts([poi_fact()]))
        self.assertEqual(idx.size(), size_before)


class ProximityTest(unittest.TestCase):
    def test_nearby_poi_is_found(self):
        idx = LearnedPoiIndex(pois_from_facts([poi_fact()]))
        hits = idx.near(lat=MALL[1], lon=MALL[0], radius_m=100)
        self.assertEqual(len(hits), 1)
        self.assertAlmostEqual(hits[0][1], 0.0, places=1)

    def test_distant_poi_is_not_found(self):
        idx = LearnedPoiIndex(pois_from_facts([poi_fact()]))
        self.assertEqual(idx.near(lat=25.4000, lon=51.7000, radius_m=250), [])

    def test_results_are_nearest_first_and_deterministic(self):
        idx = LearnedPoiIndex(pois_from_facts([
            poi_fact(key="a", lng=51.5310, lat=25.2854),
            poi_fact(key="b", lng=51.5315, lat=25.2854),
        ]))
        keys = [p.fact_key for p, _ in idx.near(lat=25.2854, lon=51.5310, radius_m=1000)]
        self.assertEqual(keys, ["a", "b"])


class RollbackTest(unittest.TestCase):
    def test_withdraw_removes_a_promoted_poi(self):
        idx = LearnedPoiIndex(pois_from_facts([poi_fact()]))
        self.assertTrue(idx.withdraw("poi_candidate:51.531:25.285"))
        self.assertEqual(len(idx), 0)
        self.assertEqual(idx.near(lat=MALL[1], lon=MALL[0], radius_m=500), [])

    def test_withdrawing_an_unknown_key_is_a_no_op(self):
        self.assertFalse(LearnedPoiIndex().withdraw("nope"))


if __name__ == "__main__":
    unittest.main()
