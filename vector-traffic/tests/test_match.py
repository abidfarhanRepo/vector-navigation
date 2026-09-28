import unittest

from vector_traffic.errors import MapMatchError
from vector_traffic.probe import Probe
from vector_traffic.segments import RoadSegment
from vector_traffic.match import match_probe, match_probes


class TestMatch(unittest.TestCase):
    def setUp(self):
        self.seg_a = RoadSegment("A", [(0.0, 0.0), (0.001, 0.0)], 50.0)
        self.seg_b = RoadSegment("B", [(0.0, 0.002), (0.001, 0.002)], 50.0)

    def test_match_nearest(self):
        p = Probe(0.0005, 0.0, speed_kmh=30.0)
        r = match_probe(p, [self.seg_a, self.seg_b])
        self.assertEqual(r.segment_id, "A")

    def test_distance_correct(self):
        p = Probe(0.0005, 0.0, speed_kmh=30.0)
        r = match_probe(p, [self.seg_a])
        self.assertAlmostEqual(r.distance_m, 0.0, places=6)

    def test_tie_break_order(self):
        p = Probe(0.0005, 0.001, speed_kmh=30.0)
        r = match_probe(p, [self.seg_a, self.seg_b], max_match_radius_m=200.0)
        self.assertEqual(r.segment_id, "A")

    def test_radius_rejection(self):
        p = Probe(0.5, 1.0, speed_kmh=30.0)
        with self.assertRaises(MapMatchError):
            match_probe(p, [self.seg_a])

    def test_match_probes_tuple(self):
        near = Probe(0.0005, 0.0, speed_kmh=30.0)
        far = Probe(0.5, 1.0, speed_kmh=10.0)
        results, dropped = match_probes([near, far], [self.seg_a])
        self.assertEqual(len(results), 1)
        self.assertEqual(dropped, 1)

    def test_far_probe_dropped(self):
        near = Probe(0.0005, 0.0, speed_kmh=30.0)
        far = Probe(0.5, 1.0, speed_kmh=10.0)
        results, dropped = match_probes([far, near], [self.seg_a])
        self.assertEqual(dropped, 1)
        self.assertEqual(results[0].segment_id, "A")


if __name__ == "__main__":
    unittest.main()
