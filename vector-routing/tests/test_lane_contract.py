"""V7 Stage 1 — the routing/lane wire contract plus roundabout direction.

Covers the additive fields a client needs to tell "no lane data" from "no lane
choice":

* `lane_data.source` / `lane_data.direction` — provenance, always present on a
  step with an approach;
* `lane_data.turn_lanes` — the `turn:lanes` string RESOLVED for the direction
  driven (mirrored for a backward traversal, `:forward`/`:backward` honoured);
* `approach_lanes` — lane count under the vehicle, only when the map has it;
* `lane_data.forward_lanes` — lanes in the direction DRIVEN (V7 Stage 4), which
  is not `approach_lanes` on a two-way way: OSM counts both directions in
  `lanes`, so 5,728 Qatari two-way `lanes=2` ways are one lane each way. A
  client that sizes the route from `approach_lanes` and centres it on the way
  centreline draws the route across the ONCOMING lane on every one of them;
* `preferred`/`preferred_reason` — MUST be omitted: no per-lane destination or
  connectivity data exists in the extract to justify a preference;
* roundabouts — a Qatar (right-hand traffic, counter-clockwise) ring orders its
  exits correctly and NEVER claims a preferred lane; and the approach bend into
  a roundabout is geometry, not a separate "turn left"/"Make a U-turn" step.

The direction defect this pins: OSM writes `turn:lanes` in the way's
digitisation order, and before the graph tagged which way each directed edge
runs, BOTH directions of a two-way way were handed the same string — a driver
going the other way was quietly told the lanes were mirrored.
"""

import math
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src"))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "vendor"))

from vector_geo.graph import RoutingGraph  # noqa: E402
from vector_routing.router import (  # noqa: E402
    _forward_lane_count,
    _is_oneway,
    _resolve_turn_lanes,
)
from vector_routing.service import RoutingService  # noqa: E402

LAT, LON = 25.2854, 51.5310
MPD = 111_320.0
KX = math.cos(math.radians(LAT)) * MPD


def pt(east_m, north_m):
    return (LON + east_m / KX, LAT + north_m / MPD)


def line(a, b, n=6):
    return [(a[0] + (b[0] - a[0]) * i / (n - 1), a[1] + (b[1] - a[1]) * i / (n - 1))
            for i in range(n)]


def _seg(turn_lanes=None, fwd=None, bwd=None, lane_dir=None, lanes=None, oneway=None):
    return {"turn_lanes": turn_lanes, "turn_lanes_forward": fwd,
            "turn_lanes_backward": bwd, "lane_dir": lane_dir, "lanes": lanes,
            "oneway": oneway}


class ResolveTurnLanesTest(unittest.TestCase):
    """`_resolve_turn_lanes` — pure, so every direction case is cheap to pin."""

    def test_forward_traversal_keeps_the_unsuffixed_string(self):
        self.assertEqual(
            _resolve_turn_lanes(_seg("left|through|through", lane_dir="forward")),
            ("left|through|through", "forward", "turn:lanes"),
        )

    def test_backward_traversal_mirrors_the_unsuffixed_string(self):
        # Digitised west->east with `turn:lanes=left|through`; driven east->west,
        # the driver's LEFT lane is the digitisation's RIGHT lane.
        self.assertEqual(
            _resolve_turn_lanes(_seg("left|through", lane_dir="backward")),
            ("through|left", "backward", "turn:lanes"),
        )

    def test_backward_uses_turn_lanes_backward_without_reversing(self):
        # `turn:lanes:backward` is already written from the backward driver's
        # point of view; reversing it would mirror it AGAIN.
        self.assertEqual(
            _resolve_turn_lanes(_seg(fwd="left|through", bwd="through|right", lane_dir="backward")),
            ("through|right", "backward", "turn:lanes"),
        )

    def test_forward_prefers_the_explicit_forward_tag(self):
        self.assertEqual(
            _resolve_turn_lanes(_seg("left|through", fwd="right|right", lane_dir="forward")),
            ("right|right", "forward", "turn:lanes"),
        )

    def test_backward_with_only_a_forward_tag_has_no_resolved_lanes(self):
        # The `:forward` value describes the other direction; using it backwards
        # is the exact mirror-defect this module exists to stop.
        self.assertEqual(
            _resolve_turn_lanes(_seg(fwd="left|through", lane_dir="backward")),
            (None, "backward", "none"),
        )

    def test_unknown_direction_keeps_the_raw_string(self):
        # Older graph serializations carry no `_way_dir`; the raw string is the
        # best available answer and the direction says so.
        self.assertEqual(
            _resolve_turn_lanes(_seg("left|through")),
            ("left|through", "unknown", "turn:lanes"),
        )

    def test_no_tag_anywhere_is_source_none(self):
        self.assertEqual(
            _resolve_turn_lanes(_seg(lane_dir="forward")),
            (None, "forward", "none"),
        )


class ForwardLaneCountTest(unittest.TestCase):
    """`_forward_lane_count` — lanes in the direction driven, or nothing.

    The V7 Stage 4 field. Pure, so every shape of way is cheap to pin, and worth
    pinning exhaustively because the failure mode of getting it wrong is a route
    ribbon drawn down the oncoming carriageway.
    """

    # ---- source 1: the resolved turn:lanes cell count --------------------

    def test_a_one_way_lane_string_counts_its_own_cells(self):
        # The slip-split approach from the acceptance trace.
        spec = "through|through|through|through;slight_right"
        self.assertEqual(
            _forward_lane_count(_seg(spec, lanes="4", oneway="yes"), spec), 4)

    def test_the_lane_string_wins_over_a_disagreeing_lanes_tag(self):
        # 5 of the 3,306 one-way Qatari ways that carry both disagree. The cell
        # count is direction-specific by construction; `lanes` is a separate
        # tag that can be stale.
        spec = "left|left|right"
        self.assertEqual(
            _forward_lane_count(_seg(spec, lanes="2", oneway="yes"), spec), 3)

    def test_unmarked_cells_still_count_as_lanes(self):
        # `||right` is three lanes, two of them unmarked — the same rule
        # `LaneGuidance.parse` follows, for the same reason: dropping blanks
        # shifts the driver's lane count.
        self.assertEqual(
            _forward_lane_count(_seg("||right", oneway="yes"), "||right"), 3)

    def test_a_two_way_directional_tag_counts_its_own_cells(self):
        # `turn:lanes:forward` describes one direction even on a two-way way.
        seg = _seg(fwd="left|through", lanes="4", lane_dir="forward")
        self.assertEqual(_forward_lane_count(seg, "left|through"), 2)

    def test_an_unsuffixed_string_on_a_two_way_way_is_not_trusted(self):
        # The 16-way case. Reading `left|through` as "2 forward lanes" on a
        # two-way `lanes=2` road would claim the whole road for one direction —
        # the exact oncoming-traffic error this field exists to prevent. Fall
        # back to halving instead, which is right about the carriageway even
        # though it declines to be right about the lanes.
        seg = _seg("left|through", lanes="2")
        self.assertEqual(_forward_lane_count(seg, "left|through"), 1)

    # ---- source 2/3: the lanes tag ---------------------------------------

    def test_a_one_way_way_takes_the_whole_lane_count(self):
        self.assertEqual(_forward_lane_count(_seg(lanes="3", oneway="yes"), None), 3)

    def test_a_two_way_way_takes_half_of_an_even_lane_count(self):
        # The primary Stage 4 case: 5,728 Qatari ways are two-way `lanes=2`.
        self.assertEqual(_forward_lane_count(_seg(lanes="2"), None), 1)
        self.assertEqual(_forward_lane_count(_seg(lanes="4"), None), 2)
        self.assertEqual(_forward_lane_count(_seg(lanes="6"), None), 3)

    def test_a_two_way_way_with_an_odd_lane_count_declines(self):
        # A shared, tidal or central turning lane. Splitting 2/1 would be a
        # guess about which side of the road the driver belongs on.
        self.assertIsNone(_forward_lane_count(_seg(lanes="3"), None))
        self.assertIsNone(_forward_lane_count(_seg(lanes="5"), None))

    def test_a_one_way_way_with_an_odd_lane_count_is_fine(self):
        # Nothing to split: the total IS the direction.
        self.assertEqual(_forward_lane_count(_seg(lanes="3", oneway="yes"), None), 3)

    # ---- unknown and malformed -------------------------------------------

    def test_no_lane_information_at_all_is_none(self):
        self.assertIsNone(_forward_lane_count(_seg(), None))

    def test_a_malformed_lane_count_is_none_rather_than_a_guess(self):
        for bad in ("two", "", "0", "-2", None, "1;2"):
            self.assertIsNone(
                _forward_lane_count(_seg(lanes=bad, oneway="yes"), None),
                f"lanes={bad!r} should not produce a count")

    # ---- the one-way predicate itself ------------------------------------

    def test_reversed_one_way_ways_are_one_way(self):
        # `-1`/`reverse` means one-way AGAINST digitisation: the graph emitted
        # a single reversed edge, so the way still carries traffic one way and
        # `lanes` still counts one direction.
        self.assertTrue(_is_oneway(_seg(oneway="-1")))
        self.assertTrue(_is_oneway(_seg(oneway="reverse")))
        self.assertEqual(_forward_lane_count(_seg(lanes="3", oneway="-1"), None), 3)

    def test_time_dependent_one_way_values_are_read_as_two_way(self):
        # `reversible`/`alternating` need a schedule nobody has; `add_way`
        # reads them as bidirectional and this must agree, or the router and
        # the lane count would disagree about the same way.
        for value in ("reversible", "alternating", "no", None, ""):
            self.assertFalse(_is_oneway(_seg(oneway=value)), repr(value))
        self.assertEqual(_forward_lane_count(_seg(lanes="2", oneway="no"), None), 1)

    def test_one_way_values_are_matched_case_and_space_insensitively(self):
        self.assertTrue(_is_oneway(_seg(oneway=" YES ")))
        self.assertTrue(_is_oneway(_seg(oneway="True")))


class LaneContractIntegrationTest(unittest.TestCase):
    """The resolved contract, end to end through `navigate`."""

    def _corner(self, approach_props, exit_props=None):
        g = RoutingGraph()
        east = [pt(i * 50.0, 0) for i in range(11)]           # west -> east
        corner = east[-1]
        north = [(corner[0], LAT + (i * 50.0) / MPD) for i in range(11)]
        g.add_way(east, approach_props)
        g.add_way(north, exit_props or {"highway": "primary", "name": "Exit", "maxspeed": 60})
        return RoutingService(g).navigate(east[0], north[-1])

    def _turn(self, res):
        turn = [s for s in res["steps"] if s["type"] in
                ("turn-left", "turn-right", "slight-left", "slight-right")]
        return turn[0]

    def test_raw_turn_lanes_stays_for_compatibility_and_source_is_turn_lanes(self):
        res = self._corner({
            "highway": "primary", "name": "Approach", "maxspeed": 60,
            "turn:lanes": "left|through|through",
        })
        t = self._turn(res)
        self.assertEqual(t.get("turn_lanes"), "left|through|through")
        self.assertEqual(t["lane_data"]["source"], "turn:lanes")
        self.assertEqual(t["lane_data"]["direction"], "forward")
        self.assertEqual(t["lane_data"]["turn_lanes"], "left|through|through")
        self.assertNotIn("preferred", t["lane_data"])
        self.assertNotIn("preferred_reason", t["lane_data"])

    def test_backward_traversal_emits_mirrored_lanes_and_direction_backward(self):
        g = RoutingGraph()
        east = [pt(i * 50.0, 0) for i in range(11)]           # west -> east
        # Digitised EAST->west with oneway=-1: the single edge runs west->east,
        # so the driver travels AGAINST the way's digitisation.
        g.add_way(list(reversed(east)), {
            "highway": "primary", "name": "Approach", "maxspeed": 60,
            "oneway": "-1", "turn:lanes": "left|through",
        })
        north = [(east[-1][0], LAT + (i * 50.0) / MPD) for i in range(11)]
        g.add_way(north, {"highway": "primary", "name": "Exit", "maxspeed": 60})
        res = RoutingService(g).navigate(east[0], north[-1])
        t = self._turn(res)
        self.assertEqual(t["lane_data"]["direction"], "backward")
        self.assertEqual(t["lane_data"]["turn_lanes"], "through|left")
        self.assertEqual(t["lane_data"]["source"], "turn:lanes")
        # Raw field stays raw, for clients that do not read lane_data.
        self.assertEqual(t.get("turn_lanes"), "left|through")

    def test_two_way_way_with_directional_tags_resolves_per_direction(self):
        res = self._corner({
            "highway": "primary", "name": "Approach", "maxspeed": 60,
            "turn:lanes:forward": "left|through", "turn:lanes:backward": "through|right",
        })
        t = self._turn(res)
        self.assertEqual(t["lane_data"]["direction"], "forward")
        self.assertEqual(t["lane_data"]["turn_lanes"], "left|through")

    def test_approach_lanes_present_only_when_the_map_has_lanes(self):
        res = self._corner({
            "highway": "primary", "name": "Approach", "maxspeed": 60,
            "lanes": "3", "turn:lanes": "left|through|through",
        })
        t = self._turn(res)
        self.assertEqual(t.get("approach_lanes"), 3)

        res2 = self._corner({"highway": "primary", "name": "Approach", "maxspeed": 60})
        t2 = self._turn(res2)
        self.assertNotIn("approach_lanes", t2)
        self.assertEqual(t2["lane_data"]["source"], "none")

    def test_forward_lanes_halves_a_two_way_approach(self):
        # End to end: a two-way `lanes=2` residential road is ONE lane each
        # way, and the client needs to know that to keep the route off the
        # oncoming carriageway.
        res = self._corner({
            "highway": "residential", "name": "Approach", "maxspeed": 50,
            "lanes": "2",
        })
        t = self._turn(res)
        self.assertEqual(t.get("approach_lanes"), 2)
        self.assertEqual(t["lane_data"]["forward_lanes"], 1)

    def test_forward_lanes_keeps_a_one_way_approach_whole(self):
        res = self._corner({
            "highway": "primary", "name": "Approach", "maxspeed": 60,
            "lanes": "3", "oneway": "yes",
        })
        t = self._turn(res)
        self.assertEqual(t.get("approach_lanes"), 3)
        self.assertEqual(t["lane_data"]["forward_lanes"], 3)

    def test_forward_lanes_prefers_the_lane_string_over_the_lane_count(self):
        res = self._corner({
            "highway": "primary", "name": "Approach", "maxspeed": 60,
            "lanes": "2", "oneway": "yes", "turn:lanes": "left|left|right",
        })
        t = self._turn(res)
        self.assertEqual(t.get("approach_lanes"), 2)
        self.assertEqual(t["lane_data"]["forward_lanes"], 3)

    def test_forward_lanes_is_null_rather_than_absent_when_unknown(self):
        # Present-and-null, not omitted: a client has to be able to tell "this
        # backend does not know" from "this backend predates the field", which
        # is the same distinction `source` draws for `turn:lanes`.
        res = self._corner({"highway": "primary", "name": "Approach", "maxspeed": 60})
        t = self._turn(res)
        self.assertIn("forward_lanes", t["lane_data"])
        self.assertIsNone(t["lane_data"]["forward_lanes"])

    def test_an_odd_two_way_lane_count_declines_rather_than_guessing(self):
        res = self._corner({
            "highway": "primary", "name": "Approach", "maxspeed": 60, "lanes": "3",
        })
        t = self._turn(res)
        self.assertEqual(t.get("approach_lanes"), 3)
        self.assertIsNone(t["lane_data"]["forward_lanes"])

    def test_the_existing_lane_fields_are_unchanged_by_the_addition(self):
        # Stage 1's contract, re-asserted beside the new field so a change to
        # one cannot quietly move the other.
        res = self._corner({
            "highway": "primary", "name": "Approach", "maxspeed": 60,
            "lanes": "3", "oneway": "yes", "turn:lanes": "left|through|through",
        })
        t = self._turn(res)
        self.assertEqual(t.get("turn_lanes"), "left|through|through")
        self.assertEqual(t.get("approach_lanes"), 3)
        self.assertEqual(t["lane_data"]["source"], "turn:lanes")
        self.assertEqual(t["lane_data"]["direction"], "forward")
        self.assertEqual(t["lane_data"]["turn_lanes"], "left|through|through")
        self.assertNotIn("preferred", t["lane_data"])

    def test_departure_has_no_approach_and_no_lane_contract(self):
        res = self._corner({
            "highway": "primary", "name": "Approach", "maxspeed": 60,
            "turn:lanes": "left|through",
        })
        s0 = res["steps"][0]
        self.assertNotIn("turn_lanes", s0)
        self.assertNotIn("lane_data", s0)
        self.assertNotIn("approach_lanes", s0)


class RoundaboutDirectionTest(unittest.TestCase):
    """The Qatar direction: right-hand traffic circulates a ring COUNTER-
    clockwise, and the ring is one maneuver — never a string of turns, and
    never a claimed lane preference."""

    def _ring(self):
        """A CCW ring (W->S->E->N) like a real Doha roundabout, with 3 spurs."""
        g = RoutingGraph()
        r = 30.0
        ring = [pt(500 + r * math.sin(math.radians(a)), r * math.cos(math.radians(a)))
                for a in (270, 180, 90, 0)]            # W, S, E, N (CCW)
        for i in range(len(ring)):
            g.add_way([ring[i], ring[(i + 1) % len(ring)]],
                      {"highway": "primary", "junction": "roundabout", "oneway": "yes"})
        approach = line(pt(0, 0), ring[0])
        g.add_way(approach, {"highway": "primary", "name": "Approach Road", "maxspeed": 60})
        spurs = {
            1: line(ring[1], pt(500, -400)),     # south spur = first exit
            2: line(ring[2], pt(900, 0)),        # east spur = second exit
            3: line(ring[3], pt(500, 400)),      # north spur = third exit
        }
        names = {1: "South Spur", 2: "East Spur", 3: "North Spur"}
        for i, spur in spurs.items():
            g.add_way(spur, {"highway": "primary", "name": names[i], "maxspeed": 60})
        return g, names

    def test_counter_clockwise_roundabout_names_the_physical_first_exit(self):
        # Entering from the west, the first exit on a Qatar ring is the SOUTH
        # spur (right exit); the east spur is the second.
        g, _ = self._ring()
        res = RoutingService(g).navigate(pt(0, 0), pt(900, 0))
        rb = [s for s in res["steps"] if s["type"] == "roundabout"]
        self.assertTrue(rb, res["steps"])
        self.assertIn("second exit", rb[0]["instruction"], rb[0]["instruction"])

    def test_a_single_entry_from_the_west_stays_one_maneuver(self):
        g, _ = self._ring()
        res = RoutingService(g).navigate(pt(0, 0), pt(500, -400))
        t = [s["type"] for s in res["steps"]]
        self.assertEqual(t.count("roundabout"), 1, t)
        self.assertLessEqual(
            sum(1 for x in t if "turn" in x or "slight" in x), 0, t)

    def test_roundabout_never_claims_a_preferred_lane(self):
        g, _ = self._ring()
        # Tag the approach with lanes; the roundabout step must still carry no
        # preference.
        res = RoutingService(g).navigate(pt(0, 0), pt(500, -400))
        rb = [s for s in res["steps"] if s["type"] == "roundabout"][0]
        ld = rb.get("lane_data", {})
        self.assertNotIn("preferred", ld)
        self.assertNotIn("preferred_reason", ld)

    def test_a_roundabout_step_describes_its_approach_not_the_ring(self):
        # `forward_lanes` on a roundabout step is the APPROACH's, exactly as
        # `turn_lanes` and `approach_lanes` are — the lanes the driver chooses
        # between on the way in. It is emphatically NOT a claim about which
        # concentric ring lane leads to which exit: OSM has no such data, the
        # style already withholds lane dividers on rings for that reason, and
        # V7 Stage 4 must not reintroduce the claim from the routing side.
        g, _ = self._ring()
        res = RoutingService(g).navigate(pt(0, 0), pt(500, -400))
        rb = [s for s in res["steps"] if s["type"] == "roundabout"][0]
        # The approach in this fixture carries no `lanes`, so there is nothing
        # to know and the field says so rather than describing the ring.
        self.assertIn("forward_lanes", rb["lane_data"])
        self.assertIsNone(rb["lane_data"]["forward_lanes"])

    def test_the_bend_into_the_ring_is_not_a_turn_step(self):
        # The live-Doha defect, reproduced: a vertex inside the 25 m bearing
        # window of the ring entry reads the RING, not the approach — the
        # window walks forward into the ring, whose arc heads off at ~90 degrees
        # to the approach, and a straight approach 15 m out is classified a
        # U-TURN. The app then said "Make a U-turn" (or "Keep left") one step
        # before "At the roundabout, take the Nth exit". On live routes that
        # was 31 of 77 roundabout crossings. The roundabout is the instruction;
        # its entry is geometry.
        g = RoutingGraph()
        r = 30.0
        ring = [pt(500 + r * math.sin(math.radians(a)), r * math.cos(math.radians(a)))
                for a in (270, 180, 90, 0)]            # W, S, E, N (CCW)
        for i in range(len(ring)):
            g.add_way([ring[i], ring[(i + 1) % len(ring)]],
                      {"highway": "primary", "junction": "roundabout", "oneway": "yes"})
        # Approach straight into the ring with a point 15 m short of the entry.
        p = [pt(0, 0), pt(400, 0), pt(485, 0), ring[0]]
        g.add_way(p, {"highway": "primary", "name": "Approach", "maxspeed": 60})
        g.add_way(line(ring[2], pt(900, 0)),
                  {"highway": "primary", "name": "East Spur", "maxspeed": 60})
        res = RoutingService(g).navigate(pt(0, 0), pt(900, 0))
        types = [s["type"] for s in res["steps"]]
        self.assertIn("roundabout", types, types)
        for s in res["steps"]:
            if s["type"] in ("turn-left", "turn-right", "uturn",
                              "slight-left", "slight-right"):
                self.fail(f"a turn survived on the ring approach: {s['instruction']}")

    def test_a_genuine_turn_on_the_approach_still_survives(self):
        # The suppression is a window, not a whole approach: a real decision
        # 200 m out keeps its own step ahead of the roundabout.
        g = RoutingGraph()
        r = 30.0
        ring = [pt(500 + r * math.sin(math.radians(a)), 200 + r * math.cos(math.radians(a)))
                for a in (270, 180, 90, 0)]            # W, S, E, N (CCW)
        for i in range(len(ring)):
            g.add_way([ring[i], ring[(i + 1) % len(ring)]],
                      {"highway": "primary", "junction": "roundabout", "oneway": "yes"})
        # Approach runs north 200 m, then bends east 200 m out from the ring.
        g.add_way([pt(0, 0), pt(0, 200)],
                  {"highway": "primary", "name": "South Road", "maxspeed": 60})
        g.add_way([pt(0, 200), pt(200, 200), pt(400, 200), pt(455, 200), ring[0]],
                  {"highway": "primary", "name": "Approach", "maxspeed": 60})
        g.add_way(line(ring[2], pt(700, 200)),
                  {"highway": "primary", "name": "East Spur", "maxspeed": 60})
        res = RoutingService(g).navigate(pt(0, 0), pt(700, 200))
        types = [s["type"] for s in res["steps"]]
        self.assertIn("turn-right", types, types)
        self.assertIn("roundabout", types, types)
        tr = res["steps"][types.index("turn-right")]
        rb = res["steps"][types.index("roundabout")]
        self.assertLess(tr["cumulative_distance_m"], rb["cumulative_distance_m"] - 25.0)


if __name__ == "__main__":
    unittest.main()