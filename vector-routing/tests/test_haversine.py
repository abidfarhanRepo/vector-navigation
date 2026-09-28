import unittest

from vector_routing.haversine import haversine_meters, haversine_meters_coord


class HaversineTest(unittest.TestCase):
    def test_berlin_paris(self):
        berlin = (13.405, 52.520)
        paris = (2.352, 48.857)
        d = haversine_meters(berlin, paris)
        self.assertGreater(d, 878000 * 0.98)
        self.assertLess(d, 878000 * 1.02)

    def test_one_degree_latitude(self):
        d = haversine_meters((0.0, 0.0), (0.0, 1.0))
        self.assertGreater(d, 111000 * 0.98)
        self.assertLess(d, 111000 * 1.02)

    def test_symmetry(self):
        a = (13.405, 52.520)
        b = (2.352, 48.857)
        self.assertAlmostEqual(haversine_meters(a, b), haversine_meters(b, a), places=6)

    def test_identity_zero(self):
        self.assertEqual(haversine_meters((13.405, 52.520), (13.405, 52.520)), 0.0)

    def test_coord_dict_flat(self):
        a = {"lon": 13.405, "lat": 52.520}
        b = {"lon": 2.352, "lat": 48.857}
        self.assertAlmostEqual(
            haversine_meters_coord(a, b), haversine_meters((13.405, 52.520), (2.352, 48.857)), places=6
        )

    def test_coord_dict_typed(self):
        a = {"type": "Coordinate", "lon": 13.405, "lat": 52.520}
        b = {"type": "Coordinate", "lon": 2.352, "lat": 48.857}
        self.assertAlmostEqual(
            haversine_meters_coord(a, b), haversine_meters((13.405, 52.520), (2.352, 48.857)), places=6
        )


if __name__ == "__main__":
    unittest.main()
