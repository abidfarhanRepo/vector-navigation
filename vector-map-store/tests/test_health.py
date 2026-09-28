import unittest

from vector_map_store.__main__ import main
from vector_map_store.health import health


class HealthTest(unittest.TestCase):
    def test_health_payload(self):
        payload = health()
        self.assertEqual(payload["status"], "ok")
        self.assertEqual(payload["service"], "vector-map-store")

    def test_main_runs(self):
        self.assertIsNone(main())


if __name__ == "__main__":
    unittest.main()
