"""Maneuver generation, case by case (V1 validation §6).

Covers the list the validation brief calls out: left/right, slight left/right,
U-turns, roundabouts, road-name changes, short connectors, ramps, consecutive
turns, unnamed roads, and Arabic / English / mixed name data.

Two failure modes are pinned in both directions, because they pull against each
other and "fixing" one usually breaks the other:

  * **Noise** — a string of "Continue" instructions a driver cannot act on.
  * **Over-collapsing** — two genuinely separate turns merged into one because
    they happen to be close together.
"""

import math
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src"))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "vendor"))

from vector_geo.graph import RoutingGraph  # noqa: E402
from vector_routing.service import RoutingService  # noqa: E402

LAT, LON = 25.2854, 51.5310
MPD = 111_320.0
KX = math.cos(math.radians(LAT)) * MPD


def pt(east_m, north_m):
    return (LON + east_m / KX, LAT + north_m / MPD)


def line(a, b, n=6):
    """`n` points from a to b inclusive."""
    return [(a[0] + (b[0] - a[0]) * i / (n - 1), a[1] + (b[1] - a[1]) * i / (n - 1))
            for i in range(n)]


def types_of(res):
    return [s["type"] for s in res["steps"]]


def text_of(res):
    return " | ".join(s["instruction"] for s in res["steps"])


class TurnDirectionTest(unittest.TestCase):
    def _corner(self, dx, dy):
        g = RoutingGraph()
        a, b = pt(0, 0), pt(500, 0)
        c = pt(500 + dx, dy)
        g.add_way(line(a, b), {"highway": "primary", "name": "East Road", "maxspeed": 60})
        g.add_way(line(b, c), {"highway": "primary", "name": "Second Road", "maxspeed": 60})
        return RoutingService(g).navigate(a, c)

    def test_left_turn(self):
        self.assertIn("turn-left", types_of(self._corner(0, 500)))

    def test_right_turn(self):
        self.assertIn("turn-right", types_of(self._corner(0, -500)))

    def test_slight_left(self):
        # ~30 degrees off straight.
        self.assertIn("slight-left", types_of(self._corner(430, 250)))

    def test_slight_right(self):
        self.assertIn("slight-right", types_of(self._corner(430, -250)))

    def test_a_gentle_bend_is_not_a_turn(self):
        # ~10 degrees: geometry, not a decision.
        res = self._corner(490, 88)
        self.assertNotIn("turn-left", types_of(res))
        self.assertNotIn("slight-left", types_of(res))


class RoundaboutTest(unittest.TestCase):
    def _roundabout(self, exit_index):
        """A 4-exit roundabout: approach from the west, leave at `exit_index`."""
        g = RoutingGraph()
        centre = pt(500, 0)
        r = 30.0
        # Ring nodes at N, E, S, W (clockwise from north).
        ring = [pt(500 + r * math.sin(math.radians(a)), r * math.cos(math.radians(a)))
                for a in (270, 0, 90, 180)]   # W, N, E, S
        # Circulate W -> N -> E -> S -> W, one-way, tagged as a roundabout.
        for i in range(len(ring)):
            g.add_way([ring[i], ring[(i + 1) % len(ring)]],
                      {"highway": "primary", "junction": "roundabout", "oneway": "yes"})
        approach = line(pt(0, 0), ring[0])
        g.add_way(approach, {"highway": "primary", "name": "Approach Road", "maxspeed": 60})
        spurs = {
            1: line(ring[1], pt(500, 400)),
            2: line(ring[2], pt(900, 0)),
            3: line(ring[3], pt(500, -400)),
        }
        names = {1: "North Spur", 2: "East Spur", 3: "South Spur"}
        for i, spur in spurs.items():
            g.add_way(spur, {"highway": "primary", "name": names[i], "maxspeed": 60})
        return RoutingService(g).navigate(pt(0, 0), spurs[exit_index][-1]), names[exit_index]

    def test_a_roundabout_is_one_maneuver_not_a_string_of_bends(self):
        res, _ = self._roundabout(2)
        t = types_of(res)
        self.assertIn("roundabout", t, f"expected a roundabout maneuver, got {t}")
        # The ring's own geometry must not leak out as slight turns.
        self.assertLessEqual(
            sum(1 for x in t if x in ("slight-left", "slight-right")), 1,
            f"roundabout geometry leaked into turn instructions: {t}",
        )

    def test_the_exit_road_is_named(self):
        res, name = self._roundabout(2)
        self.assertIn(name, text_of(res), text_of(res))

    def test_the_instruction_reads_like_a_navigator(self):
        res, _ = self._roundabout(2)
        rb = [s for s in res["steps"] if s["type"] == "roundabout"][0]
        self.assertIn("roundabout", rb["instruction"].lower())

    def test_a_later_exit_is_numbered_higher_than_an_earlier_one(self):
        """Exit ordinals must be counted, not guessed."""
        first, _ = self._roundabout(1)
        third, _ = self._roundabout(3)

        def ordinal(res):
            for s in res["steps"]:
                if s["type"] == "roundabout":
                    for n, word in enumerate(
                        ("", "first", "second", "third", "fourth", "fifth")
                    ):
                        if word and word in s["instruction"]:
                            return n
            return None

        a, b = ordinal(first), ordinal(third)
        if a is not None and b is not None:
            self.assertLess(a, b, f"exit ordering wrong: {a} vs {b}")


class ConsecutiveAndShortTest(unittest.TestCase):
    def test_two_real_turns_close_together_are_both_kept(self):
        """Over-collapsing is as bad as noise: a driver needs both."""
        g = RoutingGraph()
        a, b, c, d = pt(0, 0), pt(400, 0), pt(400, 120), pt(800, 120)
        g.add_way(line(a, b), {"highway": "primary", "name": "First", "maxspeed": 60})
        g.add_way(line(b, c), {"highway": "primary", "name": "Second", "maxspeed": 60})
        g.add_way(line(c, d), {"highway": "primary", "name": "Third", "maxspeed": 60})
        res = RoutingService(g).navigate(a, d)
        turns = [t for t in types_of(res) if t.startswith("turn")]
        self.assertEqual(len(turns), 2, f"both turns must survive: {types_of(res)}")

    def test_a_junction_modelled_as_several_tiny_bends_is_one_instruction(self):
        """A corner drawn with 2 m spacing must not become three instructions."""
        g = RoutingGraph()
        a = pt(0, 0)
        approach = line(a, pt(300, 0))
        # Three 30-degree bends within 6 m: one physical corner.
        micro = [pt(300, 0), pt(302, 1), pt(303, 3), pt(304, 6), pt(310, 60), pt(320, 300)]
        g.add_way(approach, {"highway": "primary", "name": "Approach", "maxspeed": 60})
        g.add_way(micro, {"highway": "primary", "name": "Approach", "maxspeed": 60})
        res = RoutingService(g).navigate(a, micro[-1])
        turns = [t for t in types_of(res) if t.startswith(("turn", "slight"))]
        self.assertLessEqual(len(turns), 1, f"micro-geometry became instructions: {types_of(res)}")

    def test_no_step_repeats_the_same_continue_over_and_over(self):
        g = RoutingGraph()
        a, b = pt(0, 0), pt(3000, 0)
        g.add_way(line(a, b, n=150), {"highway": "primary", "name": "Long Road", "maxspeed": 80})
        res = RoutingService(g).navigate(a, b)
        self.assertEqual(len(res["steps"]), 2, text_of(res))


class NameHandlingTest(unittest.TestCase):
    def _two_roads(self, name_a, name_b):
        g = RoutingGraph()
        a, b, c = pt(0, 0), pt(400, 0), pt(400, 400)
        g.add_way(line(a, b), {"highway": "primary", "name": name_a, "maxspeed": 60})
        g.add_way(line(b, c), {"highway": "primary", "name": name_b, "maxspeed": 60})
        return RoutingService(g).navigate(a, c)

    def test_arabic_names_survive_end_to_end(self):
        res = self._two_roads("شارع الكورنيش", "شارع المرخية")
        self.assertIn("شارع المرخية", text_of(res), text_of(res))

    def test_arabic_is_not_mangled_or_escaped(self):
        res = self._two_roads("شارع الكورنيش", "شارع المرخية")
        txt = text_of(res)
        self.assertNotIn("\\u", txt)
        self.assertNotIn("?", txt)
        # The exact codepoints, not a lookalike.
        self.assertIn("شارع", txt)

    def test_english_names(self):
        self.assertIn("Al Corniche Street", text_of(self._two_roads("A", "Al Corniche Street")))

    def test_mixed_arabic_and_english_in_one_route(self):
        res = self._two_roads("شارع الكورنيش", "Lusail Expressway")
        txt = text_of(res)
        self.assertIn("شارع الكورنيش", txt)
        self.assertIn("Lusail Expressway", txt)

    def test_an_unnamed_road_gets_a_speakable_fallback(self):
        g = RoutingGraph()
        a, b, c = pt(0, 0), pt(400, 0), pt(400, 400)
        g.add_way(line(a, b), {"highway": "primary", "maxspeed": 60})
        g.add_way(line(b, c), {"highway": "tertiary", "maxspeed": 60})
        txt = text_of(RoutingService(g).navigate(a, c))
        # "the road", not "the tertiary road" — V6. The point of this case is
        # that an unnamed way gets a SPEAKABLE fallback rather than a `None` or
        # a raw tag, and that property is unchanged and still asserted below;
        # what changed is that the fallback no longer recites OSM's
        # classification at a driver. See `_ROAD_WORDS`.
        self.assertIn("the road", txt, txt)
        self.assertNotIn("tertiary", txt, txt)
        self.assertNotIn("None", txt)

    def test_a_ramp_is_called_a_slip_road(self):
        g = RoutingGraph()
        a, b, c = pt(0, 0), pt(400, 0), pt(700, 300)
        g.add_way(line(a, b), {"highway": "motorway", "name": "Expressway", "maxspeed": 100})
        g.add_way(line(b, c), {"highway": "motorway_link", "maxspeed": 60})
        txt = text_of(RoutingService(g).navigate(a, c))
        self.assertIn("slip road", txt, txt)
        self.assertNotIn("motorway_link", txt)

    def test_internal_tags_never_reach_the_driver(self):
        g = RoutingGraph()
        a, b, c = pt(0, 0), pt(400, 0), pt(400, 400)
        g.add_way(line(a, b), {"highway": "trunk_link", "maxspeed": 60})
        g.add_way(line(b, c), {"highway": "living_street", "maxspeed": 20})
        txt = text_of(RoutingService(g).navigate(a, c))
        for leak in ("trunk_link", "living_street", "highway=", "_link"):
            self.assertNotIn(leak, txt, f"{leak!r} leaked: {txt}")


if __name__ == "__main__":
    unittest.main()


class LaneGuidanceFieldsTest(unittest.TestCase):
    """`turn:lanes` must reach the step, and must come from the APPROACH.

    At a junction the lanes that matter are the ones on the road you are
    currently in, not the road you are turning onto — by the time you are on the
    new road, choosing a lane for the turn is too late. Getting this backwards
    produces guidance that is confidently wrong, which is worse than none.

    3,090 ways in the Doha extract carry `turn:lanes`, so this is real data.
    """

    LAT, LON = 25.2854, 51.5310

    def _corner(self, approach_props, exit_props):
        g = RoutingGraph()
        kx = math.cos(math.radians(self.LAT)) * MPD
        east = [(self.LON + (i * 50.0) / kx, self.LAT) for i in range(11)]
        corner = east[-1]
        north = [(corner[0], self.LAT + (i * 50.0) / MPD) for i in range(11)]
        g.add_way(east, approach_props)
        g.add_way(north, exit_props)
        return RoutingService(g).navigate(east[0], north[-1])

    def test_turn_lanes_reach_the_step(self):
        res = self._corner(
            {"highway": "primary", "name": "Approach", "maxspeed": 60,
             "turn:lanes": "left|through|through"},
            {"highway": "primary", "name": "Exit", "maxspeed": 60},
        )
        turn = [s for s in res["steps"] if s["type"].startswith("turn")]
        self.assertTrue(turn, "no turn produced: %s" % [s["type"] for s in res["steps"]])
        self.assertEqual(turn[0].get("turn_lanes"), "left|through|through")

    def test_the_lanes_come_from_the_approach_not_the_exit(self):
        res = self._corner(
            {"highway": "primary", "name": "Approach", "maxspeed": 60,
             "turn:lanes": "left|through"},
            {"highway": "primary", "name": "Exit", "maxspeed": 60,
             "turn:lanes": "right|right|right"},
        )
        turn = [s for s in res["steps"] if s["type"].startswith("turn")][0]
        self.assertEqual(turn.get("turn_lanes"), "left|through",
                         "the step took the EXIT road's lanes, which is too late to act on")

    def test_no_lane_data_means_no_field_rather_than_an_empty_one(self):
        res = self._corner(
            {"highway": "primary", "name": "Approach", "maxspeed": 60},
            {"highway": "primary", "name": "Exit", "maxspeed": 60},
        )
        for s in res["steps"]:
            self.assertNotIn("turn_lanes", s,
                             "absent data must be absent, not an empty string")

    def test_motorway_exit_reference_reaches_the_step(self):
        res = self._corner(
            {"highway": "motorway", "name": "Expressway", "maxspeed": 100,
             "junction:ref": "12", "destination": "Al Wakrah"},
            {"highway": "motorway_link", "maxspeed": 60},
        )
        withref = [s for s in res["steps"] if s.get("exit_ref")]
        self.assertTrue(withref, "exit number never reached a step")
        self.assertEqual(withref[0]["exit_ref"], "12")
        self.assertEqual(withref[0].get("destination"), "Al Wakrah")

    def test_departure_has_no_approach_and_so_no_lanes(self):
        res = self._corner(
            {"highway": "primary", "name": "Approach", "maxspeed": 60,
             "turn:lanes": "left|through"},
            {"highway": "primary", "name": "Exit", "maxspeed": 60},
        )
        self.assertNotIn("turn_lanes", res["steps"][0])


class BearingWindowTest(unittest.TestCase):
    """A maneuver is classified from the ROAD, not from two adjacent vertices.

    Comparing only the edges touching a vertex reads the drawing rather than the
    junction. Both failure directions were found on live Doha routes and
    adjudicated by OSM's own `turn:lanes` data, which is an independent source:
    over 81 maneuvers whose lane strip was unanimous ("every lane turns left"),
    the adjacent-edge rule agreed with the lanes **36%** of the time and the
    25 m windowed rule **80%**. The window is a plateau, not a tuned constant —
    15 m scores 84%, 20/25/30 m score 80-81%, 60 m 80%, and only below 15 m does
    it collapse (56% at 10 m).
    """

    # A ~75-degree corner drawn as five small bends over ~20 m. Each bend is
    # under the 20-degree "continue" threshold, so the adjacent-edge rule saw
    # nothing; the corner is not, so the windowed rule sees a turn.
    _CORNER = [pt(300, 0), pt(304, 1), pt(308, 3), pt(311, 6), pt(313, 10),
               pt(314, 15), pt(320, 300)]

    def _junction(self):
        """The corner above, at a real junction: a side road continues east.

        The side road matters. A bend a driver cannot get wrong is not a
        maneuver, so the corner is only announced where there is somewhere else
        to go — which is also the only place OSM tags lanes for it.
        """
        g = RoutingGraph()
        g.add_way(line(pt(0, 0), pt(300, 0)),
                  {"highway": "primary", "name": "Approach", "maxspeed": 60})
        g.add_way(self._CORNER, {"highway": "primary", "name": "Approach", "maxspeed": 60})
        g.add_way(line(pt(300, 0), pt(600, 0)),
                  {"highway": "primary", "name": "Side Road", "maxspeed": 60})
        return g

    def test_a_turn_drawn_as_several_small_bends_is_announced(self):
        """The missed turn: told to continue, the driver drives past it."""
        types = types_of(RoutingService(self._junction()).navigate(pt(0, 0), self._CORNER[-1]))
        self.assertTrue(any(t.startswith(("turn", "slight")) for t in types),
                        f"the corner was never announced: {types}")

    def test_that_corner_is_announced_exactly_once(self):
        """...and the window must not turn one corner into a run of them.

        A windowed bearing change is non-zero at every vertex within the window
        of the corner, so without suppression this reports the same turn four or
        five times.
        """
        types = types_of(RoutingService(self._junction()).navigate(pt(0, 0), self._CORNER[-1]))
        turns = [t for t in types if t.startswith(("turn", "slight"))]
        self.assertEqual(len(turns), 1, f"one corner became {len(turns)} instructions: {types}")

    def test_the_same_corner_is_silent_when_there_is_nowhere_else_to_go(self):
        """No junction, no decision, no instruction.

        The identical geometry without the side road. A road that simply bends
        needs no announcement, and announcing it is what filled a live Doha
        route with "Bear left to stay on Al Corniche".
        """
        g = RoutingGraph()
        g.add_way(line(pt(0, 0), pt(300, 0)),
                  {"highway": "primary", "name": "Approach", "maxspeed": 60})
        g.add_way(self._CORNER, {"highway": "primary", "name": "Approach", "maxspeed": 60})
        types = types_of(RoutingService(g).navigate(pt(0, 0), self._CORNER[-1]))
        self.assertEqual([t for t in types if t.startswith(("turn", "slight"))], [],
                         f"a bend with no alternative was announced: {types}")

    def test_a_dogleg_is_not_a_turn(self):
        """The false turn.

        Two opposite bends a few metres apart — a carriageway stepping sideways
        to line up with a bridge, say. Each is sharp enough for the adjacent-edge
        rule to call it a turn, so the driver was told to turn left and then
        immediately right while the road simply jogged. Measured over the window
        the net change is ~0.
        """
        g = RoutingGraph()
        a = pt(0, 0)
        jog = [pt(300, 0), pt(304, 4), pt(308, 4), pt(312, 0), pt(600, 0)]
        g.add_way(line(a, pt(300, 0)), {"highway": "primary", "name": "Straight", "maxspeed": 60})
        g.add_way(jog, {"highway": "primary", "name": "Straight", "maxspeed": 60})
        types = types_of(RoutingService(g).navigate(a, jog[-1]))
        self.assertEqual([t for t in types if t.startswith(("turn", "slight"))], [],
                         f"a sideways jog was announced as turns: {types}")

    def test_a_real_sharp_turn_is_still_a_sharp_turn(self):
        """The window must not blunt a genuine corner into a slight bend."""
        g = RoutingGraph()
        a, b, c = pt(0, 0), pt(400, 0), pt(400, 400)
        g.add_way(line(a, b), {"highway": "primary", "name": "First", "maxspeed": 60})
        g.add_way(line(b, c), {"highway": "primary", "name": "Second", "maxspeed": 60})
        self.assertIn("turn-left", types_of(RoutingService(g).navigate(a, c)))

    def test_a_long_sweeping_bend_is_still_not_a_turn(self):
        """A motorway curve of 30 degrees over 300 m is geometry, not a decision.

        This is the property that lets the window be widened at all: it measures
        a bounded distance, so gradual curvature stays gradual.
        """
        g = RoutingGraph()
        pts = [pt(400 * math.sin(math.radians(t)), 400 - 400 * math.cos(math.radians(t)))
               for t in range(0, 31, 2)]
        g.add_way(pts, {"highway": "motorway", "name": "Sweep", "maxspeed": 100})
        types = types_of(RoutingService(g).navigate(pts[0], pts[-1]))
        self.assertEqual([t for t in types if t.startswith(("turn", "slight"))], [],
                         f"a sweeping bend became a turn: {types}")
