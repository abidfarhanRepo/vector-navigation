"""What ``/reverse`` answers: the language, the distance, and which feature wins.

Three defects were measured against production on 2026-09-21, all of them read
by a driver through the destination chip of a dropped pin:

1. **No language.** ``/search``, ``/speed`` and ``/navigate`` all resolve
   ``lang=en`` through ``name:en``; ``/reverse`` returned the native name
   unconditionally. The nearest feature to a point on a random residential
   street in Mansoura is a way tagged ``name=ابن درهم``, so an English device put
   an Arabic string into the chip, the Recents list and the arrival
   announcement — the product-disagreeing-with-itself defect
   ``GeocodeHit.to_geojson`` already documents for search.

2. **No distance.** The payload carried no ordering evidence at all, so a client
   had no way to bound "only name this pin after something that is actually
   here" — and the ranking is unbounded: a pin dropped in the desert came back
   named after a village **3,528 m** away.

3. **Ranked by a vertex.** A way is indexed at ``ring[0]``, so a street passing
   5 m from the pin lost to one that merely begins nearby. See
   ``test_index.ReverseShapeDistanceTest``.

The negative assertions matter as much as the positive ones: with no ``lang``
the payload must be exactly what it was before, because every existing client
reads it.
"""

import json
import os
import sys
import tempfile
import threading
import unittest
import urllib.request

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src"))

from vector_geocoder.index import _haversine_m  # noqa: E402
from vector_geocoder.serve import GeocodeService, make_server  # noqa: E402

# One street with both a native and a Latin name, one road with only a native
# name, and one POI with only a native name: the three cases `/reverse` is asked
# about, at three distances from the query point.
#
# `Ibn Dirham Street` is deliberately NOT placed on the query point: it runs
# along lon 51.5329 from lat 25.2710 to 25.2730, so the polyline passes ~10 m
# from `HERE` while its FIRST VERTEX is ~111 m away. That gap is the whole
# reason `distance_m` exists (see ReverseDistanceTest).
BASEMAP = {
    "type": "FeatureCollection",
    "features": [
        {"type": "Feature",
         "properties": {"kind": "road", "name": "ابن درهم",
                        "name:en": "Ibn Dirham Street", "highway": "residential"},
         "geometry": {"type": "LineString",
                      "coordinates": [[51.5329, 25.2710], [51.5329, 25.2730]]}},
        {"type": "Feature",
         "properties": {"kind": "poi", "name": "مقهى الوكرة"},
         "geometry": {"type": "Point", "coordinates": [51.5331, 25.2721]}},
        {"type": "Feature",
         "properties": {"kind": "road", "name": "حيان", "highway": "residential"},
         "geometry": {"type": "LineString",
                      "coordinates": [[51.5322, 25.2745], [51.5322, 25.2755]]}},
    ],
}

HERE = (25.2720, 51.5330)


def write_json(path, doc):
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(doc, fh, ensure_ascii=False)
    return path


def names(result):
    return [f["properties"]["name"] for f in result["features"]]


class ReverseLanguageTest(unittest.TestCase):
    """The service methods, which is where the resolution happens."""

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        basemap = write_json(os.path.join(cls.tmp.name, "basemap.geojson"), BASEMAP)
        cls.service = GeocodeService.from_geojson(basemap)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def test_no_lang_returns_the_native_names_unchanged(self):
        """The compatibility guarantee: existing clients see the same payload."""
        result = self.service.reverse(*HERE, limit=3)
        self.assertIn("ابن درهم", names(result))
        self.assertIn("مقهى الوكرة", names(result))
        for feature in result["features"]:
            self.assertNotIn("name_local", feature["properties"])

    def test_lang_en_prefers_the_latin_name(self):
        result = self.service.reverse(*HERE, limit=3, lang="en")
        self.assertIn("Ibn Dirham Street", names(result))
        by_name = {f["properties"]["name"]: f["properties"] for f in result["features"]}
        # Nothing is lost: the native name is still there, under the same key
        # `/search` uses for the same purpose.
        self.assertEqual(by_name["Ibn Dirham Street"]["name_local"], "ابن درهم")
        self.assertEqual(by_name["Ibn Dirham Street"]["kind"], "road")

    def test_lang_en_leaves_a_name_it_cannot_translate_alone(self):
        result = self.service.reverse(*HERE, limit=3, lang="en")
        self.assertIn("مقهى الوكرة", names(result))
        self.assertIn("حيان", names(result))

    def test_an_unknown_lang_is_treated_as_no_preference(self):
        self.assertEqual(
            self.service.reverse(*HERE, limit=3),
            self.service.reverse(*HERE, limit=3, lang="ar"),
        )


class ReverseDistanceTest(unittest.TestCase):
    """Every hit carries the distance it was ranked on.

    Without it a client cannot tell "the shop you are standing in" from "a
    village 3.5 km away" — production named a dropped pin in the desert after
    `Umm Hotta`, **3,528 m** off, because nothing in the payload said how far
    anything was.
    """

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        basemap = write_json(os.path.join(cls.tmp.name, "basemap.geojson"), BASEMAP)
        cls.service = GeocodeService.from_geojson(basemap)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def test_every_feature_reports_a_distance(self):
        result = self.service.reverse(*HERE, limit=3)
        self.assertTrue(result["features"])
        for feature in result["features"]:
            self.assertIsInstance(feature["properties"]["distance_m"], float)

    def test_the_features_are_ordered_by_that_distance(self):
        result = self.service.reverse(*HERE, limit=3)
        distances = [f["properties"]["distance_m"] for f in result["features"]]
        self.assertEqual(distances, sorted(distances))

    def test_the_distance_is_measured_to_the_way_not_to_its_first_vertex(self):
        """The point of the field.

        `Ibn Dirham Street` runs north-south past the query point, and its first
        vertex is at the SOUTH end — ~111 m away. The polyline is ~10 m away. A
        client bounding a dropped pin's name by the vertex would reject the
        street the pin is on.
        """
        result = self.service.reverse(*HERE, limit=3, lang="en")
        by_name = {f["properties"]["name"]: f for f in result["features"]}
        props = by_name["Ibn Dirham Street"]["properties"]
        self.assertAlmostEqual(props["distance_m"], 10.0, delta=2.0)
        vertex = by_name["Ibn Dirham Street"]["geometry"]["coordinates"]
        vertex_m = _haversine_m(HERE[0], HERE[1], vertex[1], vertex[0])
        self.assertGreater(vertex_m, 100.0)
        # Which is the difference the client would otherwise act on.
        self.assertGreater(vertex_m, props["distance_m"] * 5)

    def test_a_far_feature_is_still_reported_far(self):
        """Nothing is clamped: the client decides what "here" means."""
        far = self.service.reverse(24.9, 51.1, limit=1)
        self.assertGreater(far["features"][0]["properties"]["distance_m"], 1000.0)

    def test_the_route_carries_the_distance_too(self):
        status, body = self._get("/reverse?lat=25.2720&lon=51.5330&limit=3&lang=en")
        self.assertEqual(status, 200)
        for feature in body["features"]:
            self.assertIn("distance_m", feature["properties"])

    # ---- harness ---------------------------------------------------------

    def setUp(self):
        self.server = make_server(port=0, service=self.service, host="127.0.0.1")
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)

    def _get(self, path):
        with urllib.request.urlopen(f"http://127.0.0.1:{self.port}{path}", timeout=5) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))


class ReverseLanguageHttpTest(unittest.TestCase):
    """The wiring — `?lang=en` on the query string, not just the signature."""

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        basemap = write_json(os.path.join(cls.tmp.name, "basemap.geojson"), BASEMAP)
        cls.service = GeocodeService.from_geojson(basemap)
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

    def test_lang_en_reaches_the_response(self):
        status, body = self._get("/reverse?lat=25.2720&lon=51.5330&limit=3&lang=en")
        self.assertEqual(status, 200)
        self.assertIn("Ibn Dirham Street", names(body))

    def test_without_lang_the_route_is_unchanged(self):
        status, body = self._get("/reverse?lat=25.2720&lon=51.5330&limit=3")
        self.assertEqual(status, 200)
        self.assertIn("ابن درهم", names(body))

    def test_an_uppercase_lang_is_accepted_like_speed_accepts_it(self):
        status, body = self._get("/reverse?lat=25.2720&lon=51.5330&limit=3&lang=EN")
        self.assertEqual(status, 200)
        self.assertIn("Ibn Dirham Street", names(body))


if __name__ == "__main__":
    unittest.main()
