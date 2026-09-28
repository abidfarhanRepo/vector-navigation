import unittest

from vector_routing.__main__ import main
from vector_routing.health import health


class HealthTest(unittest.TestCase):
    def test_health_payload(self):
        payload = health()
        self.assertEqual(payload["status"], "ok")
        self.assertEqual(payload["service"], "vector-routing")

    def test_main_runs(self):
        self.assertIsNone(main())


if __name__ == "__main__":
    unittest.main()
