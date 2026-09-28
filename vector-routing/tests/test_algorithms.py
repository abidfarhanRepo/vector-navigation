import json
import os
import unittest

from vector_routing.algorithms import (
    Route,
    astar,
    build_graph_from_features,
    dijkstra,
)
from vector_routing.errors import NoRouteError
from vector_routing.graph import RoutingGraph
from vector_routing.haversine import haversine_meters

FIXTURE = os.path.join(os.path.dirname(__file__), "data", "sample_routing.geojson")


def load_fixture_graph():
    with open(FIXTURE, "r", encoding="utf-8") as fh:
        data = json.load(fh)
    return build_graph_from_features(data)


class AlgorithmsTest(unittest.TestCase):
    def test_dijkstra_endpoints_and_distance(self):
        g = load_fixture_graph()
        src = g.node_key(13.399, 52.519)
        tgt = g.node_key(13.401, 52.519)
        dist, path = dijkstra(g, src, tgt)
        self.assertEqual(path[0], src)
        self.assertEqual(path[-1], tgt)
        a = (13.399, 52.519)
        b = (13.400, 52.519)
        c = (13.401, 52.519)
        expected = haversine_meters(a, b) + haversine_meters(b, c)
        self.assertAlmostEqual(dist, expected, places=3)

    def test_dijkstra_equals_astar(self):
        g = load_fixture_graph()
        src = g.node_key(13.399, 52.519)
        tgt = g.node_key(13.401, 52.521)
        d_d, p_d = dijkstra(g, src, tgt)
        d_a, p_a = astar(g, src, tgt)
        self.assertAlmostEqual(d_d, d_a, places=6)
        self.assertEqual(p_d[0], p_a[0])
        self.assertEqual(p_d[-1], p_a[-1])

    def test_unreachable_raises(self):
        g = load_fixture_graph()
        grid = g.node_key(13.399, 52.519)
        isolated = g.node_key(13.500, 52.600)
        with self.assertRaises(NoRouteError):
            dijkstra(g, grid, isolated)
        with self.assertRaises(NoRouteError):
            astar(g, grid, isolated)

    def test_dijkstra_chooses_shorter_path(self):
        g = RoutingGraph()
        s = (0.0, 0.0)
        t = (0.0, 2.0)
        a = (0.001, 1.0)
        b = (0.010, 1.0)
        g.add_way([s, a], {})
        g.add_way([a, t], {})
        g.add_way([s, b], {})
        g.add_way([b, t], {})
        _dist, path = dijkstra(g, g.node_key(*s), g.node_key(*t))
        self.assertIn(g.node_key(*a), path)
        self.assertNotIn(g.node_key(*b), path)

    def test_route_dataclass(self):
        r = Route(distance_m=100.0, duration_s=5.0, path=[(0.0, 0.0)], node_keys=["k"], waypoint_count=1)
        self.assertEqual(r.distance_m, 100.0)
        self.assertEqual(r.waypoint_count, 1)


if __name__ == "__main__":
    unittest.main()
