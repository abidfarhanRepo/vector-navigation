import unittest

from vector_traffic.health import health


class TestHealth(unittest.TestCase):
    def test_health_keys(self):
        h = health()
        self.assertIn("status", h)
        self.assertIn("service", h)

    def test_health_values(self):
        h = health()
        self.assertEqual(h["status"], "ok")
        self.assertEqual(h["service"], "vector-traffic")


if __name__ == "__main__":
    unittest.main()
