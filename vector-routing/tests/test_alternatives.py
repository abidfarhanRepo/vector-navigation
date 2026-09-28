"""Alternative routes.

`/route?alternatives=1` was accepted and IGNORED — the parameter existed, the
engine always returned a single path, and a client asking for choices silently
got none. Every consumer navigator offers two or three options; Vector offered
one and said nothing about it.

The tests below pin the two things that make alternatives useful rather than
merely numerous: they must be genuinely DIFFERENT roads (not the same corridor
via one different junction) and they must be REASONABLE (not an 80%-slower
detour presented as a choice).
"""

import math
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src"))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "vendor"))

from vector_geo.graph import RoutingGraph  # noqa: E402
from vector_routing.alternatives import (  # noqa: E402
    MAX_DETOUR, MAX_OVERLAP, describe, find_alternatives, overlap_fraction,
)
from vector_routing.router import _distinguishing_roads  # noqa: E402
from vector_routing.service import RoutingService  # noqa: E402

LAT, LON = 25.2854, 51.5310
MPD = 111_320.0
KX = math.cos(math.radians(LAT)) * MPD


def pt(e, n=0.0):
    return (LON + e / KX, LAT + n / MPD)


def line(a, b, n=6):
    return [(a[0] + (b[0]-a[0]) * i / (n-1), a[1] + (b[1]-a[1]) * i / (n-1)) for i in range(n)]


class OverlapTest(unittest.TestCase):
    def test_identical_paths_fully_overlap(self):
        p = ["a", "b", "c", "d"]
        self.assertEqual(overlap_fraction(p, p), 1.0)

    def test_disjoint_paths_do_not_overlap(self):
        self.assertEqual(overlap_fraction(["a", "b"], ["c", "d"]), 0.0)

    def test_partial_overlap_is_measured_against_the_candidate(self):
        a = ["x", "y", "z"]           # edges xy, yz
        b = ["x", "y", "q"]           # edges xy, yq  -> half of b is shared
        self.assertAlmostEqual(overlap_fraction(a, b), 0.5)

    def test_an_empty_candidate_counts_as_fully_overlapping(self):
        # Degenerate input must be rejected as an alternative, not offered.
        self.assertEqual(overlap_fraction(["a", "b"], []), 1.0)


class FindAlternativesTest(unittest.TestCase):
    """Driven through a fake route_fn so the policy is tested in isolation."""

    class FakeRoute:
        def __init__(self, keys, duration):
            self.node_keys = keys
            self.duration_s = duration
            self.distance_m = duration * 15.0

    def test_asking_for_one_returns_only_the_primary(self):
        calls = []
        def fn(m):
            calls.append(m)
            return self.FakeRoute(["a", "b", "c"], 100.0)
        out = find_alternatives(fn, wanted=1)
        self.assertEqual(len(out), 1)
        self.assertEqual(len(calls), 1, "must not compute a second route it was not asked for")

    def test_a_distinct_reasonable_route_is_offered(self):
        seq = [self.FakeRoute(["a", "b", "c"], 100.0),
               self.FakeRoute(["a", "x", "c"], 115.0)]
        out = find_alternatives(lambda m: seq.pop(0), wanted=2)
        self.assertEqual(len(out), 2)

    def test_a_near_identical_route_is_rejected(self):
        # Same corridor, one different junction: technically a different path,
        # useless to a driver.
        seq = [self.FakeRoute(["a", "b", "c", "d", "e"], 100.0),
               self.FakeRoute(["a", "b", "c", "d", "z"], 101.0)]
        out = find_alternatives(lambda m: seq.pop(0), wanted=2)
        self.assertEqual(len(out), 1, "a route sharing most of its length is the same route")

    def test_a_wildly_slower_route_is_rejected(self):
        seq = [self.FakeRoute(["a", "b", "c"], 100.0),
               self.FakeRoute(["a", "x", "c"], 100.0 * MAX_DETOUR + 60)]
        out = find_alternatives(lambda m: seq.pop(0), wanted=2)
        self.assertEqual(len(out), 1, "an 80%-slower route is a mistake, not an option")

    def test_an_unroutable_second_pass_degrades_to_the_primary(self):
        # Penalising the only corridor can disconnect the graph. That means "no
        # alternative exists", not "the request failed".
        state = {"n": 0}
        def fn(m):
            state["n"] += 1
            if state["n"] == 1:
                return self.FakeRoute(["a", "b", "c"], 100.0)
            raise RuntimeError("no route")
        out = find_alternatives(fn, wanted=3)
        self.assertEqual(len(out), 1)

    def test_the_penalty_multiplier_targets_the_used_edges(self):
        seen = {}
        seq = [self.FakeRoute(["a", "b", "c"], 100.0),
               self.FakeRoute(["a", "x", "c"], 110.0)]
        def fn(m):
            if m is not None:
                seen["ab"] = m("a", "b")
                seen["ax"] = m("a", "x")
            return seq.pop(0)
        find_alternatives(fn, wanted=2)
        self.assertGreater(seen["ab"], 1.0, "an edge on the primary must be penalised")
        self.assertEqual(seen["ax"], 1.0, "an unused edge must not be penalised")


class DescribeTest(unittest.TestCase):
    class R:
        def __init__(self, d, s):
            self.distance_m, self.duration_s = d, s

    def test_deltas_are_relative_to_the_best_route(self):
        out = describe([self.R(10000, 600), self.R(11000, 660), self.R(12000, 780)])
        self.assertEqual([d["duration_delta_s"] for d in out], [0.0, 60.0, 180.0])
        self.assertTrue(out[0]["primary"])
        self.assertFalse(out[1]["primary"])

    def test_route_ids_are_stable_and_ordered(self):
        out = describe([self.R(1, 1), self.R(2, 2)])
        self.assertEqual([d["route_id"] for d in out], [0, 1])

    def test_no_routes_describes_nothing(self):
        self.assertEqual(describe([]), [])


class EndToEndTest(unittest.TestCase):
    """Two real parallel corridors between the same endpoints."""

    def _two_corridors(self):
        g = RoutingGraph()
        a, b = pt(0, 0), pt(2000, 0)
        # Direct road.
        g.add_way(line(a, b, 21), {"highway": "primary", "name": "Direct Road", "maxspeed": 60})
        # A parallel road 400 m north, slightly longer.
        na, nb = pt(0, 400), pt(2000, 400)
        g.add_way(line(a, na), {"highway": "residential", "maxspeed": 50})
        g.add_way(line(na, nb, 21), {"highway": "secondary", "name": "North Road", "maxspeed": 60})
        g.add_way(line(nb, b), {"highway": "residential", "maxspeed": 50})
        return g, a, b

    def test_two_corridors_yield_two_routes(self):
        g, a, b = self._two_corridors()
        routes = RoutingService(g).route_alternatives(a, b, wanted=2)
        self.assertEqual(len(routes), 2, "a genuine parallel corridor must be offered")

    def test_the_alternative_is_actually_a_different_road(self):
        g, a, b = self._two_corridors()
        routes = RoutingService(g).route_alternatives(a, b, wanted=2)
        self.assertLess(
            overlap_fraction(routes[0].node_keys, routes[1].node_keys), MAX_OVERLAP,
            "the alternative reuses most of the primary",
        )

    def test_the_primary_is_the_fastest(self):
        g, a, b = self._two_corridors()
        routes = RoutingService(g).route_alternatives(a, b, wanted=3)
        for r in routes[1:]:
            self.assertGreaterEqual(r.duration_s, routes[0].duration_s - 1e-6)

    def test_a_single_corridor_yields_one_route_without_erroring(self):
        # On a peninsula there is frequently exactly one way, and that is a
        # correct answer rather than a failure.
        g = RoutingGraph()
        a, b = pt(0, 0), pt(1000, 0)
        g.add_way(line(a, b, 11), {"highway": "primary", "maxspeed": 60})
        routes = RoutingService(g).route_alternatives(a, b, wanted=3)
        self.assertEqual(len(routes), 1)


class NavigableAlternativesTest(unittest.TestCase):
    """An alternative you cannot drive is not an alternative.

    This is the gap these tests exist for: `/route?alternatives=1` returned
    geometry, a distance and a duration per option and NO `steps`, while
    `/navigate` returned steps and accepted no alternatives parameter. So a
    client could draw a choice and then had nothing to navigate — re-requesting
    `/navigate` for the same endpoints returns the PRIMARY route, because
    nothing identified the option that was chosen. Choosing "via North Road"
    would have silently driven the driver down Direct Road.
    """

    def _two_corridors(self):
        g = RoutingGraph()
        a, b = pt(0, 0), pt(2000, 0)
        g.add_way(line(a, b, 21), {"highway": "primary", "name": "Direct Road", "maxspeed": 60})
        na, nb = pt(0, 400), pt(2000, 400)
        g.add_way(line(a, na), {"highway": "residential", "maxspeed": 50})
        g.add_way(line(na, nb, 21), {"highway": "secondary", "name": "North Road", "maxspeed": 60})
        g.add_way(line(nb, b), {"highway": "residential", "maxspeed": 50})
        return g, a, b

    def test_every_alternative_carries_turn_by_turn_steps(self):
        g, a, b = self._two_corridors()
        answers = RoutingService(g).navigate_alternatives(a, b, wanted=2)
        self.assertEqual(len(answers), 2)
        for i, ans in enumerate(answers):
            self.assertTrue(ans["steps"], f"alternative {i} has no steps to drive")

    def test_each_alternative_is_identified_so_a_client_can_choose_it(self):
        g, a, b = self._two_corridors()
        answers = RoutingService(g).navigate_alternatives(a, b, wanted=2)
        self.assertEqual([a_["route_id"] for a_ in answers], [0, 1])
        self.assertTrue(answers[0]["primary"])
        self.assertFalse(answers[1]["primary"])

    def test_an_alternative_has_the_same_shape_as_a_single_route(self):
        # A client that has to branch on which endpoint produced a route is a
        # client that will get one of the branches wrong.
        g, a, b = self._two_corridors()
        svc = RoutingService(g)
        single = svc.navigate(a, b)
        alts = svc.navigate_alternatives(a, b, wanted=2)
        for key in single:
            self.assertIn(key, alts[0], f"alternatives are missing {key!r}")

    def test_the_geometries_actually_differ(self):
        g, a, b = self._two_corridors()
        answers = RoutingService(g).navigate_alternatives(a, b, wanted=2)
        self.assertNotEqual(answers[0]["path"], answers[1]["path"])

    def test_a_route_with_no_exclusive_named_road_gets_no_label(self):
        # Two options that differ only by an unnamed slip road have nothing to
        # say about each other, and "via" must not be invented.
        answers = [
            {"steps": [{"road": "Shared Road", "distance_m": 1000.0},
                       {"road": None, "distance_m": 50.0}]},
            {"steps": [{"road": "Shared Road", "distance_m": 1000.0},
                       {"road": None, "distance_m": 60.0}]},
        ]
        self.assertEqual(_distinguishing_roads(answers), ["", ""])

    def test_the_label_names_the_road_that_distinguishes_the_route(self):
        # "12 min / 11.0 km" beside "13 min / 11.2 km" tells a driver nothing
        # about WHICH way either goes. The road name is the whole decision.
        g, a, b = self._two_corridors()
        answers = RoutingService(g).navigate_alternatives(a, b, wanted=2)
        labels = [a_["label"] for a_ in answers]
        self.assertIn("via Direct Road", labels)
        self.assertIn("via North Road", labels)

    def test_a_single_corridor_still_answers_with_one_navigable_route(self):
        g = RoutingGraph()
        a, b = pt(0, 0), pt(1000, 0)
        g.add_way(line(a, b, 11), {"highway": "primary", "name": "Only Road", "maxspeed": 60})
        answers = RoutingService(g).navigate_alternatives(a, b, wanted=3)
        self.assertEqual(len(answers), 1)
        self.assertTrue(answers[0]["steps"])
        # With one route every road it uses is trivially exclusive, so the label
        # degrades to its longest road rather than to nothing. Still true of the
        # route; the UI simply does not render chips for a choice of one.
        self.assertEqual(answers[0]["label"], "via Only Road")

    def test_steps_name_the_road_they_are_driven_on(self):
        # `_distinguishing_roads` needs per-road distances, and a client showing
        # a step list cannot recover the name by parsing the instruction
        # sentence — especially not an Arabic one.
        g = RoutingGraph()
        a, b = pt(0, 0), pt(1000, 0)
        g.add_way(line(a, b, 11), {"highway": "primary", "name": "Named Road", "maxspeed": 60})
        steps = RoutingService(g).navigate(a, b)["steps"]
        self.assertTrue(any(s.get("road") == "Named Road" for s in steps),
                        "no step reports the road it is driven on")


if __name__ == "__main__":
    unittest.main()
