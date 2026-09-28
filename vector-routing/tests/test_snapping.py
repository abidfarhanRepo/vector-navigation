"""Endpoint snapping: observable, and bounded (V1 validation §2).

Two defects are pinned.

**Snapping was invisible.** The API reported a route but never where the
endpoints actually landed. A tap inside Hamad International's terminal snaps
~136 m to the nearest service road, which is correct; a tap that snaps 700 m is
a coverage gap. Nothing in the response distinguished them, so "the route starts
somewhere else" looked like a routing bug.

**Snapping was unbounded.** `_snap_routable` tried a 800 m radius for a
*departable* node and then fell through to the global nearest node with **no
limit at all**. A point in the Gulf, in the desert, or simply outside the baked
region produced a confident route between two other places. Now it raises
`EndpointTooFarError` past `Router.MAX_SNAP_M`, and the HTTP layer answers 422
with the distance and the limit.
"""

import math
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src"))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "vendor"))

from vector_geo.graph import RoutingGraph  # noqa: E402
from vector_routing.errors import EndpointTooFarError  # noqa: E402
from vector_routing.router import Router  # noqa: E402
from vector_routing.service import RoutingService  # noqa: E402

LAT, LON = 25.2854, 51.5310
MPD = 111_320.0
KX = math.cos(math.radians(LAT)) * MPD


def pt(east_m, north_m=0.0):
    return (LON + east_m / KX, LAT + north_m / MPD)


def small_graph():
    g = RoutingGraph()
    g.add_way([pt(i * 100.0) for i in range(11)], {"highway": "residential", "maxspeed": 50})
    return g


class SnapReportingTest(unittest.TestCase):
    def test_navigate_reports_where_each_endpoint_landed(self):
        svc = RoutingService(small_graph())
        res = svc.navigate(pt(0, 30), pt(1000, 30))
        self.assertIn("snap", res)
        self.assertEqual(len(res["snap"]), 2, "one entry per endpoint")
        for s in res["snap"]:
            self.assertIn("requested", s)
            self.assertIn("snapped", s)
            self.assertIn("distance_m", s)
            self.assertTrue(s["routable"])

    def test_the_reported_distance_is_the_real_distance(self):
        svc = RoutingService(small_graph())
        # 30 m north of the road: the snap must be ~30 m, not 0 and not 300.
        res = svc.navigate(pt(0, 30), pt(1000, 30))
        self.assertAlmostEqual(res["snap"][0]["distance_m"], 30.0, delta=3.0)

    def test_snap_max_m_is_the_worst_of_the_endpoints(self):
        svc = RoutingService(small_graph())
        res = svc.navigate(pt(0, 5), pt(1000, 120))
        self.assertAlmostEqual(res["snap_max_m"], max(s["distance_m"] for s in res["snap"]))
        self.assertGreater(res["snap_max_m"], 100)

    def test_an_on_road_point_snaps_to_almost_nothing(self):
        svc = RoutingService(small_graph())
        res = svc.navigate(pt(0), pt(1000))
        self.assertLess(res["snap_max_m"], 1.0)

    def test_waypoints_are_reported_too(self):
        svc = RoutingService(small_graph())
        res = svc.navigate(pt(0), pt(1000), waypoints=[pt(500, 40)])
        self.assertEqual(len(res["snap"]), 3, "origin + via + destination")


class SnapBoundsTest(unittest.TestCase):
    def test_a_point_far_outside_the_region_is_refused_not_routed(self):
        """The defect: this used to return a confident route between two other
        places."""
        svc = RoutingService(small_graph())
        # ~100 km away — nowhere near the baked network.
        with self.assertRaises(EndpointTooFarError):
            svc.navigate(pt(0), (LON + 1.0, LAT + 1.0))

    def test_the_error_says_how_far_and_what_the_limit_is(self):
        svc = RoutingService(small_graph())
        try:
            svc.navigate(pt(0), (LON + 1.0, LAT + 1.0))
            self.fail("expected EndpointTooFarError")
        except EndpointTooFarError as e:
            self.assertGreater(e.distance_m, Router.MAX_SNAP_M)
            self.assertEqual(e.limit_m, Router.MAX_SNAP_M)
            # The message must be actionable on its own.
            self.assertIn("outside the routable area", str(e))
            self.assertIn("limit", str(e))

    def test_a_point_within_the_bound_still_routes(self):
        # 500 m off-road is unusual but legitimate — a car park, an apron.
        svc = RoutingService(small_graph())
        res = svc.navigate(pt(0, 500), pt(1000))
        self.assertGreater(res["distance_m"], 0)
        self.assertGreater(res["snap_max_m"], 400)

    def test_the_bound_is_a_documented_constant_not_a_magic_number(self):
        self.assertTrue(hasattr(Router, "MAX_SNAP_M"))
        self.assertTrue(500 <= Router.MAX_SNAP_M <= 5000)

    def test_out_of_area_is_distinguishable_from_no_route(self):
        # Different failure, different class: one is a coverage problem, the
        # other a connectivity problem, and they send you to different places.
        from vector_geo.errors import NoRouteError
        self.assertFalse(issubclass(EndpointTooFarError, NoRouteError))


if __name__ == "__main__":
    unittest.main()
