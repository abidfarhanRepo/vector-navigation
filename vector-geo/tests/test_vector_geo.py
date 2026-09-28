#!/usr/bin/env python3
"""Smoke tests for the vector_geo package (import + basic behavior)."""

import unittest

from vector_geo import (
    haversine_meters,
    haversine_meters_coord,
    RoutingGraph,
    dijkstra,
    astar,
    build_graph_from_features,
    Route,
    RouteError,
    NoRouteError,
)


class TestVectorGeo(unittest.TestCase):
    def test_haversine_zero(self):
        self.assertEqual(haversine_meters((0.0, 0.0), (0.0, 0.0)), 0.0)

    def test_haversine_known(self):
        # ~111 km per degree of latitude.
        d = haversine_meters((0.0, 0.0), (0.0, 1.0))
        self.assertTrue(110000 < d < 112000)

    def test_graph_roundtrip(self):
        g = RoutingGraph()
        g.add_way([(0.0, 0.0), (0.0, 1.0)], {})
        self.assertEqual(g.edge_count(), 2)  # undirected -> both directions
        g2 = RoutingGraph.from_dict(g.to_dict())
        self.assertEqual(g2.edge_count(), 2)

    def test_errors(self):
        self.assertTrue(issubclass(NoRouteError, RouteError))
        with self.assertRaises(NoRouteError):
            raise NoRouteError("a", "b")


if __name__ == "__main__":
    unittest.main()
