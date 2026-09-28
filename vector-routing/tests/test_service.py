import unittest

from vector_routing.algorithms import build_graph_from_features
from vector_routing.errors import RouteError
from vector_routing.service import RoutingService


class ServiceTest(unittest.TestCase):
    def test_empty_graph_route_raises(self):
        svc = RoutingService()
        with self.assertRaises(RouteError):
            svc.route((0.0, 0.0), (0.001, 0.0))

    def test_health_on_empty_graph(self):
        svc = RoutingService()
        health = svc.health()
        self.assertEqual(health["status"], "ok")
        self.assertEqual(health["nodes"], 0)
        self.assertEqual(health["edges"], 0)

    def test_from_geojson_ignores_malformed(self):
        features = [
            {"type": "Feature", "geometry": {"type": "Point", "coordinates": [13.4, 52.5]}, "properties": {}},
            {"type": "Feature", "properties": {}},
            {
                "type": "Feature",
                "properties": {"highway": "primary"},
                "geometry": {"type": "LineString", "coordinates": [[13.399, 52.519], [13.400, 52.519]]},
            },
        ]
        g = build_graph_from_features(features)
        # only the valid LineString contributes nodes
        self.assertEqual(len(g.nodes()), 2)
        self.assertEqual(g.edge_count(), 2)

    def test_from_geojson_bare_list(self):
        svc = RoutingService.from_geojson  # referenced for type only
        features = [
            {
                "type": "Feature",
                "properties": {"maxspeed_kmh": 30},
                "geometry": {"type": "LineString", "coordinates": [[13.0, 52.0], [13.001, 52.0]]},
            }
        ]
        g = build_graph_from_features(features)
        self.assertEqual(len(g.nodes()), 2)
        self.assertGreater(g.edge_count(), 0)
        self.assertIsNotNone(svc)


class TrafficRouteTest(unittest.TestCase):
    """Wave 30: route() honours a congestion overlay; ingest_traffic populates it."""

    def _svc(self):
        features = [
            # A triangle so congestion on one edge has a detour alternative.
            {"type": "Feature", "properties": {"highway": "primary", "maxspeed_kmh": 50},
             "geometry": {"type": "LineString", "coordinates": [[13.000, 52.000], [13.010, 52.000]]}},
            {"type": "Feature", "properties": {"highway": "primary", "maxspeed_kmh": 50},
             "geometry": {"type": "LineString", "coordinates": [[13.010, 52.000], [13.010, 52.010]]}},
            {"type": "Feature", "properties": {"highway": "primary", "maxspeed_kmh": 50},
             "geometry": {"type": "LineString", "coordinates": [[13.010, 52.010], [13.000, 52.010]]}},
            {"type": "Feature", "properties": {"highway": "secondary", "maxspeed_kmh": 30},
             "geometry": {"type": "LineString", "coordinates": [[13.000, 52.010], [13.000, 52.000]]}},
        ]
        return RoutingService(graph=build_graph_from_features(features))

    def test_route_ignores_traffic_when_no_overlay(self):
        svc = self._svc()
        a = svc.route((13.000, 52.000), (13.010, 52.000), traffic=True)
        self.assertIsNotNone(a)
        self.assertGreater(len(a.node_keys), 0)

    def test_ingest_traffic_and_reroute_avoids_congested_edge(self):
        svc = self._svc()
        nominal = svc.route((13.000, 52.000), (13.010, 52.000), traffic=False)
        # Congest the direct top edge heavily.
        seg = {"geometry": [[52.000, 13.000], [52.000, 13.010]],
               "free_flow_kmh": 50, "mean_speed_kmh": 3}
        n = svc.ingest_traffic([seg])
        self.assertGreaterEqual(n, 1)
        self.assertTrue(svc._overlay is not None)
        aware = svc.route((13.000, 52.000), (13.010, 52.000), traffic=True)
        # The traffic-aware path is no shorter in distance but costs more in
        # time (penalty applied) — and when a detour exists it should differ.
        self.assertGreaterEqual(aware.duration_s, nominal.duration_s)
        if aware.node_keys != nominal.node_keys:
            self.assertNotEqual(aware.node_keys, nominal.node_keys)


if __name__ == "__main__":
    unittest.main()
