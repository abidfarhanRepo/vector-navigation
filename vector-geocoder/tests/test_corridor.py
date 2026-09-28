"""Corridor POI search (W53): /along unit tests."""

import unittest

from vector_geocoder.corridor import _sample, corridor_hits
from vector_geocoder.index import GeocodeIndex


def _fc():
    return {"type": "FeatureCollection", "features": [
        # 50 m off the route line -> in a 400 m corridor.
        {"type": "Feature",
         "geometry": {"type": "Point", "coordinates": [51.5315, 25.2860]},
         "properties": {"name": "Fuel Station A", "kind": "fuel"}},
        # 2 km away from anything on the line -> excluded.
        {"type": "Feature",
         "geometry": {"type": "Point", "coordinates": [51.5500, 25.3000]},
         "properties": {"name": "Far Mall", "kind": "mall"}},
        # ~120 m off the route, different kind.
        {"type": "Feature",
         "geometry": {"type": "Point", "coordinates": [51.5350, 25.2916]},
         "properties": {"name": "Corner Cafe", "kind": "cafe"}},
        # Unnamed POI near the route: never searchable, must be excluded.
        {"type": "Feature",
         "geometry": {"type": "Point", "coordinates": [51.5318, 25.2865]},
         "properties": {"kind": "atm"}},
    ]}


# Straight two-point line through central Doha.
LINE = [[51.5300, 25.2850], [51.5400, 25.2950]]


class CorridorTest(unittest.TestCase):
    def setUp(self):
        self.idx = GeocodeIndex.from_geojson(_fc())

    def test_hits_within_radius_sorted_by_detour(self):
        fc = corridor_hits(self.idx, LINE, radius_m=400.0)
        names = [f["properties"]["name"] for f in fc]
        self.assertIn("Fuel Station A", names)
        self.assertIn("Corner Cafe", names)
        self.assertNotIn("Far Mall", names)
        dets = [f["properties"]["detour_m"] for f in fc]
        self.assertEqual(dets, sorted(dets))

    def test_kind_filter(self):
        fc = corridor_hits(self.idx, LINE, radius_m=400.0, kinds=["fuel"])
        names = [f["properties"]["name"] for f in fc]
        self.assertEqual(names, ["Fuel Station A"])

    def test_unnamed_features_never_returned(self):
        fc = corridor_hits(self.idx, LINE, radius_m=400.0)
        self.assertTrue(all(f["properties"].get("name") for f in fc))

    def test_small_radius_excludes_everything(self):
        self.assertEqual(corridor_hits(self.idx, LINE, radius_m=20), [])

    def test_limit(self):
        fc = corridor_hits(self.idx, LINE, radius_m=400.0, limit=1)
        self.assertEqual(len(fc), 1)

    def test_sample_densifies_long_legs(self):
        long_leg = [[51.0, 25.0], [51.05, 25.0]]   # ~5 km
        pts = _sample(long_leg, max_step_m=500)
        self.assertGreater(len(pts), 10)

    def test_degenerate_line(self):
        self.assertEqual(corridor_hits(self.idx, [], radius_m=400), [])
        self.assertEqual(corridor_hits(self.idx, [[51.5, 25.2]], radius_m=400), [])


if __name__ == "__main__":
    unittest.main()
