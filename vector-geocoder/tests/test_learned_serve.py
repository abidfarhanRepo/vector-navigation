"""Tests for the learned-POI service + HTTP wiring (issue 09).

``test_learned_poi.py`` covers the index logic and the privacy floor. These
cover the wiring that was missing: that the layer is reachable from
``GeocodeService``, that it surfaces through ``/reverse`` and ``/learned`` but
**never** silently through name search, and that the rollback works.

The most important assertion in this file is negative: existing search results
must be byte-identical with the learned layer loaded. Issue 09 is the highest
privacy-risk consumer in the effort, so "it cannot leak into search" needs to be
a test, not a design intention.
"""

import json
import os
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src"))

from vector_geocoder.learned_poi import LEARNED_KIND, LearnedPoi, LearnedPoiIndex
from vector_geocoder.serve import GeocodeService, make_server, read_learned_facts

# Two named OSM features near Doha, one of them a road with a speed limit.
BASEMAP = {
    "type": "FeatureCollection",
    "features": [
        {"type": "Feature",
         "properties": {"kind": "poi", "name": "Souq Waqif", "poi_class": "marketplace"},
         "geometry": {"type": "Point", "coordinates": [51.5330, 25.2870]}},
        {"type": "Feature",
         "properties": {"kind": "road", "name": "Al Rayyan Road",
                        "highway": "primary", "maxspeed": 80},
         "geometry": {"type": "LineString",
                      "coordinates": [[51.500, 25.280], [51.520, 25.285]]}},
    ],
}

# A promoted poi_candidate ~60 m from Souq Waqif.
POI_FACT = {
    "fact_key": "poi_candidate:51.533:25.288",
    "fact_type": "poi_candidate",
    "evidence_count": 9,
    "confidence": 0.93,
    "lng": 51.5335,
    "lat": 25.2875,
    "payload": {"distinct_trips": 9},
}


def write_json(path, doc):
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(doc, fh)
    return path


class ReadLearnedFactsTest(unittest.TestCase):
    def test_missing_path_is_empty_not_an_error(self):
        self.assertEqual(read_learned_facts(None), [])
        self.assertEqual(read_learned_facts(os.path.join(tempfile.gettempdir(), "nope.json")), [])

    def test_wrapped_and_bare_forms_load(self):
        with tempfile.TemporaryDirectory() as tmp:
            wrapped = write_json(os.path.join(tmp, "w.json"), {"facts": [POI_FACT]})
            bare = write_json(os.path.join(tmp, "b.json"), [POI_FACT])
            self.assertEqual(len(read_learned_facts(wrapped)), 1)
            self.assertEqual(len(read_learned_facts(bare)), 1)

    def test_corrupt_export_does_not_take_the_geocoder_down(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "bad.json")
            with open(path, "w", encoding="utf-8") as fh:
                fh.write("{{{")
            self.assertEqual(read_learned_facts(path), [])


class LearnedServiceTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.basemap = write_json(os.path.join(self.tmp.name, "basemap.geojson"), BASEMAP)
        self.export = write_json(os.path.join(self.tmp.name, "learned_pois.json"),
                                 {"facts": [POI_FACT]})

    def tearDown(self):
        self.tmp.cleanup()

    def _service(self, with_learned=True):
        return GeocodeService.from_geojson(
            self.basemap, learned_facts_path=self.export if with_learned else None)

    def test_learned_pois_load_from_the_export(self):
        service = self._service()
        self.assertEqual(service.health()["learned_pois"], 1)

    def test_search_results_are_identical_with_and_without_the_learned_layer(self):
        """The no-regression guarantee, asserted rather than assumed."""
        plain = self._service(with_learned=False).search("souq", 20)
        learned = self._service().search("souq", 20)
        self.assertEqual(plain, learned)

    def test_arabic_transliteration_search_is_unaffected(self):
        plain = self._service(with_learned=False).search("Al Rayyan", 20)
        learned = self._service().search("Al Rayyan", 20)
        self.assertEqual(plain, learned)
        self.assertTrue(plain["features"])

    def test_osm_index_size_is_unchanged_by_the_learned_layer(self):
        self.assertEqual(
            self._service(with_learned=False).health()["entries"],
            self._service().health()["entries"],
        )

    def test_unnamed_learned_poi_never_appears_in_search(self):
        service = self._service()
        for query in ("", "unnamed", "place", "poi", "51.5335", "learned"):
            for feature in service.search(query or "a", 50)["features"]:
                self.assertNotEqual(feature["properties"].get("kind"), LEARNED_KIND)

    def test_reverse_surfaces_a_learned_poi_at_that_spot(self):
        result = self._service().reverse(25.2875, 51.5335, limit=3)
        kinds = [f["properties"].get("kind") for f in result["features"]]
        self.assertIn(LEARNED_KIND, kinds)
        learned = [f for f in result["features"] if f["properties"].get("kind") == LEARNED_KIND][0]
        self.assertTrue(learned["properties"]["learned"])
        self.assertIsNone(learned["properties"]["name"])
        self.assertIn("distance_m", learned["properties"])

    def test_reverse_far_away_returns_no_learned_poi(self):
        result = self._service().reverse(25.9, 51.9, limit=3)
        for feature in result["features"]:
            self.assertNotEqual(feature["properties"].get("kind"), LEARNED_KIND)

    def test_osm_hits_come_before_learned_ones(self):
        result = self._service().reverse(25.2870, 51.5330, limit=3)
        kinds = [f["properties"].get("kind") for f in result["features"]]
        self.assertGreater(kinds.index(LEARNED_KIND), 0,
                           "an inferred place outranked surveyed OSM data")

    def test_disabling_the_layer_restores_previous_reverse_output_exactly(self):
        service = self._service()
        service.set_learned_enabled(False)
        plain = self._service(with_learned=False).reverse(25.2875, 51.5335, limit=3)
        self.assertEqual(service.reverse(25.2875, 51.5335, limit=3), plain)

    def test_withdraw_removes_a_promoted_poi(self):
        service = self._service()
        self.assertTrue(service.withdraw_learned(POI_FACT["fact_key"]))
        self.assertEqual(service.health()["learned_pois"], 0)
        self.assertFalse(service.withdraw_learned(POI_FACT["fact_key"]))

    def test_naming_from_a_non_trace_source_makes_it_searchable(self):
        service = self._service()
        self.assertFalse(service.search("Falcon", 20)["features"])
        self.assertTrue(service.name_learned_poi(POI_FACT["fact_key"], "Falcon Souq Annex"))
        hits = service.search("Falcon", 20)["features"]
        self.assertEqual(len(hits), 1)
        # Still tagged as learned, so it stays distinguishable from OSM data.
        self.assertEqual(hits[0]["properties"]["kind"], LEARNED_KIND)

    def test_naming_an_unknown_key_fails_cleanly(self):
        self.assertFalse(self._service().name_learned_poi("nope", "Whatever"))

    def test_learned_layer_endpoint_payload(self):
        layer = self._service().learned_layer()
        self.assertEqual(layer["type"], "FeatureCollection")
        self.assertEqual(layer["properties"]["count"], 1)
        self.assertEqual(layer["properties"]["named"], 0)

    def test_search_limit_is_still_respected_with_named_learned_pois(self):
        service = self._service()
        service.name_learned_poi(POI_FACT["fact_key"], "Souq Extra")
        self.assertLessEqual(len(service.search("souq", 1)["features"]), 1)


class LearnedGeocoderHttpTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        basemap = write_json(os.path.join(cls.tmp.name, "basemap.geojson"), BASEMAP)
        export = write_json(os.path.join(cls.tmp.name, "learned_pois.json"), {"facts": [POI_FACT]})
        cls.service = GeocodeService.from_geojson(basemap, learned_facts_path=export)
        cls.server = make_server(port=0, service=cls.service, host="127.0.0.1")
        cls.port = cls.server.server_address[1]
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=5)
        cls.tmp.cleanup()

    def _get(self, path):
        with urllib.request.urlopen(f"http://127.0.0.1:{self.port}{path}", timeout=5) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))

    def _post(self, path, payload):
        req = urllib.request.Request(
            f"http://127.0.0.1:{self.port}{path}",
            data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=5) as resp:
                return resp.status, json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read().decode("utf-8"))

    def test_healthz_reports_the_learned_count(self):
        status, body = self._get("/healthz")
        self.assertEqual(status, 200)
        self.assertEqual(body["learned_pois"], 1)

    def test_learned_endpoint_returns_the_layer(self):
        status, body = self._get("/learned")
        self.assertEqual(status, 200)
        self.assertEqual(len(body["features"]), 1)
        self.assertEqual(body["features"][0]["properties"]["kind"], LEARNED_KIND)

    def test_reverse_includes_the_learned_poi(self):
        status, body = self._get("/reverse?lat=25.2875&lon=51.5335&limit=3")
        self.assertEqual(status, 200)
        self.assertIn(LEARNED_KIND, [f["properties"].get("kind") for f in body["features"]])

    def test_search_does_not(self):
        status, body = self._get("/search?q=souq&limit=20")
        self.assertEqual(status, 200)
        self.assertNotIn(LEARNED_KIND, [f["properties"].get("kind") for f in body["features"]])

    def test_withdraw_over_http(self):
        status, body = self._post("/learned/withdraw", {"fact_key": POI_FACT["fact_key"]})
        self.assertEqual(status, 200)
        self.assertEqual(body["status"], "withdrawn")
        status, body = self._get("/learned")
        self.assertEqual(len(body["features"]), 0)
        # Restore so test ordering cannot matter.
        self.service.load_learned_pois(
            os.path.join(self.tmp.name, "learned_pois.json"))

    def test_withdraw_requires_a_fact_key(self):
        status, _ = self._post("/learned/withdraw", {})
        self.assertEqual(status, 400)

    def test_name_requires_a_non_empty_name(self):
        status, _ = self._post("/learned/name", {"fact_key": POI_FACT["fact_key"], "name": "  "})
        self.assertEqual(status, 400)

    def test_unknown_post_path_is_404(self):
        status, _ = self._post("/nope", {})
        self.assertEqual(status, 404)


if __name__ == "__main__":
    unittest.main()
