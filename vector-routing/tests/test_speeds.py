"""The speed model: one source of truth for cost AND ETA.

Before this module there were THREE speed derivations with different fallbacks:

    router._edge_time_weight   maxspeed or 30 km/h   <- routing cost
    router.navigate segments   maxspeed or 50 km/h   <- reported ETA
    router._compute_duration   maxspeed or 50 km/h   <- reported ETA

The router therefore optimised against one set of speeds and reported a journey
time computed from another. And because only **10% of ways in the Qatar extract
carry a usable maxspeed**, the flat 30 km/h fallback applied to 77% of trunk
roads — making the router prefer a tagged 50 km/h residential street over the
untagged trunk beside it.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src"))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "vendor"))

from vector_routing.speeds import (  # noqa: E402
    CLASS_DEFAULT_KMH, FALLBACK_KMH, SERVICE_DEFAULT_KMH, TURN_DELAY_S,
    default_kmh, effective_kmh, effective_ms, junction_delay_s, parse_maxspeed_kmh,
)
from vector_routing.router import _edge_time_weight  # noqa: E402


class ParseMaxspeedTest(unittest.TestCase):
    def test_plain_numbers(self):
        self.assertEqual(parse_maxspeed_kmh({"maxspeed": 80}), 80.0)
        self.assertEqual(parse_maxspeed_kmh({"maxspeed": "50"}), 50.0)
        self.assertEqual(parse_maxspeed_kmh({"maxspeed_kmh": 60}), 60.0)

    def test_mph_is_converted(self):
        self.assertAlmostEqual(parse_maxspeed_kmh({"maxspeed": "30 mph"}), 48.28, places=1)

    def test_kmh_suffix(self):
        self.assertEqual(parse_maxspeed_kmh({"maxspeed": "70 km/h"}), 70.0)

    def test_non_numeric_osm_values_are_unknown_not_zero(self):
        # "none" is the autobahn no-limit value. Reporting 0 would make the edge
        # cost infinite; reporting a number would be an invention.
        for v in ("none", "signals", "walk", "", "fast"):
            self.assertIsNone(parse_maxspeed_kmh({"maxspeed": v}), v)

    def test_absent_is_none(self):
        self.assertIsNone(parse_maxspeed_kmh({}))
        self.assertIsNone(parse_maxspeed_kmh({"highway": "primary"}))

    def test_nonpositive_is_rejected(self):
        self.assertIsNone(parse_maxspeed_kmh({"maxspeed": 0}))
        self.assertIsNone(parse_maxspeed_kmh({"maxspeed": -20}))

    def test_a_boolean_is_not_a_speed(self):
        # bool is an int subclass in Python; True would otherwise become 1 km/h.
        self.assertIsNone(parse_maxspeed_kmh({"maxspeed": True}))


class ClassDefaultTest(unittest.TestCase):
    def test_an_untagged_trunk_is_not_costed_as_a_back_street(self):
        """THE defect. 77% of trunk roads carry no maxspeed; a flat 30 km/h made
        the router route around them."""
        self.assertEqual(effective_kmh({"highway": "trunk"}), 80.0)
        self.assertGreater(effective_kmh({"highway": "trunk"}), FALLBACK_KMH)

    def test_class_hierarchy_is_ordered(self):
        order = ["living_street", "service", "residential", "tertiary", "primary", "motorway"]
        speeds = [effective_kmh({"highway": h}) for h in order]
        self.assertEqual(speeds, sorted(speeds), f"class speeds not monotonic: {list(zip(order, speeds))}")

    def test_explicit_maxspeed_always_beats_the_class_default(self):
        self.assertEqual(effective_kmh({"highway": "motorway", "maxspeed": "60"}), 60.0)
        self.assertEqual(effective_kmh({"highway": "residential", "maxspeed": "100"}), 100.0)

    def test_unknown_class_gets_the_conservative_fallback(self):
        self.assertEqual(effective_kmh({"highway": "spaceway"}), FALLBACK_KMH)
        self.assertEqual(effective_kmh({}), FALLBACK_KMH)

    def test_track_ignores_its_tiny_observed_sample(self):
        # The extract's median for `track` is 70 km/h from EIGHT ways. That is
        # not evidence; an unsealed desert track is not a 70 km/h road.
        self.assertEqual(CLASS_DEFAULT_KMH["track"], 30.0)

    def test_parking_aisles_are_slower_than_general_service_roads(self):
        self.assertEqual(effective_kmh({"highway": "service"}), 40.0)
        self.assertEqual(effective_kmh({"highway": "service", "service": "parking_aisle"}), 10.0)
        self.assertLess(
            effective_kmh({"highway": "service", "service": "driveway"}),
            effective_kmh({"highway": "service"}),
        )

    def test_every_default_is_a_positive_plausible_road_speed(self):
        for cls, kmh in CLASS_DEFAULT_KMH.items():
            self.assertTrue(5 <= kmh <= 130, f"{cls} = {kmh}")
        for sub, kmh in SERVICE_DEFAULT_KMH.items():
            self.assertTrue(5 <= kmh <= 50, f"{sub} = {kmh}")

    def test_effective_ms_never_returns_zero(self):
        # A zero would make an edge cost infinite and silently disconnect it.
        for props in ({}, {"maxspeed": "none"}, {"highway": "x", "maxspeed": 0}):
            self.assertGreaterEqual(effective_ms(props), 1.0)


class CostAndEtaAgreeTest(unittest.TestCase):
    """The invariant the split fallbacks broke."""

    def test_routing_cost_uses_the_same_speed_as_the_eta(self):
        for props in (
            {"highway": "trunk"},
            {"highway": "residential"},
            {"highway": "service", "service": "parking_aisle"},
            {"highway": "motorway", "maxspeed": "120"},
            {},
        ):
            cost_s = _edge_time_weight("a", "b", 1000.0, props)
            expected_s = 1000.0 / effective_ms(props)
            self.assertAlmostEqual(cost_s, expected_s, places=6, msg=str(props))

    def test_a_1km_trunk_costs_less_than_a_1km_residential(self):
        trunk = _edge_time_weight("a", "b", 1000.0, {"highway": "trunk"})
        res = _edge_time_weight("a", "b", 1000.0, {"highway": "residential"})
        self.assertLess(trunk, res, "an untagged trunk must not cost more than a residential street")


class JunctionDelayTest(unittest.TestCase):
    def test_turns_cost_time_and_continuing_does_not(self):
        self.assertEqual(junction_delay_s(["depart", "continue", "arrive"]), 0.0)
        self.assertGreater(junction_delay_s(["turn-left"]), 0.0)

    def test_a_uturn_is_the_most_expensive_maneuver(self):
        self.assertEqual(max(TURN_DELAY_S.values()), TURN_DELAY_S["uturn"])

    def test_a_left_turn_costs_more_than_a_right_turn(self):
        # It crosses opposing traffic. (Right-hand-drive assumption, documented.)
        self.assertGreater(TURN_DELAY_S["turn-left"], TURN_DELAY_S["turn-right"])

    def test_delay_scales_with_turn_count_not_distance(self):
        few = junction_delay_s(["depart", "turn-left", "arrive"])
        many = junction_delay_s(["depart"] + ["turn-left"] * 10 + ["arrive"])
        self.assertAlmostEqual(many, few * 10, places=6)

    def test_unknown_maneuver_types_are_free_rather_than_an_error(self):
        self.assertEqual(junction_delay_s(["fly", "teleport"]), 0.0)


if __name__ == "__main__":
    unittest.main()
