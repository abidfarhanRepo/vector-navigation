"""Tests for Wave 30 Doha-graph traffic generation."""

import json
import os
import tempfile
import unittest

from vector_traffic.loaders import build_segments_from_graph, build_graph_probes
from vector_traffic.serve import TrafficService

# A tiny road network (3 edges, one will be jammed at index 0 % 13 == 0).
_SAMPLE_GRAPH = {
    "type": "FeatureCollection",
    "features": [
        {"type": "Feature", "properties": {"highway": "motorway", "maxspeed": 100},
         "geometry": {"type": "LineString", "coordinates": [[51.51, 25.30], [51.52, 25.30]]}},
        {"type": "Feature", "properties": {"highway": "primary", "maxspeed": 60},
         "geometry": {"type": "LineString", "coordinates": [[51.52, 25.30], [51.52, 25.31]]}},
        {"type": "Feature", "properties": {"highway": "residential"},
         "geometry": {"type": "LineString", "coordinates": [[51.52, 25.31], [51.51, 25.31]]}},
    ],
}


class GraphTrafficTest(unittest.TestCase):
    def test_build_segments_from_graph_uses_maxspeed(self):
        segs = build_segments_from_graph(_SAMPLE_GRAPH)
        self.assertEqual(len(segs), 3)
        self.assertEqual(segs[0].free_flow_kmh, 100.0)
        self.assertEqual(segs[1].free_flow_kmh, 60.0)
        self.assertEqual(segs[2].free_flow_kmh, 50.0)  # default

    def test_build_segments_cap_stops_early(self):
        g = {"type": "FeatureCollection", "features": [
            {"type": "Feature", "properties": {}, "geometry": {"type": "LineString", "coordinates": [[0, 0], [0, 1]]}}
            for _ in range(5000)
        ]}
        segs = build_segments_from_graph(g, cap=10)
        self.assertEqual(len(segs), 10)

    def test_build_graph_probes_jams_subset(self):
        segs = build_segments_from_graph(_SAMPLE_GRAPH)
        probes = build_graph_probes(segs)
        # index 0 is jammed (slow), others near free-flow
        self.assertLess(probes[0].speed_kmh, segs[0].free_flow_kmh * 0.5)
        self.assertGreater(probes[1].speed_kmh, segs[1].free_flow_kmh * 0.5)

    def test_traffic_from_graph_emits_doha_like_segments(self):
        with tempfile.NamedTemporaryFile("w", suffix=".geojson", delete=False) as fh:
            json.dump(_SAMPLE_GRAPH, fh)
            path = fh.name
        try:
            svc = TrafficService(graph_path=path)
            fc = svc.traffic_from_graph()
            feats = fc["features"]
            self.assertEqual(len(feats), 3)
            props = {f["properties"]["segment_id"]: f["properties"] for f in feats}
            # First edge is jammed with a low mean speed.
            self.assertEqual(props["doha-0"]["congestion"], "jammed")
            self.assertLess(props["doha-0"]["mean_speed_kmh"], props["doha-0"]["free_flow_kmh"])
            # Coordinates are the real graph coords (not Berlin).
            self.assertAlmostEqual(feats[0]["geometry"]["coordinates"][0][0], 51.51, places=4)
        finally:
            os.unlink(path)

    def test_traffic_from_graph_missing_file_raises(self):
        svc = TrafficService(graph_path="/nonexistent/path.geojson")
        with self.assertRaises(FileNotFoundError):
            svc.traffic_from_graph()


if __name__ == "__main__":
    unittest.main()
