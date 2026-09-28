"""One-way handling in the routing graph.

Two things are pinned here.

**`oneway=-1` was not recognised.** OSM uses it for a way that is one-way
AGAINST its digitisation order. ``add_way`` matched only ("yes", "1", True), so a
`-1` way fell through to the bidirectional branch and got edges in BOTH
directions — the router would send a car the wrong way up it, and nothing in the
suite could see it.

**One-way restrictions are real and they legitimately lengthen routes.** In the
Msheireb grid a 650 m hop routes 2.05 km; ignoring one-ways it is 0.90 km. That
is the road network, not a defect, and the test below records the distinction so
nobody "fixes" it by loosening a detour threshold.
"""

import math
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src"))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "vendor"))

from vector_geo.graph import RoutingGraph  # noqa: E402
from vector_routing.service import RoutingService  # noqa: E402
from vector_geo.errors import NoRouteError  # noqa: E402

LAT = 25.2854
LON = 51.5310
M_PER_DEG = 111_320.0
KX = math.cos(math.radians(LAT)) * M_PER_DEG


def east(n, spacing=50.0, lat=LAT, lon0=LON):
    return [(lon0 + (i * spacing) / KX, lat) for i in range(n)]


class OnewayDirectionTest(unittest.TestCase):
    def test_a_plain_way_is_bidirectional(self):
        g = RoutingGraph()
        c = east(3)
        g.add_way(c, {"highway": "residential"})
        self.assertTrue(any(to == g.node_key(*c[1]) for to, _, _ in g.neighbors(g.node_key(*c[0]))))
        self.assertTrue(any(to == g.node_key(*c[0]) for to, _, _ in g.neighbors(g.node_key(*c[1]))))

    def test_oneway_yes_only_goes_forward(self):
        g = RoutingGraph()
        c = east(3)
        g.add_way(c, {"highway": "residential", "oneway": "yes"})
        fwd = [to for to, _, _ in g.neighbors(g.node_key(*c[0]))]
        back = [to for to, _, _ in g.neighbors(g.node_key(*c[1]))]
        self.assertIn(g.node_key(*c[1]), fwd)
        self.assertNotIn(g.node_key(*c[0]), back)

    def test_oneway_minus_one_goes_BACKWARD(self):
        """The bug: `-1` was unrecognised, so the way became bidirectional."""
        g = RoutingGraph()
        c = east(3)
        g.add_way(c, {"highway": "residential", "oneway": "-1"})
        first, second = g.node_key(*c[0]), g.node_key(*c[1])
        self.assertNotIn(second, [to for to, _, _ in g.neighbors(first)],
                         "a -1 way must NOT be traversable in digitisation order")
        self.assertIn(first, [to for to, _, _ in g.neighbors(second)],
                      "a -1 way must be traversable against digitisation order")

    def test_oneway_minus_one_is_not_silently_bidirectional(self):
        g = RoutingGraph()
        c = east(4)
        g.add_way(c, {"highway": "residential", "oneway": "-1"})
        svc = RoutingService(g)
        # Forward is now illegal; the graph offers no path.
        with self.assertRaises(NoRouteError):
            svc.navigate(c[0], c[-1])
        # Backward works.
        res = svc.navigate(c[-1], c[0])
        self.assertGreater(res["distance_m"], 0)

    def test_reverse_is_accepted_as_a_synonym(self):
        g = RoutingGraph()
        c = east(3)
        g.add_way(c, {"highway": "residential", "oneway": "reverse"})
        self.assertNotIn(g.node_key(*c[1]), [to for to, _, _ in g.neighbors(g.node_key(*c[0]))])

    def test_oneway_no_is_bidirectional(self):
        g = RoutingGraph()
        c = east(3)
        g.add_way(c, {"highway": "residential", "oneway": "no"})
        self.assertIn(g.node_key(*c[0]), [to for to, _, _ in g.neighbors(g.node_key(*c[1]))])

    def test_time_dependent_values_are_treated_as_bidirectional(self):
        # `reversible`/`alternating` need a schedule we do not have. Treating
        # them as one-way in an arbitrary direction would be a guess.
        for v in ("reversible", "alternating"):
            g = RoutingGraph()
            c = east(3)
            g.add_way(c, {"highway": "residential", "oneway": v})
            self.assertIn(g.node_key(*c[0]),
                          [to for to, _, _ in g.neighbors(g.node_key(*c[1]))], v)

    def test_case_and_whitespace_are_tolerated(self):
        g = RoutingGraph()
        c = east(3)
        g.add_way(c, {"highway": "residential", "oneway": " YES "})
        self.assertNotIn(g.node_key(*c[0]), [to for to, _, _ in g.neighbors(g.node_key(*c[1]))])


class OnewayForcesLegitimateDetourTest(unittest.TestCase):
    """A one-way grid makes short trips long. That is the road network."""

    def _grid(self, oneway):
        """Two parallel east-west streets joined at both ends.

        With both east-west streets one-way eastbound, going from a point on the
        upper street back to a point slightly west of it requires a full loop.
        """
        g = RoutingGraph()
        north = east(6, spacing=100.0, lat=LAT)
        south = east(6, spacing=100.0, lat=LAT - 100.0 / M_PER_DEG)
        props = {"highway": "residential"}
        if oneway:
            props = {"highway": "residential", "oneway": "yes"}
        g.add_way(north, dict(props))
        # A real one-way PAIR alternates direction: eastbound on one street,
        # westbound on the other. Making both eastbound leaves no return leg and
        # the grid is simply unroutable -- which is what the first version of
        # this fixture did, and it failed with NoRouteError rather than a loop.
        g.add_way(list(reversed(south)), dict(props))
        # Connectors are always two-way.
        g.add_way([north[0], south[0]], {"highway": "residential"})
        g.add_way([north[-1], south[-1]], {"highway": "residential"})
        return g, north

    def test_ignoring_oneways_gives_the_short_answer(self):
        g, north = self._grid(oneway=False)
        res = RoutingService(g).navigate(north[4], north[1])
        self.assertLess(res["distance_m"], 400, "two-way: should be a direct 300 m")

    def test_respecting_oneways_forces_the_loop(self):
        g, north = self._grid(oneway=True)
        res = RoutingService(g).navigate(north[4], north[1])
        # Must go east to the end, down, west along the south street, and back up.
        self.assertGreater(res["distance_m"], 700,
                           "one-way grid must force a loop, not a 300 m reverse")

    def test_the_loop_never_traverses_a_oneway_backwards(self):
        g, north = self._grid(oneway=True)
        svc = RoutingService(g)
        res = svc.navigate(north[4], north[1])
        coords = res["path"]
        # Every consecutive pair in the path must be a real directed edge.
        for a, b in zip(coords, coords[1:]):
            ka, kb = g.node_key(*a), g.node_key(*b)
            self.assertIn(kb, [to for to, _, _ in g.neighbors(ka)],
                          f"path uses a non-existent edge {a} -> {b}")


if __name__ == "__main__":
    unittest.main()
