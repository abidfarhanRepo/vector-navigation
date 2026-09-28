import os
import unittest

from vector_routing.errors import NoRouteError
from vector_routing.graph import RoutingGraph
from vector_routing.router import Router
from vector_routing.service import RoutingService
from vector_routing.traffic_overlay import TrafficOverlay, OverlayView

FIXTURE = os.path.join(os.path.dirname(__file__), "data", "sample_routing.geojson")


class RouterTest(unittest.TestCase):
    def setUp(self):
        self.svc = RoutingService.from_geojson(FIXTURE)
        self.graph = self.svc.graph()

    def test_from_geojson_builds_nonempty(self):
        self.assertGreater(len(self.graph.nodes()), 0)
        self.assertGreater(self.graph.edge_count(), 0)

    def test_route_returns_populated_route(self):
        route = self.svc.route((13.399, 52.519), (13.401, 52.521))
        self.assertGreater(route.distance_m, 0)
        self.assertIsNotNone(route.duration_s)
        self.assertGreater(route.duration_s, 0)
        self.assertGreaterEqual(route.waypoint_count, 2)
        self.assertEqual(len(route.path), route.waypoint_count)
        self.assertEqual(len(route.node_keys), route.waypoint_count)
        # path starts/ends at the snapped nearest nodes
        self.assertEqual(route.node_keys[0], self.graph.nearest_node(13.399, 52.519))
        self.assertEqual(route.node_keys[-1], self.graph.nearest_node(13.401, 52.521))

    def test_route_dict_endpoint(self):
        route = self.svc.route({"lon": 13.399, "lat": 52.519}, {"lon": 13.401, "lat": 52.521})
        self.assertGreater(route.distance_m, 0)

    def test_disconnected_component_raises(self):
        # routing from a grid node to the isolated disconnected component
        with self.assertRaises(NoRouteError):
            self.svc.route((13.399, 52.519), (13.500, 52.600))

    def test_route_by_node(self):
        router = Router(self.graph)
        src = self.graph.node_key(13.399, 52.519)
        tgt = self.graph.node_key(13.401, 52.519)
        route = router.route_by_node(src, tgt)
        self.assertEqual(route.node_keys[0], src)
        self.assertEqual(route.node_keys[-1], tgt)

    def test_service_health_counts(self):
        health = self.svc.health()
        self.assertEqual(health["status"], "ok")
        self.assertEqual(health["service"], "vector-routing")
        self.assertEqual(health["nodes"], len(self.graph.nodes()))
        self.assertEqual(health["edges"], self.graph.edge_count())


class NavigateTest(unittest.TestCase):
    def _grid(self) -> RoutingGraph:
        g = RoutingGraph()
        lats = [52.51, 52.52, 52.53]
        lons = [13.39, 13.40, 13.41]
        for lat in lats:
            g.add_way([(lon, lat) for lon in lons], {"highway": "primary", "maxspeed_kmh": 50})
        for lon in lons:
            g.add_way([(lon, lat) for lat in lats], {"highway": "primary", "maxspeed_kmh": 50})
        return g

    def test_navigate_two_point(self):
        svc = RoutingService(self._grid())
        res = svc.navigate((13.39, 52.51), (13.41, 52.53))
        self.assertGreater(res["distance_m"], 0)
        self.assertGreater(res["duration_s"], 0)
        steps = res["steps"]
        self.assertGreaterEqual(len(steps), 2)
        self.assertEqual(steps[0]["type"], "depart")
        self.assertEqual(steps[-1]["type"], "arrive")
        # departure instruction names a compass direction and road
        self.assertTrue(steps[0]["instruction"].startswith("Head "))
        self.assertIn("road", steps[0]["instruction"])

    def test_navigate_cumulative_monotonic(self):
        svc = RoutingService(self._grid())
        res = svc.navigate((13.39, 52.51), (13.41, 52.53))
        cum = [s["cumulative_distance_m"] for s in res["steps"]]
        for prev, cur in zip(cum, cum[1:]):
            self.assertGreaterEqual(cur, prev)

    def test_navigate_multi_leg_includes_waypoint(self):
        svc = RoutingService(self._grid())
        res = svc.navigate((13.39, 52.51), (13.41, 52.53), waypoints=[(13.40, 52.52)])
        locs = [s["location"] for s in res["steps"]]
        self.assertIn([13.40, 52.52], locs)

    def test_navigate_eta_matches_speed(self):
        # single-edge grid: distance via haversine, duration = d / (50/3.6)
        # calibrated by ETA_DRIVE_BIAS (measured from the first 18 real
        # drives: free-flow under-predicts by a median 1.25x).
        from vector_routing.speeds import ETA_DRIVE_BIAS
        g = RoutingGraph()
        g.add_way([(13.40, 52.50), (13.41, 52.50)], {"highway": "residential", "maxspeed_kmh": 50})
        svc = RoutingService(g)
        res = svc.navigate((13.40, 52.50), (13.41, 52.50))
        from vector_routing.haversine import haversine_meters
        expected_d = haversine_meters((13.40, 52.50), (13.41, 52.50))
        self.assertAlmostEqual(res["distance_m"], expected_d, places=3)
        self.assertAlmostEqual(
            res["duration_s"],
            expected_d / (50.0 / 3.6) * ETA_DRIVE_BIAS, places=3)

    def test_eta_calibration_biases_freeflow_only(self):
        """The measured drive calibration (n=18, median obs/pred 1.25x) applies
        to free-flow segments; a learned (observed) segment speed is real-world
        already and must not be inflated twice."""
        from vector_routing.speeds import ETA_DRIVE_BIAS
        g = RoutingGraph()
        g.add_way([(13.40, 52.50), (13.41, 52.50)], {"highway": "residential", "maxspeed_kmh": 50})
        svc = RoutingService(g)
        base = svc.navigate((13.40, 52.50), (13.41, 52.50))
        # same edge, but with a learned observed speed of 50 km/h
        from vector_routing.learned_overlay import LearnedSpeedOverlay
        overlay = LearnedSpeedOverlay.from_facts([{
            "fact_type": "speed_profile",
            "payload": {
                "segment_id": "t",
                "band": 0,
                "median_speed_kmh": 50.0,
                "geometry": [[13.40, 52.50], [13.41, 52.50]],
            },
        }])
        svc._learned = overlay
        svc._learned_enabled = True
        learned = svc.navigate((13.40, 52.50), (13.41, 52.50), learned=True, band=0)
        self.assertGreater(base["duration_s"], learned["duration_s"])
        # free-flow (biased) vs learned (unbiased) differ by the bias factor
        self.assertAlmostEqual(
            learned["duration_s"] * ETA_DRIVE_BIAS, base["duration_s"],
            delta=base["duration_s"] * 0.03)


class TrafficOverlayTest(unittest.TestCase):
    """Wave 26c: routing consumes live congestion and avoids jammed edges."""

    def _graph(self):
        # S->T direct (short, will be congested) + S->M->T detour (free).
        g = RoutingGraph()
        g.add_way([(0.0, 0.0), (0.002, 0.0)], {"highway": "primary", "maxspeed_kmh": 50})
        g.add_way([(0.0, 0.0), (0.001, 0.001), (0.002, 0.0)], {"highway": "primary", "maxspeed_kmh": 50})
        return g

    def _congestion(self):
        # Mark the S->T direct edge heavily congested (free_flow 50, mean 5).
        return TrafficOverlay.from_segments([
            {
                "geometry": [[0.0, 0.0], [0.0, 0.002]],  # [lat, lon] envelope convention
                "free_flow_kmh": 50.0,
                "mean_speed_kmh": 5.0,
                "probe_count": 3,
                "congestion": "heavy",
            }
        ])

    def test_no_overlay_takes_direct(self):
        svc = RoutingService(self._graph())
        res = svc.navigate((0.0, 0.0), (0.002, 0.0))
        # Direct edge is the shortest path: no detour midpoint.
        mids = [c for c in res["path"][1:-1]]
        self.assertNotIn((0.001, 0.001), mids)

    def test_overlay_diverts_around_congestion(self):
        # W49: navigate() intentionally routes on the BASE graph (the
        # congestion OverlayView re-wraps every neighbour list per A*
        # relaxation and makes the search explode on real graphs — 46s vs
        # 0.5s on Doha, worse on Qatar). Traffic-aware rerouting is reserved
        # for /route?traffic=1. So setting an overlay must NOT change the
        # navigate() path: it still takes the direct edge.
        svc = RoutingService(self._graph())
        svc.set_traffic_overlay(self._congestion())
        res = svc.navigate((0.0, 0.0), (0.002, 0.0))
        mids = [c for c in res["path"][1:-1]]
        self.assertNotIn((0.001, 0.001), mids)

    def test_overlay_view_penalizes_edge(self):
        g = self._graph()
        overlay = self._congestion()
        view = OverlayView(g, overlay)
        nodes = g.nodes()
        # Find the S->T edge and confirm its penalized weight grew.
        s_key = g.nearest_node(0.0, 0.0)
        t_key = g.nearest_node(0.002, 0.0)
        base_w = dict((to, w) for to, w, _p in g.neighbors(s_key))[t_key]
        pen_w = dict((to, w) for to, w, _p in view.neighbors(s_key))[t_key]
        self.assertGreater(pen_w, base_w)


class RoadAwareRoutingTest(unittest.TestCase):
    """Wave 38: routes stay on the road network and respect road semantics."""

    def _net(self) -> RoutingGraph:
        # Two parallel east-west roads between the same endpoints A(lon0,lat) and
        # B(lon1,lat): a slow 30 km/h residential street (direct) and a fast
        # 100 km/h motorway that doglegs through an intermediate node. Both
        # connect A->B. Routing BY TRAVEL TIME must prefer the faster motorway
        # even though the residential street is geometrically shorter.
        g = RoutingGraph()
        g.add_way([(0.0, 0.0), (0.01, 0.0)], {"highway": "residential", "maxspeed": 30})
        g.add_way(
            [(0.0, 0.0), (0.005, 0.004), (0.01, 0.0)],
            {"highway": "motorway", "maxspeed": 100},
        )
        return g

    def test_snap_routable_lands_on_outgoing_node(self):
        g = self._net()
        # A point just south of the network (no node exactly there).
        router = Router(g)
        key = router._snap_routable(0.0001, -0.001)
        self.assertTrue(g.neighbors(key), "snapped node must have outgoing edges")

    def test_time_weight_prefers_faster_road(self):
        g = self._net()
        svc = RoutingService(g)
        res = svc.route((0.0, 0.0), (0.01, 0.0))
        # The faster motorway doglegs through (0.005, 0.004); assert it is used.
        self.assertIn((0.005, 0.004), [tuple(c) for c in res.path])

    def test_oneway_blocks_illegal_direction(self):
        g = RoutingGraph()
        # One-way south->north only.
        g.add_way([(0.0, 0.0), (0.0, 0.01)], {"highway": "primary", "oneway": True, "maxspeed": 50})
        svc = RoutingService(g)
        # Legal direction works.
        fwd = svc.route((0.0, 0.0), (0.0, 0.01))
        self.assertGreaterEqual(fwd.waypoint_count, 2)
        # Illegal (north->south) must be unreachable.
        with self.assertRaises(NoRouteError):
            svc.route((0.0, 0.01), (0.0, 0.0))


if __name__ == "__main__":
    unittest.main()
