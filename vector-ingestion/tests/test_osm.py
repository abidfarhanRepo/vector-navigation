"""Real OSM ingestion tests (Wave 27a).

Parses the committed real OSM extract (tests/data/osm/berlin_mitte.osm, fetched
from Overpass — genuine OpenStreetMap ways) and proves it converts into the
routing-graph GeoJSON contract AND builds a real RoutingGraph via the vendored
vector_geo (the exact structure the routing engine serves).
"""

import json
import os
import sys
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FIXTURE = os.path.join(ROOT, "tests", "data", "osm", "doha_qatar.osm")
VENDOR = os.path.join(ROOT, "vendor")
if VENDOR not in sys.path:
    sys.path.insert(0, VENDOR)

from vector_ingestion.osm import parse_osm_file  # noqa: E402


class OsmParseTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fc = parse_osm_file(FIXTURE)
        cls.features = cls.fc["features"]

    def test_featurecollection_shape(self):
        self.assertEqual(self.fc["type"], "FeatureCollection")
        self.assertGreater(len(self.features), 50, "real extract should yield many road ways")

    def test_only_highway_ways_become_features(self):
        for f in self.features:
            self.assertIn("highway", f["properties"])
            self.assertIsNotNone(f["properties"]["highway"])

    def test_linestring_geometry_with_real_coords(self):
        for f in self.features:
            geom = f["geometry"]
            self.assertEqual(geom["type"], "LineString")
            coords = geom["coordinates"]
            self.assertGreaterEqual(len(coords), 2)
            # Real Doha, Qatar extract extent: lon ~51.49-51.55, lat ~25.27-25.33.
            for lon, lat in coords:
                self.assertTrue(51.48 < lon < 51.55, "lon out of Doha range: %r" % lon)
                self.assertTrue(25.26 < lat < 25.34, "lat out of Doha range: %r" % lat)

    def test_properties_carried(self):
        # At least one real named Doha street (e.g. Corniche St / شارع الكورنيش)
        # must appear among the ingested ways.
        names = {f["properties"].get("name") for f in self.features}
        self.assertIn("شارع الكورنيش", names)
        # maxspeed parsed to int where present.
        with_speed = [f for f in self.features if f["properties"].get("maxspeed") is not None]
        self.assertTrue(with_speed, "expected some ways with parsed maxspeed")
        for f in with_speed[:5]:
            self.assertIsInstance(f["properties"]["maxspeed"], int)


class OsmToRoutingGraphTest(unittest.TestCase):
    """Prove the ingested OSM data flows into the real RoutingGraph."""

    def test_build_routing_graph_from_osm(self):
        from vector_geo.algorithms import build_graph_from_features  # vendored

        fc = parse_osm_file(FIXTURE)
        g = build_graph_from_features(fc)
        self.assertGreater(len(g.nodes()), 10, "graph should have many intersection nodes")
        self.assertGreater(len(g.edges()), 10, "graph should have many road edges")
        # Edge properties must carry OSM semantics (highway tag survives).
        highway_props = [props.get("highway") for (_a, _b, _w, props) in g.edges() if props]
        self.assertTrue(any(highway_props), "expected OSM highway tag on edges")


FULL_FIXTURE = os.path.join(ROOT, "tests", "data", "osm", "doha_qatar_full.osm")


class BasemapParseTest(unittest.TestCase):
    """Wave 29: the parser emits basemap features (buildings/parks/labels)."""

    @classmethod
    def setUpClass(cls):
        cls.fc = parse_osm_file(FULL_FIXTURE)
        cls.features = cls.fc["features"]
        cls.by_kind = {}
        for f in cls.features:
            cls.by_kind.setdefault(f["properties"].get("kind"), []).append(f)

    def test_mixed_kinds_present(self):
        self.assertIn("road", self.by_kind)
        self.assertIn("building", self.by_kind)
        self.assertIn("park", self.by_kind)
        self.assertGreater(len(self.by_kind["building"]), 100,
                           "Doha extract should contain many buildings")

    def test_road_features_tagged_and_linestring(self):
        for f in self.by_kind["road"]:
            self.assertEqual(f["properties"].get("kind"), "road")
            self.assertEqual(f["geometry"]["type"], "LineString")

    def test_building_features_polygon(self):
        for f in self.by_kind["building"][:20]:
            self.assertEqual(f["geometry"]["type"], "Polygon")
            ring = f["geometry"]["coordinates"][0]
            # Polygon ring must be closed.
            self.assertEqual(ring[0], ring[-1])

    def test_label_features_point_with_name(self):
        labels = self.by_kind.get("label", [])
        self.assertTrue(labels, "expected place labels")
        for f in labels[:5]:
            self.assertEqual(f["geometry"]["type"], "Point")
            self.assertIn("name", f["properties"])

    def test_roads_only_fixture_still_roads(self):
        # The routing fixture must remain roads-only (no buildings) so the
        # routing graph is unaffected by the basemap extension.
        fc = parse_osm_file(FIXTURE)
        for f in fc["features"]:
            self.assertEqual(f["properties"].get("kind"), "road")


if __name__ == "__main__":
    unittest.main()
