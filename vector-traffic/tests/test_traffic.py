import unittest

from vector_traffic.probe import Probe
from vector_traffic.segments import RoadSegment
from vector_traffic.traffic import TrafficModel, TrafficSegment, estimate_traffic


def _sample():
    segments = [
        RoadSegment("seg-1", [(13.40, 52.52), (13.41, 52.52)], 50.0),
        RoadSegment("seg-2", [(13.40, 52.50), (13.41, 52.50)], 50.0),
        RoadSegment("seg-3", [(13.45, 52.53), (13.46, 52.53)], 50.0),
    ]
    probes = [
        Probe(13.4005, 52.5200, speed_kmh=45.0),
        Probe(13.4050, 52.5200, speed_kmh=40.0),
        Probe(13.4005, 52.5000, speed_kmh=10.0),
    ]
    return segments, probes


class TestTraffic(unittest.TestCase):
    def test_estimate_end_to_end(self):
        segments, probes = _sample()
        results = TrafficModel().estimate(segments, probes)
        self.assertEqual(len(results), 3)
        by_id = {r.segment_id: r for r in results}
        self.assertEqual(by_id["seg-1"].mean_speed_kmh, 42.5)
        self.assertEqual(by_id["seg-1"].congestion, "free")
        self.assertEqual(by_id["seg-2"].mean_speed_kmh, 10.0)
        self.assertEqual(by_id["seg-2"].congestion, "jammed")
        self.assertIsNone(by_id["seg-3"].mean_speed_kmh)
        self.assertEqual(by_id["seg-3"].congestion, "unknown")
        self.assertEqual(by_id["seg-3"].probe_count, 0)

    def test_to_geojson_structure(self):
        segments, probes = _sample()
        model = TrafficModel()
        results = model.estimate(segments, probes)
        gj = model.to_geojson(results)
        self.assertEqual(gj["type"], "FeatureCollection")
        self.assertEqual(len(gj["features"]), 3)
        for feat in gj["features"]:
            self.assertEqual(feat["geometry"]["type"], "LineString")
            props = feat["properties"]
            for key in ("segment_id", "free_flow_kmh", "mean_speed_kmh", "probe_count", "congestion"):
                self.assertIn(key, props)

    def test_estimate_geojson(self):
        segments, probes = _sample()
        gj = TrafficModel().estimate_geojson(segments, probes)
        self.assertEqual(gj["type"], "FeatureCollection")

    def test_estimate_traffic_function(self):
        segments, probes = _sample()
        results = estimate_traffic(segments, probes)
        self.assertIsInstance(results, list)
        self.assertIsInstance(results[0], TrafficSegment)
        self.assertEqual(len(results), 3)

    def test_max_match_radius_respected(self):
        seg = RoadSegment("S", [(0.0, 0.0), (0.001, 0.0)], 50.0)
        probe = Probe(0.0005, 0.0005, speed_kmh=30.0)
        wide = TrafficModel().estimate([seg], [probe], max_match_radius_m=100.0)
        tight = TrafficModel().estimate([seg], [probe], max_match_radius_m=30.0)
        self.assertEqual(wide[0].probe_count, 1)
        self.assertEqual(tight[0].probe_count, 0)
        self.assertEqual(tight[0].congestion, "unknown")

    def test_unknown_segment_present(self):
        seg_matched = RoadSegment("M", [(0.0, 0.0), (0.001, 0.0)], 50.0)
        seg_idle = RoadSegment("I", [(0.0, 0.1), (0.001, 0.1)], 50.0)
        probe = Probe(0.0005, 0.0, speed_kmh=30.0)
        results = TrafficModel().estimate([seg_matched, seg_idle], [probe])
        by_id = {r.segment_id: r for r in results}
        self.assertEqual(by_id["I"].congestion, "unknown")
        self.assertIsNone(by_id["I"].mean_speed_kmh)


if __name__ == "__main__":
    unittest.main()
