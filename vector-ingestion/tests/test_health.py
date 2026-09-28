import unittest

from vector_ingestion.__main__ import main
from vector_ingestion.health import health


class HealthTest(unittest.TestCase):
    def test_health_payload(self):
        payload = health()
        self.assertEqual(payload["status"], "ok")
        self.assertEqual(payload["service"], "vector-ingestion")

    def test_main_runs(self):
        self.assertIsNone(main())


if __name__ == "__main__":
    unittest.main()
