import unittest

from vector_tile_gen.__main__ import main
from vector_tile_gen.health import health


class HealthTest(unittest.TestCase):
    def test_health_payload(self):
        payload = health()
        self.assertEqual(payload["status"], "ok")
        self.assertEqual(payload["service"], "vector-tile-gen")

    def test_main_runs(self):
        self.assertIsNone(main())


if __name__ == "__main__":
    unittest.main()
