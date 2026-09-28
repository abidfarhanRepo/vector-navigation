import unittest

from vector_tile_gen.tiles import lonlat_to_tile


class TileTest(unittest.TestCase):
    def test_zoom_zero(self):
        self.assertEqual(lonlat_to_tile(0, 0.0, 0.0), (0, 0))
        self.assertEqual(lonlat_to_tile(0, 180.0, 85.0), (0, 0))

    def test_zoom_one_center(self):
        self.assertEqual(lonlat_to_tile(1, 0.0, 0.0), (1, 1))

    def test_clamped(self):
        self.assertEqual(lonlat_to_tile(2, 200.0, 100.0), (3, 3))


if __name__ == "__main__":
    unittest.main()
