import unittest

from vector_traffic.errors import InputError
from vector_traffic.probe import Probe, normalize_probe, load_probes


class TestProbe(unittest.TestCase):
    def test_from_lon_lat(self):
        p = normalize_probe({"lon": 1.0, "lat": 2.0, "speed": 30})
        self.assertEqual(p.lon, 1.0)
        self.assertEqual(p.lat, 2.0)
        self.assertEqual(p.speed_kmh, 30.0)

    def test_from_coordinates(self):
        p = normalize_probe({"coordinates": [1.0, 2.0], "speed_kmh": 40})
        self.assertEqual(p.lon, 1.0)
        self.assertEqual(p.lat, 2.0)
        self.assertEqual(p.speed_kmh, 40.0)

    def test_from_geometry_coords(self):
        p = normalize_probe(
            {"geometry": {"type": "Point", "coordinates": [1.0, 2.0]}, "speed_kmh": 40}
        )
        self.assertEqual(p.lon, 1.0)
        self.assertEqual(p.lat, 2.0)
        self.assertEqual(p.speed_kmh, 40.0)

    def test_missing_coords_raises(self):
        with self.assertRaises(InputError):
            normalize_probe({"foo": 1})

    def test_speed_parse(self):
        p = normalize_probe({"lon": 1.0, "lat": 2.0, "speed": "35"})
        self.assertEqual(p.speed_kmh, 35.0)

    def test_load_probes(self):
        gj = {
            "type": "FeatureCollection",
            "features": [
                {
                    "type": "Feature",
                    "properties": {"speed_kmh": 45.0},
                    "geometry": {"type": "Point", "coordinates": [13.4, 52.52]},
                },
                {
                    "type": "Feature",
                    "properties": {"speed_kmh": 50.0},
                    "geometry": {"type": "Point", "coordinates": [13.41, 52.52]},
                },
            ],
        }
        probes = load_probes(gj)
        self.assertEqual(len(probes), 2)
        self.assertEqual(probes[0].speed_kmh, 45.0)
        self.assertEqual(probes[1].speed_kmh, 50.0)
        self.assertEqual(probes[0].lon, 13.4)


if __name__ == "__main__":
    unittest.main()
