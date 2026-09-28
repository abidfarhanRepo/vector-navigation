import unittest

from vector_traffic.geometry import (
    haversine_meters,
    bearing_deg,
    point_to_segment_distance_m,
)


class TestGeometry(unittest.TestCase):
    def test_haversine_berlin_paris(self):
        berlin = (13.405, 52.52)
        paris = (2.3522, 48.8566)
        d = haversine_meters(berlin, paris)
        self.assertAlmostEqual(d, 878000.0, delta=2000.0)

    def test_one_degree_lat(self):
        d = haversine_meters((0.0, 0.0), (0.0, 1.0))
        self.assertAlmostEqual(d, 111195.0, delta=200.0)

    def test_symmetry(self):
        a = (13.4, 52.52)
        b = (2.35, 48.85)
        self.assertEqual(haversine_meters(a, b), haversine_meters(b, a))

    def test_point_to_segment_known(self):
        d = point_to_segment_distance_m((0.001, 0.005), (0.0, 0.0), (0.0, 0.01))
        self.assertAlmostEqual(d, 111.32, places=1)

    def test_bearing_sanity(self):
        self.assertAlmostEqual(bearing_deg((0.0, 0.0), (0.0, 1.0)), 0.0, places=5)
        self.assertAlmostEqual(bearing_deg((0.0, 0.0), (1.0, 0.0)), 90.0, places=5)


if __name__ == "__main__":
    unittest.main()
