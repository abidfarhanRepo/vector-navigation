import unittest

from vector_ingestion.normalize import normalize_lonlat


class NormalizeTest(unittest.TestCase):
    def test_wrap_positive(self):
        self.assertEqual(normalize_lonlat(190.0, 0.0), (-170.0, 0.0))

    def test_wrap_negative(self):
        self.assertEqual(normalize_lonlat(-190.0, 45.0), (170.0, 45.0))

    def test_identity(self):
        self.assertEqual(normalize_lonlat(12.5, -33.0), (12.5, -33.0))

    def test_invalid_latitude(self):
        with self.assertRaises(ValueError):
            normalize_lonlat(0.0, 95.0)


if __name__ == "__main__":
    unittest.main()
