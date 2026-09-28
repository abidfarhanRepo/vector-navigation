import unittest

from vector_traffic.errors import InputError
from vector_traffic.segments import RoadSegment, normalize_segment, load_segments


class TestSegments(unittest.TestCase):
    def _feature(self, geom_type, coords, props=None):
        return {
            "type": "Feature",
            "properties": props or {},
            "geometry": {"type": geom_type, "coordinates": coords},
        }

    def test_basic_normalize(self):
        f = self._feature(
            "LineString", [[13.4, 52.52], [13.41, 52.52]], {"id": "S1", "free_flow_kmh": 50.0}
        )
        s = normalize_segment(f)
        self.assertEqual(s.id, "S1")
        self.assertEqual(s.free_flow_kmh, 50.0)
        self.assertEqual(len(s.geometry), 2)

    def test_default_free_flow(self):
        f = self._feature("LineString", [[0.0, 0.0], [0.001, 0.0]])
        s = normalize_segment(f)
        self.assertEqual(s.free_flow_kmh, 50.0)

    def test_explicit_free_flow(self):
        f = self._feature(
            "LineString", [[0.0, 0.0], [0.001, 0.0]], {"free_flow_kmh": 80.0}
        )
        s = normalize_segment(f)
        self.assertEqual(s.free_flow_kmh, 80.0)

    def test_less_than_two_points(self):
        f = self._feature("LineString", [[0.0, 0.0]])
        with self.assertRaises(InputError):
            normalize_segment(f)

    def test_non_linestring(self):
        f = self._feature("Point", [0.0, 0.0])
        with self.assertRaises(InputError):
            normalize_segment(f)

    def test_load_segments(self):
        gj = {
            "type": "FeatureCollection",
            "features": [
                self._feature("LineString", [[0.0, 0.0], [0.001, 0.0]], {"id": "A"}),
                self._feature("LineString", [[0.0, 0.001], [0.001, 0.001]], {"id": "B"}),
            ],
        }
        segs = load_segments(gj)
        self.assertEqual(len(segs), 2)
        self.assertIsInstance(segs[0], RoadSegment)


if __name__ == "__main__":
    unittest.main()
