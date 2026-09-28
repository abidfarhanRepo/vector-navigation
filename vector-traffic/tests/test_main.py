import unittest

from vector_traffic.__main__ import main


class TestMain(unittest.TestCase):
    def test_main_keys(self):
        d = main()
        for key in ("status", "service", "segments", "with_traffic", "unknown", "total_probe_count", "matched"):
            self.assertIn(key, d)

    def test_main_values(self):
        d = main()
        self.assertEqual(d["status"], "ok")
        self.assertEqual(d["service"], "vector-traffic")
        self.assertEqual(d["segments"], 3)
        self.assertEqual(d["total_probe_count"], 4)
        self.assertEqual(d["matched"], 3)
        self.assertEqual(d["with_traffic"], 2)
        self.assertEqual(d["unknown"], 1)


if __name__ == "__main__":
    unittest.main()
