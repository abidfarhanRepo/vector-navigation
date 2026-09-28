import unittest

from vector_traffic.segments import RoadSegment
from vector_traffic.match import MatchResult
from vector_traffic.aggregate import congestion_level, aggregate


class TestAggregate(unittest.TestCase):
    def test_free(self):
        self.assertEqual(congestion_level(45.0, 50.0), "free")

    def test_light(self):
        self.assertEqual(congestion_level(35.0, 50.0), "light")

    def test_moderate(self):
        self.assertEqual(congestion_level(25.0, 50.0), "moderate")

    def test_heavy(self):
        self.assertEqual(congestion_level(15.0, 50.0), "heavy")

    def test_jammed(self):
        self.assertEqual(congestion_level(10.0, 50.0), "jammed")

    def test_none_unknown(self):
        self.assertEqual(congestion_level(None, 50.0), "unknown")

    def test_mean_speed(self):
        segs = [RoadSegment("A", [(0.0, 0.0), (0.001, 0.0)], 50.0)]
        matches = [
            MatchResult("A", 1.0, (0.0, 0.0), 40.0),
            MatchResult("A", 1.0, (0.001, 0.0), 60.0),
        ]
        out = aggregate(matches, segs)
        self.assertIn("A", out)
        self.assertEqual(out["A"]["mean_speed_kmh"], 50.0)
        self.assertEqual(out["A"]["probe_count"], 2)
        self.assertEqual(out["A"]["congestion"], "free")

    def test_no_matches_excluded(self):
        segs = [
            RoadSegment("A", [(0.0, 0.0), (0.001, 0.0)], 50.0),
            RoadSegment("B", [(0.0, 0.001), (0.001, 0.001)], 50.0),
        ]
        matches = [MatchResult("A", 1.0, (0.0, 0.0), 40.0)]
        out = aggregate(matches, segs)
        self.assertEqual(set(out.keys()), {"A"})

    def test_groups_by_segment(self):
        segs = [
            RoadSegment("A", [(0.0, 0.0), (0.001, 0.0)], 50.0),
            RoadSegment("B", [(0.0, 0.001), (0.001, 0.001)], 50.0),
        ]
        matches = [
            MatchResult("A", 1.0, (0.0, 0.0), 40.0),
            MatchResult("A", 1.0, (0.001, 0.0), 60.0),
            MatchResult("B", 1.0, (0.0, 0.001), 10.0),
        ]
        out = aggregate(matches, segs)
        self.assertEqual(out["A"]["probe_count"], 2)
        self.assertEqual(out["B"]["probe_count"], 1)
        self.assertEqual(out["A"]["mean_speed_kmh"], 50.0)
        self.assertEqual(out["B"]["congestion"], "jammed")


if __name__ == "__main__":
    unittest.main()
