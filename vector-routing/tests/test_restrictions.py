"""Turn restrictions (V1 validation — the largest remaining correctness gap).

Before this, `type=restriction` relations were discarded by the converter and
the router had no concept of a forbidden movement, so a no-left-turn was simply
driven through. These tests cover the model and its enforcement in A*.

Enforcement is exercised on synthetic junctions rather than on the Doha extract,
because the live graph carries no restriction data yet — the converter only
started emitting it in this change and Overpass has been unavailable to re-bake.
That limitation is stated in the report rather than hidden behind a green test.
"""

import math
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src"))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "vendor"))

from vector_geo.algorithms import astar  # noqa: E402
from vector_geo.graph import RoutingGraph  # noqa: E402
from vector_routing.restrictions import TurnRestrictions  # noqa: E402

LAT, LON = 25.2854, 51.5310
MPD = 111_320.0
KX = math.cos(math.radians(LAT)) * MPD


def pt(e, n=0.0):
    return (LON + e / KX, LAT + n / MPD)


class CrossJunction:
    """A + junction at the centre, with four arms.

    west --- centre --- east
               |
             south
               |
             (and north)
    """

    def __init__(self):
        self.g = RoutingGraph()
        self.centre = pt(0, 0)
        self.west = pt(-300, 0)
        self.east = pt(300, 0)
        self.north = pt(0, 300)
        self.south = pt(0, -300)
        for arm in (self.west, self.east, self.north, self.south):
            self.g.add_way([arm, self.centre], {"highway": "primary", "maxspeed": 50})
        self.k = self.g.node_key

    def route(self, a, b, restrictions=None):
        return astar(
            self.g, self.k(*a), self.k(*b),
            banned_turn=restrictions.as_predicate() if restrictions else None,
        )


class TurnRestrictionModelTest(unittest.TestCase):
    def test_nothing_is_banned_by_default(self):
        r = TurnRestrictions()
        self.assertFalse(r.is_banned("a", "b", "c"))
        self.assertEqual(len(r), 0)

    def test_an_explicit_ban_is_honoured(self):
        r = TurnRestrictions()
        r.ban("a", "b", "c")
        self.assertTrue(r.is_banned("a", "b", "c"))
        self.assertFalse(r.is_banned("a", "b", "d"), "only the banned movement")
        self.assertFalse(r.is_banned("x", "b", "c"), "only from the stated approach")

    def test_only_turn_forbids_everything_else(self):
        r = TurnRestrictions()
        r.require("a", "b", "c")
        self.assertFalse(r.is_banned("a", "b", "c"), "the required movement is allowed")
        self.assertTrue(r.is_banned("a", "b", "d"))
        self.assertTrue(r.is_banned("a", "b", "e"))
        self.assertFalse(r.is_banned("z", "b", "d"), "a different approach is unaffected")

    def test_no_approach_means_no_restriction(self):
        # At the very start of a search there is no preceding edge, so no
        # restriction can apply.
        r = TurnRestrictions()
        r.ban("a", "b", "c")
        self.assertFalse(r.is_banned(None, "b", "c"))

    def test_counts_are_reported_separately(self):
        r = TurnRestrictions()
        r.ban("a", "b", "c")
        r.require("d", "e", "f")
        self.assertEqual(r.banned_count, 1)
        self.assertEqual(r.only_count, 1)
        self.assertEqual(len(r), 2)


class EnforcementTest(unittest.TestCase):
    def setUp(self):
        self.j = CrossJunction()

    def test_without_restrictions_the_direct_turn_is_taken(self):
        dist, path = self.j.route(self.j.west, self.j.north)
        self.assertIn(self.j.k(*self.j.centre), path)
        self.assertAlmostEqual(dist, 600.0, delta=5.0)

    def test_a_banned_turn_is_not_taken(self):
        """The headline: west -> centre -> north is forbidden.

        The invariant is about the MOVEMENT, not about reachability. This test
        used to assert ``NoRouteError``, which was really asserting a defect: the
        old single-predecessor search could not represent re-entering the
        junction from another arm, so it declared the destination unreachable.
        The exact search finds the legal way round (south, then back through the
        centre) — longer, and legal. What must never happen is the banned
        movement appearing in the path.
        """
        r = TurnRestrictions()
        r.ban(self.j.k(*self.j.west), self.j.k(*self.j.centre), self.j.k(*self.j.north))
        _dist, path = self.j.route(self.j.west, self.j.north, r)
        self.assertNotIn(
            (self.j.k(*self.j.west), self.j.k(*self.j.centre), self.j.k(*self.j.north)),
            list(zip(path, path[1:], path[2:])),
            "the router drove through the banned turn",
        )
        self.assertEqual(path[-1], self.j.k(*self.j.north))

    def test_the_banned_turn_is_refused_when_no_legal_route_exists(self):
        """A dead-end arm has no second approach, so the ban really closes it."""
        from vector_geo.errors import NoRouteError
        g = RoutingGraph()
        west, centre, north = pt(-300, 0), pt(0, 0), pt(0, 300)
        # One-way in, so the centre cannot be re-entered from anywhere else.
        g.add_way([west, centre], {"highway": "primary", "oneway": "yes"})
        g.add_way([centre, north], {"highway": "primary", "oneway": "yes"})
        r = TurnRestrictions()
        r.ban(g.node_key(*west), g.node_key(*centre), g.node_key(*north))
        with self.assertRaises(NoRouteError):
            astar(g, g.node_key(*west), g.node_key(*north),
                  banned_turn=r.as_predicate())

    def test_a_junction_may_be_re_entered_from_another_approach(self):
        """Regression: the exactness fix.

        Banning west->centre->north must not make north unreachable while
        west->centre->south->centre->north exists. The old search kept ONE
        predecessor per node, so once ``centre`` settled with ``west`` as its
        approach the legal southern approach was never considered — and the
        router answered "no route" for a journey a driver can make. Measured on
        the Qatar graph that fired on 11 of 20 probes across banned junctions,
        each after ~5 s of exhausting the frontier.
        """
        r = TurnRestrictions()
        r.ban(self.j.k(*self.j.west), self.j.k(*self.j.centre), self.j.k(*self.j.north))
        dist, path = self.j.route(self.j.west, self.j.north, r)
        self.assertIn(self.j.k(*self.j.south), path,
                      "the legal re-approach through the southern arm was not found")
        self.assertGreater(dist, 600.0, "the legal route is necessarily longer")

    def test_other_movements_through_the_same_junction_still_work(self):
        r = TurnRestrictions()
        r.ban(self.j.k(*self.j.west), self.j.k(*self.j.centre), self.j.k(*self.j.north))
        dist, path = self.j.route(self.j.west, self.j.east, r)
        self.assertGreater(dist, 0, "banning one turn must not close the junction")
        self.assertIn(self.j.k(*self.j.centre), path)

    def test_the_same_turn_from_a_different_approach_is_allowed(self):
        r = TurnRestrictions()
        r.ban(self.j.k(*self.j.west), self.j.k(*self.j.centre), self.j.k(*self.j.north))
        dist, _ = self.j.route(self.j.east, self.j.north, r)
        self.assertGreater(dist, 0)

    def test_only_straight_on_closes_the_turns(self):
        """`only_*` forbids every other exit FROM THAT APPROACH — and only it.

        The distinction matters: requiring west->centre->east must not close the
        centre to a vehicle arriving from the south, so north stays reachable by
        going round. What must not appear is the west->centre->north movement
        the requirement forbids.
        """
        r = TurnRestrictions()
        r.require(self.j.k(*self.j.west), self.j.k(*self.j.centre), self.j.k(*self.j.east))
        _d, path = self.j.route(self.j.west, self.j.north, r)
        self.assertNotIn(
            (self.j.k(*self.j.west), self.j.k(*self.j.centre), self.j.k(*self.j.north)),
            list(zip(path, path[1:], path[2:])),
            "only_straight_on did not forbid the left turn",
        )
        dist, _ = self.j.route(self.j.west, self.j.east, r)
        self.assertGreater(dist, 0, "the required movement must remain open")

    def test_a_detour_is_taken_when_one_exists(self):
        """The realistic case: the turn is banned, so go round the block."""
        j = CrossJunction()
        # A bypass from west to north that avoids the centre. Deliberately set
        # WIDE: a corner at (-300, 300) makes the bypass exactly 600 m, the same
        # as the direct turn, so the test could not tell the two apart.
        corner = pt(-500, 500)
        j.g.add_way([j.west, corner], {"highway": "residential", "maxspeed": 50})
        j.g.add_way([corner, j.north], {"highway": "residential", "maxspeed": 50})

        direct, _ = j.route(j.west, j.north)
        r = TurnRestrictions()
        r.ban(j.k(*j.west), j.k(*j.centre), j.k(*j.north))
        detoured, path = j.route(j.west, j.north, r)

        self.assertGreater(detoured, direct, "the detour must be longer than the banned direct turn")
        self.assertIn(j.k(*corner), path, "the bypass should be used")

    def test_passing_no_restrictions_leaves_behaviour_identical(self):
        # The hook is opt-in: an unrestricted search must be bit-identical.
        a, b = self.j.west, self.j.north
        d1, p1 = self.j.route(a, b)
        d2, p2 = self.j.route(a, b, TurnRestrictions())
        self.assertEqual(d1, d2)
        self.assertEqual(p1, p2)


class FromRelationsTest(unittest.TestCase):
    def test_relations_resolve_to_node_movements(self):
        # from-way runs A -> V, to-way runs V -> B; the junction is V.
        way_nodes = {"10": ["A", "V"], "20": ["V", "B"]}
        rels = [{
            "restriction": "no_left_turn",
            "from_way": "10", "to_way": "20", "via_node_key": "V",
        }]
        r = TurnRestrictions.from_relations(rels, way_nodes)
        self.assertEqual(r.banned_count, 1)
        self.assertTrue(r.is_banned("A", "V", "B"))

    def test_only_relations_become_requirements(self):
        way_nodes = {"10": ["A", "V"], "20": ["V", "B"]}
        rels = [{
            "restriction": "only_straight_on",
            "from_way": "10", "to_way": "20", "via_node_key": "V",
        }]
        r = TurnRestrictions.from_relations(rels, way_nodes)
        self.assertEqual(r.only_count, 1)
        self.assertTrue(r.is_banned("A", "V", "SOMEWHERE_ELSE"))
        self.assertFalse(r.is_banned("A", "V", "B"))

    def test_a_relation_referencing_a_dropped_way_is_skipped(self):
        # Legitimate: a restriction may reference a pedestrian way the car graph
        # excluded. Skipping must be silent, not an error.
        r = TurnRestrictions.from_relations(
            [{"restriction": "no_left_turn", "from_way": "10",
              "to_way": "999", "via_node_key": "V"}],
            {"10": ["A", "V"]},
        )
        self.assertEqual(len(r), 0)

    def test_an_unknown_restriction_kind_is_ignored(self):
        r = TurnRestrictions.from_relations(
            [{"restriction": "no_hovercraft", "from_way": "10",
              "to_way": "20", "via_node_key": "V"}],
            {"10": ["A", "V"], "20": ["V", "B"]},
        )
        self.assertEqual(len(r), 0)

    def test_via_at_the_far_end_of_the_way_still_resolves(self):
        # from-way digitised V -> A rather than A -> V.
        way_nodes = {"10": ["V", "A"], "20": ["V", "B"]}
        r = TurnRestrictions.from_relations(
            [{"restriction": "no_left_turn", "from_way": "10",
              "to_way": "20", "via_node_key": "V"}],
            way_nodes,
        )
        self.assertTrue(r.is_banned("A", "V", "B"))


if __name__ == "__main__":
    unittest.main()
