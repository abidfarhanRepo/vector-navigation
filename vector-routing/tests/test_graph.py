import unittest

from vector_routing.graph import RoutingGraph
from vector_routing.haversine import haversine_meters


class GraphTest(unittest.TestCase):
    def test_add_way_builds_nodes_and_edges(self):
        g = RoutingGraph()
        g.add_way([(0.0, 0.0), (0.001, 0.0), (0.002, 0.0)], {})
        self.assertEqual(len(g.nodes()), 3)
        # undirected: 2 segments * 2 directions = 4 directed edges
        self.assertEqual(g.edge_count(), 4)

    def test_undirected_adds_both_directions(self):
        g = RoutingGraph()
        g.add_way([(0.0, 0.0), (0.001, 0.0)], {})
        a = g.node_key(0.0, 0.0)
        b = g.node_key(0.001, 0.0)
        targets = {to for to, _w, _p in g.neighbors(a)}
        self.assertIn(b, targets)
        targets_b = {to for to, _w, _p in g.neighbors(b)}
        self.assertIn(a, targets_b)

    def test_oneway_adds_single_direction(self):
        g = RoutingGraph()
        g.add_way([(0.0, 0.0), (0.001, 0.0)], {"oneway": "yes"})
        a = g.node_key(0.0, 0.0)
        b = g.node_key(0.001, 0.0)
        b_targets = {to for to, _w, _p in g.neighbors(b)}
        self.assertNotIn(a, b_targets)
        a_targets = {to for to, _w, _p in g.neighbors(a)}
        self.assertIn(b, a_targets)

    def test_oneway_true_and_one(self):
        g = RoutingGraph()
        g.add_way([(0.0, 0.0), (0.001, 0.0)], {"oneway": True})
        a = g.node_key(0.0, 0.0)
        b = g.node_key(0.001, 0.0)
        self.assertEqual(len(g.neighbors(b)), 0)
        g2 = RoutingGraph()
        g2.add_way([(0.0, 0.0), (0.001, 0.0)], {"oneway": "1"})
        self.assertEqual(len(g2.neighbors(g2.node_key(0.001, 0.0))), 0)

    def test_nearest_node(self):
        g = RoutingGraph()
        g.add_way([(0.0, 0.0), (0.001, 0.0), (0.002, 0.0)], {})
        self.assertEqual(g.nearest_node(0.0001, 0.0), g.node_key(0.0, 0.0))
        self.assertEqual(g.nearest_node(0.0019, 0.0), g.node_key(0.002, 0.0))

    def test_parallel_edge_keeps_shorter(self):
        g = RoutingGraph()
        g.add_way([(0.0, 0.0), (0.001, 0.0)], {"maxspeed_kmh": 50})
        g.add_way([(0.0, 0.0), (0.001, 0.0)], {"maxspeed_kmh": 30})
        a = g.node_key(0.0, 0.0)
        b = g.node_key(0.001, 0.0)
        edges = [e for e in g.neighbors(a) if e[0] == b]
        self.assertEqual(len(edges), 1)
        expected = haversine_meters((0.0, 0.0), (0.001, 0.0))
        self.assertAlmostEqual(edges[0][1], expected, places=6)

    def test_edge_props_carry_length_and_speed(self):
        g = RoutingGraph()
        g.add_way([(0.0, 0.0), (0.001, 0.0)], {"maxspeed_kmh": 50, "highway": "primary"})
        a = g.node_key(0.0, 0.0)
        e = g.neighbors(a)[0]
        self.assertEqual(e[2]["highway"], "primary")
        self.assertEqual(e[2]["maxspeed_kmh"], 50)
        self.assertIn("length_m", e[2])

    def test_to_from_dict_roundtrip(self):
        g = RoutingGraph()
        g.add_way([(0.0, 0.0), (0.001, 0.0), (0.002, 0.0)], {"maxspeed_kmh": 50})
        g2 = RoutingGraph.from_dict(g.to_dict())
        self.assertEqual(g.nodes(), g2.nodes())
        self.assertEqual(g.edge_count(), g2.edge_count())
        self.assertEqual(g.edges(), g2.edges())


if __name__ == "__main__":
    unittest.main()
