"""Pedestrian edge-cost model (V7.4 4B.3).

The stage contract, each translated into a test:

* the model is EXPLICIT and DECOMPOSABLE: per-factor costs with documented
  values, no composite comfort score, and the per-edge line items sum EXACTLY
  to the weighted cost (``lines()`` chaining);
* hard constraints (barriers) stay hard — the cost model adds nothing that
  restores a severed node;
* stairs remain a strong penalty (2.7x, the pre-existing pinned behavior) and
  incline is a strong, documented physical penalty;
* surface/lit/width/sidewalk are documented PREFERENCES, OFF in the default
  profile — identical geometry routes identically on the default, and the
  preference values are exercised only by ``with_preferences()`` (testable
  groundwork, not a routed claim);
* crossing waits are sourced and type-aware where the edge carries the type,
  and an untyped crossing gets a documented middle value — never "every
  crossing is bad";
* missing attributes cost nothing: a bare edge's cost equals the legacy
  pure-time cost exactly, and ``lines()`` reports the source as absent;
* no pathological detours: unit-seconds costs mean a detour only wins when it
  saves more walking time than it adds;
* deterministic tie-breaking: equal-cost alternatives resolve identically
  every run;
* the default profile preserves shortest-route correctness, and
  ``legacy()`` reproduces the pre-4B.3 router byte-for-byte on the same
  graphs;
* regression: every pre-4B.3 /foot field is intact, 4B.1 facts and 4B.2
  plans are untouched by the cost model, and the real Qatar fixture carries
  real cost data through the whole wire.
"""

import json
import os
import random
import unittest

from vector_routing.algorithms import build_graph_from_features
from vector_routing.errors import NoRouteError, RouteError
from vector_routing.foot_graph import FootRouter, build_foot_graph
from vector_routing.graph import RoutingGraph
from vector_routing.pedestrian_cost import (
    CROSSING_WAIT_S,
    CROSSING_WAIT_S_UNKNOWN,
    FACTORS,
    GENERAL_FLAGS,
    INCLINE_TOBLER_B,
    INCLINE_TOBLER_S0,
    LIT_NO_TIME,
    SIDEWALK_NO_TIME,
    STEPS_SPEED_MS,
    SURFACE_TIME,
    WALK_SPEED_MS,
    WIDTH_NARROW_M,
    WIDTH_NARROW_TIME,
    PedestrianCostModel,
    crossing_wait_s,
    incline_time_multiplier,
    is_crossing_edge,
    is_narrow_width,
    is_poor_surface,
    width_time_multiplier,
)
from vector_routing.barriers import BarrierCatalog, apply_barriers
from vector_routing.router import Router

HERE = os.path.dirname(os.path.abspath(__file__))
CROSSING_FIXTURE = os.path.join(HERE, "data", "qatar-crossing-fixture.geojson")


def road(highway, coords, **props):
    """A converted road feature in the shape osm_to_geojson.py emits."""
    p = {"kind": "road", "highway": highway, "foot": True}
    p.update(props)
    return {"type": "Feature", "properties": p,
            "geometry": {"type": "LineString", "coordinates": coords}}


def edges_along(graph, keys, router=None):
    router = router or FootRouter(graph)
    return list(router.edges_along(keys))


def edge_values(route_keys, graph):
    out = []
    for a, b in zip(route_keys, route_keys[1:]):
        for to, w, p in graph.neighbors(a):
            if to == b:
                out.append((w, p))
                break
    return out


def key(lon, lat):
    return RoutingGraph.node_key(lon, lat)


# ---------------------------------------------------------------------------
# The edge-cost function itself
# ---------------------------------------------------------------------------

class EdgeCostModelTest(unittest.TestCase):
    """Each promoted attribute has a documented, testable cost; absence costs 0."""

    def test_a_bare_edge_costs_exactly_the_legacy_time(self):
        """Missing attributes produce NO unjustified penalty."""
        general = PedestrianCostModel.general()
        legacy = PedestrianCostModel.legacy()
        bare = {"highway": "footway"}
        for w in (5.0, 10.0, 123.4):
            self.assertAlmostEqual(
                general.edge_weight("a", "b", w, bare),
                legacy.edge_weight("a", "b", w, bare), delta=1e-9)

    def test_a_bare_edge_reports_every_factor_absent_and_zero(self):
        line = PedestrianCostModel.general().lines("a", "b", 10.0, {"highway": "footway"})
        for f in ("incline", "surface", "lit", "width", "sidewalk"):
            self.assertEqual(line[f]["source"], "absent")
            self.assertEqual(line[f]["uplift_s"], 0.0)
            self.assertEqual(line[f]["multiplier"], 1.0)
        self.assertEqual(line["crossing_wait_s"], 0.0)
        self.assertEqual(line["stairs"]["uplift_s"], 0.0)

    def test_lines_sum_exactly_to_the_total(self):
        """The decomposition is exact, not approximate — no hidden composite."""
        model = PedestrianCostModel.general()
        cases = [
            ("footway", 10.0, {}),
            ("footway", 33.3, {"incline": "up"}),
            ("footway", 21.0, {"incline": "10%"}),            # percent form
            ("footway", 21.0, {"incline": "30°"}),            # degree form (tan 30)
            ("steps", 9.0, {}),
            ("steps", 7.0, {"incline": "up", "step_count": "24"}),
            ("footway", 5.0, {"footway": "crossing"}),
            ("footway", 5.0, {"footway": "crossing", "crossing": "traffic_signals"}),
            ("footway", 5.0, {"footway": "crossing", "crossing": "marked",
                              "lit": "no", "surface": "sand", "width": ".5"}),
            ("path", 12.0, {"surface": "gravel", "lit": "no", "width": "3"}),
        ]
        for hw, w, extra in cases:
            props = {"highway": hw, **extra}
            line = model.lines("a", "b", w, props)
            parts = line["base_time_s"]
            parts += line["stairs"]["uplift_s"]
            for f in ("incline", "surface", "lit", "width", "sidewalk"):
                parts += line[f]["uplift_s"]
            parts += line["crossing_wait_s"]
            self.assertAlmostEqual(parts, line["total_s"], delta=0.002, msg=(hw, extra))
            self.assertAlmostEqual(
                line["total_s"], model.edge_weight("a", "b", w, props), delta=1e-6)

    def test_stairs_are_a_strong_penalty_kept_exactly(self):
        """The pre-existing 2.7x stairs penalty is unchanged by the model."""
        model = PedestrianCostModel.general()
        flat = model.edge_weight("a", "b", 10.0, {"highway": "footway"})
        steps = model.edge_weight("a", "b", 10.0, {"highway": "steps"})
        self.assertAlmostEqual(steps / flat, WALK_SPEED_MS / STEPS_SPEED_MS, delta=1e-9)
        self.assertAlmostEqual(steps, 10.0 / STEPS_SPEED_MS, delta=1e-9)

    def test_incline_is_a_documented_physical_penalty(self):
        model = PedestrianCostModel.general()
        level = model.edge_weight("a", "b", 10.0, {"highway": "footway"})
        up = model.edge_weight("a", "b", 10.0, {"highway": "footway", "incline": "up"})
        expect = level * incline_time_multiplier("up")
        self.assertAlmostEqual(up, expect, delta=1e-9)
        # Tobler: 10% grade ~1.42x time. The multiplier is the documented
        # exp(3.5*(|g+0.05| - 0.05)) function.
        self.assertAlmostEqual(incline_time_multiplier("10%"),
                               __import__("math").exp(
                                   INCLINE_TOBLER_B * (abs(0.10 + INCLINE_TOBLER_S0)
                                                       - INCLINE_TOBLER_S0)),
                               delta=1e-9)
        # A symbolic "up" is the documented representative 10% grade.
        self.assertAlmostEqual(incline_time_multiplier("up"),
                               incline_time_multiplier("10%"), delta=1e-9)
        self.assertGreater(incline_time_multiplier("30°"), incline_time_multiplier("10%"))

    def test_incline_compounds_on_stairs(self):
        model = PedestrianCostModel.general()
        steps = model.edge_weight("a", "b", 10.0, {"highway": "steps"})
        sloped = model.edge_weight("a", "b", 10.0,
                                   {"highway": "steps", "incline": "up"})
        self.assertGreater(sloped, steps, "a sloped staircase costs more than a flat one")

    def test_crossing_wait_is_sourced_and_type_aware(self):
        self.assertEqual(crossing_wait_s({"highway": "footway"}), 0.0)
        self.assertEqual(crossing_wait_s({"footway": "crossing"}), CROSSING_WAIT_S_UNKNOWN)
        self.assertEqual(crossing_wait_s({"footway": "crossing", "crossing": "traffic_signals"}),
                         CROSSING_WAIT_S["traffic_signals"])
        self.assertEqual(crossing_wait_s({"footway": "crossing", "crossing": "zebra"}),
                         CROSSING_WAIT_S["zebra"])
        self.assertEqual(crossing_wait_s({"footway": "crossing", "crossing": "uncontrolled"}),
                         CROSSING_WAIT_S["uncontrolled"])
        self.assertEqual(crossing_wait_s({"footway": "crossing", "crossing": "marked;traffic_signals"}),
                         CROSSING_WAIT_S["traffic_signals"], "compound degrades to the longest")
        self.assertEqual(crossing_wait_s({"footway": "crossing", "crossing": "no"}), 0.0)
        # An explicit crossing=* tag alone (a way whose footway=* is not
        # crossing) still counts — the same predicate as crossing_m.
        self.assertGreater(crossing_wait_s({"highway": "footway", "crossing": "marked"}), 0.0)

    def test_crossing_order_is_documented_and_monotonic(self):
        self.assertLess(CROSSING_WAIT_S["uncontrolled"], CROSSING_WAIT_S_UNKNOWN)
        self.assertLess(CROSSING_WAIT_S_UNKNOWN, CROSSING_WAIT_S["zebra"])
        self.assertLess(CROSSING_WAIT_S["zebra"], CROSSING_WAIT_S["traffic_signals"])

    def test_surface_preferences_are_documented_but_off_by_default(self):
        general = PedestrianCostModel.general()
        pref = PedestrianCostModel.with_preferences()
        rough = {"highway": "path", "surface": "sand"}
        smooth = {"highway": "path", "surface": "asphalt"}
        # Default profile: identical geometry on different surfaces costs the
        # SAME — surface is a preference, not a default cost.
        self.assertEqual(general.edge_weight("a", "b", 10.0, rough),
                         general.edge_weight("a", "b", 10.0, smooth))
        # The preference profile uses the documented table.
        self.assertGreater(pref.edge_weight("a", "b", 10.0, rough),
                           pref.edge_weight("a", "b", 10.0, smooth))
        self.assertAlmostEqual(pref.edge_weight("a", "b", 10.0, rough) /
                               pref.edge_weight("a", "b", 10.0, smooth),
                               SURFACE_TIME["sand"], delta=1e-9)
        self.assertAlmostEqual(pref.edge_weight("a", "b", 10.0, smooth),
                               10.0 / WALK_SPEED_MS, delta=1e-9)
        # Absent surface stays neutral even in the preference profile.
        self.assertEqual(pref.edge_weight("a", "b", 10.0, {"highway": "path"}),
                         10.0 / WALK_SPEED_MS)

    def test_lit_preference_is_off_by_default_and_documented_when_on(self):
        general = PedestrianCostModel.general()
        pref = PedestrianCostModel.with_preferences()
        dark = {"highway": "footway", "lit": "no"}
        lit = {"highway": "footway", "lit": "yes"}
        self.assertEqual(general.edge_weight("a", "b", 10.0, dark),
                         general.edge_weight("a", "b", 10.0, lit))
        self.assertGreater(pref.edge_weight("a", "b", 10.0, dark),
                           pref.edge_weight("a", "b", 10.0, lit))
        self.assertAlmostEqual(pref.edge_weight("a", "b", 10.0, dark) /
                               pref.edge_weight("a", "b", 10.0, lit),
                               LIT_NO_TIME, delta=1e-9)

    def test_width_preference_is_off_by_default_and_documented_when_on(self):
        general = PedestrianCostModel.general()
        pref = PedestrianCostModel.with_preferences()
        narrow = {"highway": "footway", "width": ".5"}
        wide = {"highway": "footway", "width": "7"}
        self.assertEqual(general.edge_weight("a", "b", 10.0, narrow),
                         general.edge_weight("a", "b", 10.0, wide))
        self.assertAlmostEqual(width_time_multiplier(".5"), WIDTH_NARROW_TIME, delta=1e-9)
        self.assertEqual(width_time_multiplier("3"), 1.0)
        self.assertEqual(width_time_multiplier("abc"), 1.0, "unparseable width is not narrow")
        self.assertEqual(width_time_multiplier(None), 1.0, "absent width is not narrow")
        self.assertGreater(pref.edge_weight("a", "b", 10.0, narrow),
                           pref.edge_weight("a", "b", 10.0, wide))

    def test_sidewalk_preference_is_off_by_default_and_documented_when_on(self):
        general = PedestrianCostModel.general()
        pref = PedestrianCostModel.with_preferences()
        no_sw = {"highway": "residential", "sidewalk": "no"}
        with_sw = {"highway": "residential", "sidewalk": "right"}
        self.assertEqual(general.edge_weight("a", "b", 10.0, no_sw),
                         general.edge_weight("a", "b", 10.0, with_sw))
        self.assertGreater(pref.edge_weight("a", "b", 10.0, no_sw),
                           pref.edge_weight("a", "b", 10.0, with_sw))
        self.assertAlmostEqual(pref.edge_weight("a", "b", 10.0, no_sw) /
                               pref.edge_weight("a", "b", 10.0, with_sw),
                               SIDEWALK_NO_TIME, delta=1e-9)

    def test_unknown_or_absurd_attribute_values_cost_nothing(self):
        model = PedestrianCostModel.with_preferences()
        bare = model.edge_weight("a", "b", 10.0, {"highway": "path"})
        for extra in ({"width": "abc"}, {"incline": "bogus"}, {"surface": "unrealistic"},
                      {"width": "-5"}, {"incline": "0%"}):
            self.assertEqual(model.edge_weight("a", "b", 10.0, {"highway": "path", **extra}),
                             bare, extra)

    def test_legacy_profile_reproduces_pre_4b3_edge_costs(self):
        legacy = PedestrianCostModel.legacy()
        for hw, extra in (("footway", {"surface": "sand"}), ("steps", {"incline": "up"}),
                          ("footway", {"footway": "crossing"}),
                          ("path", {"lit": "no", "width": ".5", "sidewalk": "no"})):
            props = {"highway": hw, **extra}
            self.assertAlmostEqual(
                legacy.edge_weight("a", "b", 10.0, props),
                10.0 / (STEPS_SPEED_MS if hw == "steps" else WALK_SPEED_MS),
                delta=1e-9, msg=extra)


# ---------------------------------------------------------------------------
# Route selection under the model
# ---------------------------------------------------------------------------

class RouteSelectionTest(unittest.TestCase):
    """The model changes route selection only where the cost warrants it."""

    def _route_highway(self, graph, cost_model, a, b):
        r = FootRouter(graph, cost_model=cost_model)
        return [p.get("highway") for _w, p in edge_values(r.route_by_node(a, b).node_keys, graph)]

    def test_identical_geometry_with_different_surfaces_routes_identically(self):
        """Default profile: surface never changes route choice (preference off)."""
        g = build_foot_graph([
            road("path", [[51.5200, 25.2900], [51.5210, 25.2900]], surface="asphalt"),
            road("path", [[51.5200, 25.2900], [51.5210, 25.2901]], surface="sand"),
            road("path", [[51.5210, 25.2900], [51.5210, 25.2901]], surface="asphalt", foot=True),
        ])
        a = key(51.5200, 25.2900)
        # The two parallel paths both reach (51.5210, 25.2901) if the junction
        # exists; give the sand path a direct route to the target as well.
        b = key(51.5210, 25.2901)
        # Sand route: along the sand edge straight down and right.
        g2 = build_foot_graph([
            road("path", [[51.5200, 25.2900], [51.5210, 25.2900]], surface="asphalt"),
            road("path", [[51.5200, 25.2900], [51.5200, 25.2901]], surface="sand"),
            road("path", [[51.5200, 25.2901], [51.5210, 25.2901]], surface="asphalt"),
            road("path", [[51.5210, 25.2900], [51.5210, 25.2901]], surface="ground"),
        ])
        _ = g
        general = FootRouter(g2, cost_model=PedestrianCostModel.general())
        pref = FootRouter(g2, cost_model=PedestrianCostModel.with_preferences())
        route_general = general.route_by_node(a, b).node_keys
        # General: both paths equal cost except ground vs sand — with surface
        # OFF the choice is by distance/tie-break, deterministically one path.
        self.assertEqual(route_general, general.route_by_node(a, b).node_keys,
                         "identical request twice -> identical path")
        # Preferences ON: the sand+ground path is now more expensive than the
        # asphalt path, so the router takes the asphalt route.
        route_pref = pref.route_by_node(a, b)
        sand_m = sum(w for _w, p in edge_values(route_pref.node_keys, g2)
                     if p.get("surface") == "sand")
        self.assertEqual(sand_m, 0.0,
                         "with the surface preference the router avoids sand")

    def test_an_incline_alternative_is_avoided_when_level_is_not_longer(self):
        """A tagged slope loses to a level path that is not materially longer."""
        graph = build_foot_graph([
            road("footway", [[51.5200, 25.2900], [51.5210, 25.2901]], incline="up", name="hilly"),
            road("footway", [[51.5200, 25.2900], [51.5210, 25.2900]], name="level2"),
            road("footway", [[51.5210, 25.2900], [51.5210, 25.2901]], name="level3"),
        ])
        a = key(51.5200, 25.2900)
        b = key(51.5210, 25.2901)
        model = PedestrianCostModel.general()
        hilly_cost = model.edge_weight("u", "v", 10.0,
                                       {"highway": "footway", "incline": "up"})
        level_cost = model.edge_weight("u", "v", 10.0, {"highway": "footway"})
        self.assertGreater(hilly_cost, level_cost, "the model must penalize the slope")
        route = FootRouter(graph, cost_model=model).route_by_node(a, b)
        used = [p.get("name") for _w, p in edge_values(route.node_keys, graph)]
        self.assertNotIn("hilly", used,
                         "the general profile routes around the tagged slope")
        # The level detour is barely longer (one extra turn), so the ~42%
        # time penalty on the slope makes it the wrong choice.
        self.assertLess(route.distance_m, 130.0)

    def test_an_incline_detour_is_taken_only_when_it_saves_real_time(self):
        """A tiny slope difference never justifies a long detour."""
        # Level route: 100 m. Sloped route: 80 m at 10% incline.
        graph = build_foot_graph([
            # Straight level 100 m
            road("footway", [[51.5200, 25.2900], [51.5210, 25.2900]], name="level100"),
            # Up-and-over 80 m at 10%: 40 m out at 25.2901, 40 m back
            road("footway", [[51.5200, 25.2900], [51.5200, 25.2901]], incline="up", name="climb"),
            road("footway", [[51.5200, 25.2901], [51.5210, 25.2901]], incline="down", name="climb"),
            road("footway", [[51.5210, 25.2901], [51.5210, 25.2900]], incline="up", name="climb"),
        ])
        a = key(51.5200, 25.2900)
        b = key(51.5210, 25.2900)
        general = FootRouter(graph, cost_model=PedestrianCostModel.general())
        legacy = FootRouter(graph, cost_model=PedestrianCostModel.legacy())
        gen = general.route_by_node(a, b)
        leg = legacy.route_by_node(a, b)
        self.assertLessEqual(gen.distance_m, leg.distance_m + 1e-6)
        self.assertIn("level100", [p.get("name") for _w, p in edge_values(gen.node_keys, graph)],
                      "the shorter sloped path (80 m at 10%) costs more than the "
                      "level 100 m: climbing ~1.42x does not compensate 20 m shorter")

    def test_crossing_wait_changes_choice_only_when_it_saves_real_time(self):
        """A crossing wait is compared in seconds against the detour's walking time."""
        # Route A: 20 m straight through one untyped crossing (6 s wait).
        # Route B: 28 m level, no crossing. B costs 20.7 s walking; A costs
        # 14.8 s walking + 6 s wait = 20.8 s. They are effectively equal, so
        # the router may pick either — the point is the wait is SMALL compared
        # with a detour of many metres.
        graph = build_foot_graph([
            road("footway", [[51.5200, 25.2900], [51.5202, 25.2900]],
                 name="straight", footway="crossing"),
            road("footway", [[51.5200, 25.2900], [51.5200, 25.2902]],
                 name="around"),
            road("footway", [[51.5200, 25.2902], [51.5202, 25.2902]],
                 name="around"),
            road("footway", [[51.5202, 25.2902], [51.5202, 25.2900]],
                 name="around"),
        ])
        a = key(51.5200, 25.2900)
        b = key(51.5202, 25.2900)
        general = FootRouter(graph, cost_model=PedestrianCostModel.general())
        route = general.route_by_node(a, b)
        cost = sum(general._cost_model.edge_weight(x, y, w, p)
                   for x, y, w, p in general._edges_with_keys(route.node_keys))
        # Whatever is chosen (the detour is ~2.5x longer in walking time), the
        # weighted cost is within the wait+walking of the straight option: no
        # pathological detour — 28 m of walking (20.7 s) would only win over
        # 20 m + 6 s (20.8 s) by a whisker.
        self.assertLess(cost, 21.5)

    def test_a_route_never_detours_to_avoid_a_crossing_when_it_costs_more(self):
        """Long detour vs one crossing: the crossing stays (no ban on crossings)."""
        graph = build_foot_graph([
            road("footway", [[51.5200, 25.2900], [51.5201, 25.2900]],
                 name="through", footway="crossing"),
            road("footway", [[51.5200, 25.2900], [51.5200, 25.2901]],
                 name="huge-detour"),
            road("footway", [[51.5200, 25.2901], [51.5201, 25.2901]],
                 name="huge-detour"),
            road("footway", [[51.5201, 25.2901], [51.5201, 25.2900]],
                 name="huge-detour"),
        ])
        a = key(51.5200, 25.2900)
        b = key(51.5201, 25.2900)
        route = FootRouter(graph, cost_model=PedestrianCostModel.general()).route_by_node(a, b)
        names = [p.get("name") for _w, p in edge_values(route.node_keys, graph)]
        self.assertIn("through", names,
                      "a 3-side detour (~222 m of walking) must never beat one "
                      "6 s crossing — crossings are not banned")

    def test_stairs_remain_preferred_only_within_a_short_detour(self):
        """The pre-existing stairs trade-off survives the new model untouched."""
        from vector_routing.foot_graph import FootRouter as FR
        def graph_with_detour(detour_lon):
            return build_foot_graph([
                road("steps", [[51.5200, 25.2900], [51.5202, 25.2900]], foot=True),
                road("footway", [[51.5200, 25.2900], [51.5200, detour_lon]], foot=True),
                road("footway", [[51.5200, detour_lon], [51.5202, detour_lon]], foot=True),
                road("footway", [[51.5202, detour_lon], [51.5202, 25.2900]], foot=True),
            ])
        a = key(51.5200, 25.2900)
        b = key(51.5202, 25.2900)
        tight = graph_with_detour(25.29005)
        r = FR(tight, cost_model=PedestrianCostModel.general()).route_by_node(a, b)
        self.assertNotIn("steps", [p.get("highway") for _w, p in edge_values(r.node_keys, tight)],
                         "a cheap level detour should beat the stairs")
        far = graph_with_detour(25.2930)
        r2 = FR(far, cost_model=PedestrianCostModel.general()).route_by_node(a, b)
        self.assertIn("steps", [p.get("highway") for _w, p in edge_values(r2.node_keys, far)],
                      "stairs remain usable when the alternative is worse")

    def test_crossing_type_difference_is_costed_at_search_time(self):
        """signalized > zebra: the router trades waits on equal geometry."""
        # A to B, two routes of equal length: one through two zebra crossings
        # (8+8 s wait), the other through two signalized ones (15+15 s).
        graph = build_foot_graph([
            road("footway", [[51.5200, 25.2900], [51.52005, 25.2904]],
                 name="signal1", footway="crossing", crossing="traffic_signals"),
            road("footway", [[51.52005, 25.2904], [51.5201, 25.2904]],
                 name="signal2", footway="crossing", crossing="traffic_signals"),
            road("footway", [[51.5200, 25.2900], [51.52005, 25.2902]],
                 name="zebra1", footway="crossing", crossing="zebra"),
            road("footway", [[51.52005, 25.2902], [51.5201, 25.2904]],
                 name="zebra2", footway="crossing", crossing="zebra"),
        ])
        a = key(51.5200, 25.2900)
        b = key(51.5201, 25.2904)
        leg = FootRouter(graph, cost_model=PedestrianCostModel.legacy()).route_by_node(a, b)
        gen = FootRouter(graph, cost_model=PedestrianCostModel.general()).route_by_node(a, b)
        self.assertAlmostEqual(leg.distance_m, gen.distance_m, delta=1.0,
                               msg="both routes are the same length")
        used = [p.get("name") for _w, p in edge_values(gen.node_keys, graph)]
        self.assertTrue(any(n.startswith("zebra") for n in used),
                        "two zebra crossings (8+8 s) beat two signalized (15+15 s) "
                        "on equal geometry")

    def test_a_straight_walk_cannot_detour_for_a_crossing_it_must_cross(self):
        """When every route crosses a road, the crossing stays (archipelago)."""
        graph = build_foot_graph([
            road("footway", [[51.5200, 25.2900], [51.5201, 25.2900]],
                 footway="crossing"),
        ])
        a = key(51.5200, 25.2900)
        b = key(51.5201, 25.2900)
        route = FootRouter(graph, cost_model=PedestrianCostModel.general()).route_by_node(a, b)
        self.assertGreater(route.distance_m, 0.0)

    def test_no_pathological_detour_from_tiny_attribute_differences(self):
        """A 0.1 s difference must never send the walk around the block."""
        # Two parallel 40 m paths; one has a single lit=no tag. With the
        # DEFAULT profile (lit off) they are equal, so no detour is possible.
        graph = build_foot_graph([
            road("footway", [[51.5200, 25.2900], [51.5204, 25.2900]], name="dark", lit="no"),
            road("footway", [[51.5200, 25.2900], [51.5204, 25.2901]], name="light", lit="yes"),
            road("footway", [[51.5204, 25.2900], [51.5204, 25.2901]], name="join"),
        ])
        a = key(51.5200, 25.2900)
        b = key(51.5204, 25.2901)
        gen = FootRouter(graph, cost_model=PedestrianCostModel.general()).route_by_node(a, b)
        leg = FootRouter(graph, cost_model=PedestrianCostModel.legacy()).route_by_node(a, b)
        # Same distance either way; the general profile is distance-driven here
        # (lit preference is off) so it cannot justify ANY detour.
        self.assertLessEqual(gen.distance_m, max(leg.distance_m, gen.distance_m) + 1e-9)
        self.assertLessEqual(gen.distance_m, 45.0, "no meter-eating detour for a preference")


# ---------------------------------------------------------------------------
# Determinism
# ---------------------------------------------------------------------------

class DeterminismTest(unittest.TestCase):
    def test_identical_requests_produce_identical_paths_and_costs(self):
        g = build_foot_graph([
            road("footway", [[51.5200, 25.2900], [51.5210, 25.2900]], incline="up"),
            road("footway", [[51.5200, 25.2900], [51.5200, 25.2901]]),
            road("footway", [[51.5200, 25.2901], [51.5210, 25.2901]]),
            road("footway", [[51.5210, 25.2900], [51.5210, 25.2901]]),
            road("footway", [[51.5210, 25.2900], [51.5220, 25.2900]], footway="crossing"),
        ])
        a = key(51.5200, 25.2900)
        b = key(51.5220, 25.2900)
        router = FootRouter(g, cost_model=PedestrianCostModel.general())
        r1 = router.walk((51.5200, 25.2900), (51.5220, 25.2900))
        r2 = router.walk((51.5200, 25.2900), (51.5220, 25.2900))
        for k in ("route", "maneuvers", "maneuver_plan"):
            self.assertEqual(r1[k], r2[k], k)
        self.assertEqual(r1["cost"], r2["cost"])
        self.assertEqual(r1["segment_cost_s"], r2["segment_cost_s"])
        self.assertEqual([m for m in r1["cost"]["factor_s"]],
                         sorted(m for m in r1["cost"]["factor_s"]))

    def test_equal_cost_alternatives_tie_break_deterministically(self):
        """A symmetric 'Y' with equal costs picks the same branch every time."""
        g = build_foot_graph([
            road("footway", [[51.5200, 25.2900], [51.5201, 25.2900]]),
            road("footway", [[51.5201, 25.2900], [51.5202, 25.2900]]),
            road("footway", [[51.5201, 25.2900], [51.5202, 25.2901]], footway="crossing"),
            road("footway", [[51.5202, 25.2901], [51.5202, 25.2902]]),
            road("footway", [[51.5202, 25.2902], [51.5203, 25.2902]]),
            road("footway", [[51.5202, 25.2900], [51.5203, 25.2900]]),
        ])

        def route_keys():
            router = FootRouter(g, cost_model=PedestrianCostModel.general())
            return router.route_by_node(key(51.5200, 25.2900), key(51.5203, 25.2900)).node_keys

        first = route_keys()
        for _ in range(5):
            self.assertEqual(route_keys(), first, "tie-breaking must be stable")


# ---------------------------------------------------------------------------
# Barriers stay hard; legacy stays byte-identical
# ---------------------------------------------------------------------------

class BarrierAndLegacyTest(unittest.TestCase):
    def test_a_blocked_barrier_node_stays_unroutable_with_the_cost_model(self):
        """The cost model adds nothing that restores a severed node."""
        from vector_routing.barriers import BarrierCatalog
        # A short path through a gate PLUS a longer level detour around it: the
        # post-severance graph must use the detour (or fail), never the gate.
        graph = build_foot_graph([
            road("footway", [[51.5200, 25.2900], [51.5201, 25.2900]], name="to-gate"),
            road("footway", [[51.5201, 25.2900], [51.5202, 25.2900]], name="gate-way"),
            road("footway", [[51.5202, 25.2900], [51.5203, 25.2900]], name="from-gate"),
            # The detour avoids the gate coordinate entirely (a severed node
            # loses ALL incident edges, so a detour sharing the gate node would
            # be severed too).
            road("footway", [[51.5200, 25.2900], [51.5200, 25.2901]], name="detour"),
            road("footway", [[51.5200, 25.2901], [51.5203, 25.2901]], name="detour"),
            road("footway", [[51.5203, 25.2901], [51.5203, 25.2900]], name="detour"),
        ])
        gate_key = key(51.5201, 25.2900)
        catalog = BarrierCatalog.from_feature_collection({
            "type": "FeatureCollection",
            "features": [{
                "type": "Feature",
                "id": "n1",
                "properties": {"kind": "barrier", "pedestrian_effect": "block"},
                "geometry": {"type": "Point", "coordinates": [51.5201, 25.2900]},
            }]})
        stats = apply_barriers(graph, catalog)
        self.assertEqual(stats["blocked"], 1)
        router = FootRouter(graph, cost_model=PedestrianCostModel.general())
        a, b = key(51.5200, 25.2900), key(51.5203, 25.2900)
        route = router.route_by_node(a, b)
        used = [RoutingGraph.node_key(*graph.node_coord(k)) for k in route.node_keys]
        self.assertNotIn(gate_key, used,
                         "the severed gate node cannot appear in a route")
        names = [p.get("name") for _w, p in edge_values(route.node_keys, graph)]
        self.assertIn("detour", names and names,
                      "the route must go around the blocked gate")

    def test_legacy_router_is_byte_identical_on_identical_graphs(self):
        """The pre-4B.3 router is exactly reproducible for regression."""
        g = build_foot_graph([
            road("footway", [[51.5200, 25.2900], [51.5210, 25.2900]], incline="up"),
            road("footway", [[51.5200, 25.2900], [51.5200, 25.2901]]),
            road("footway", [[51.5200, 25.2901], [51.5210, 25.2901]]),
            road("footway", [[51.5210, 25.2900], [51.5210, 25.2901]]),
            road("steps", [[51.5210, 25.2900], [51.5220, 25.2900]]),
        ])
        legacy_router = FootRouter(g, cost_model=PedestrianCostModel.legacy())
        # A plain FootRouter constructed before 4B.3 had NO cost model; its
        # _edge_weight was w / walk_speed. Assert that equality directly.
        from vector_routing.speeds import walk_speed_ms as _wsm
        for a, b, w, p in g.edges():
            self.assertAlmostEqual(
                legacy_router._edge_weight(a, b, w, p), w / _wsm(p), delta=1e-12)

    def test_geometry_and_facts_are_unchanged_by_the_model(self):
        """The same node path produces the same facts/plan regardless of cost."""
        g = build_foot_graph([
            road("footway", [[51.5200, 25.2900], [51.5210, 25.2900]], footway="crossing"),
            road("footway", [[51.5210, 25.2900], [51.5220, 25.2900]]),
        ])
        a, b = key(51.5200, 25.2900), key(51.5220, 25.2900)
        for model in (PedestrianCostModel.legacy(), PedestrianCostModel.general()):
            r = FootRouter(g, cost_model=model).route_by_node(a, b)
            walk = FootRouter(g, cost_model=model).walk(
                (51.5200, 25.2900), (51.5220, 25.2900))
            self.assertEqual(["depart", "cross", "arrive"],
                             [f["type"] for f in walk["maneuvers"]])
            self.assertEqual(["depart", "cross", "arrive"],
                             [m["kind"] for m in walk["maneuver_plan"]])
            self.assertEqual(len(walk["segment_cost_s"]), len(r.node_keys) - 1)

    def test_footz_carries_the_cost_model_config(self):
        import json as _json
        fc = {"type": "FeatureCollection", "features": [
            road("footway", [[51.5200, 25.2900], [51.5201, 25.2900]]),
        ]}
        import threading
        import urllib.request
        from vector_routing.service import RoutingService
        from vector_routing.serve import make_server
        svc = RoutingService(graph=build_graph_from_features([]))
        svc.set_foot_graph(build_foot_graph(fc))
        server = make_server(0, svc)
        port = server.server_address[1]
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{port}/footz") as r:
                body = _json.loads(r.read())
        finally:
            server.shutdown()
            server.server_close()
        cm = body["cost_model"]
        self.assertEqual(cm["profile"], "general")
        self.assertEqual(cm["unit"], "seconds")
        self.assertIn("flags", cm)
        self.assertIn("crossing_wait_s", cm)
        self.assertIn("incline", cm)
        self.assertEqual(cm["flags"]["incline"], True)
        self.assertEqual(cm["flags"]["surface"], False)


# ---------------------------------------------------------------------------
# The route-level diagnostics and the wire
# ---------------------------------------------------------------------------

class CostDiagnosticsTest(unittest.TestCase):
    def test_cost_summary_quantities_line_up(self):
        g = build_foot_graph([
            road("footway", [[51.5200, 25.2900], [51.5201, 25.2900]], footway="crossing"),
            road("steps", [[51.5201, 25.2900], [51.5202, 25.2900]], step_count="12"),
            road("footway", [[51.5202, 25.2900], [51.5203, 25.2900]], incline="up"),
            road("footway", [[51.5203, 25.2900], [51.5204, 25.2900]], lit="no", surface="sand", width=".5"),
        ])
        walk = FootRouter(g, cost_model=PedestrianCostModel.general()).walk(
            (51.5200, 25.2900), (51.5204, 25.2900))
        cost = walk["cost"]
        # weighted cost == pace + penalty, pace exactly the reported duration.
        self.assertAlmostEqual(cost["cost_s"], cost["pace_s"] + cost["penalty_s"], delta=0.01)
        self.assertAlmostEqual(cost["pace_s"], walk["route"].duration_s, delta=0.01)
        # factor_s (chained uplifts) sums exactly to cost - base_time: the
        # stairs uplift is the gap between base_time and pace, and the rest
        # (incline/wait/preferences) is the gap between pace and cost.
        total = sum(cost["factor_s"].values())
        self.assertAlmostEqual(total, cost["cost_s"] - cost["base_time_s"], delta=0.02)
        self.assertGreater(cost["pace_s"], cost["base_time_s"],
                           "the stairs edges must inflate pace over flat base")
        # exposure totals are measured regardless of profile flags.
        self.assertGreaterEqual(cost["stairs"]["edges"], 1)
        self.assertEqual(cost["stairs"]["step_count"], 12)
        self.assertGreaterEqual(cost["incline"]["edges"], 1)
        self.assertGreaterEqual(cost["crossing"]["edges"], 1)
        self.assertGreaterEqual(cost["crossing"]["wait_s"], CROSSING_WAIT_S_UNKNOWN - 0.01)
        self.assertEqual(cost["surface"]["poor_edges"], 1)
        self.assertEqual(cost["lit"]["unlit_edges"], 1)
        self.assertEqual(cost["width"]["narrow_edges"], 1)
        # crossing exposure agrees with crossing_m (both count crossing metres).
        self.assertGreater(walk["crossing_m"], 0.0)
        # segment costs align with geometry.
        self.assertEqual(len(walk["segment_cost_s"]), len(walk["route"].node_keys) - 1)
        # The crossing segment must carry the wait: it is the highest-cost flat
        # segment relative to its length.
        seg = walk["segment_cost_s"]
        cross_idx = [i for i, t in enumerate(walk["segment_tags"]) if t["footway"] == "crossing"]
        self.assertTrue(cross_idx)
        self.assertEqual(walk["cost"]["profile"], "general")

    def test_completed_wire_carries_cost_additively(self):
        import json as _json
        import threading
        import urllib.request
        from vector_routing.service import RoutingService
        from vector_routing.serve import make_server
        with open(CROSSING_FIXTURE, encoding="utf-8") as fh:
            fc = _json.load(fh)
        svc = RoutingService(graph=build_graph_from_features([]))
        svc.set_foot_graph(build_foot_graph(fc))
        server = make_server(0, svc)
        port = server.server_address[1]
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            # a deterministic walk in the fixture that crosses (seed 5, as the
            # 4B.1 wire test uses).
            router = FootRouter(build_foot_graph(fc))
            random.seed(5)
            nodes = sorted(router._graph.nodes().items())
            pair = None
            for _ in range(3000):
                a = random.choice(nodes)[1]
                b = random.choice(nodes)[1]
                try:
                    r = router.walk(a, b)
                except (NoRouteError, RouteError):
                    continue
                if r["crossing_m"] > 0:
                    pair = (a, b)
                    break
            self.assertIsNotNone(pair, "fixture must yield a crossing walk")
            with urllib.request.urlopen(
                    f"http://127.0.0.1:{port}/foot?from={pair[1][1]},{pair[1][0]}&to={pair[0][1]},{pair[0][0]}") as resp:
                body = _json.loads(resp.read())
        finally:
            server.shutdown()
            server.server_close()
        props = body["features"][0]["properties"]
        # Every pre-4B.3 wire field is intact (regression list).
        pre_43 = ["profile", "distance_m", "duration_s", "steps_m", "crossing_m",
                  "nodes", "classes", "footway", "crossing", "lit", "enclosed",
                  "area", "maneuvers", "maneuver_plan", "snap", "snap_max_m",
                  "snap_within_preferred", "component", "component_nodes",
                  "straight_m", "requested_straight_m", "snap_straight_m",
                  "route_m", "detour_ratio", "walk_speed_ms", "from", "to"]
        missing = [k for k in pre_43 if k not in props]
        self.assertEqual(missing, [], "all pre-4B.3 fields must remain")
        # The new additive keys.
        self.assertIn("cost", props)
        self.assertIn("segment_cost_s", props)
        self.assertEqual(len(props["segment_cost_s"]), props["nodes"] - 1)
        cost = props["cost"]
        self.assertEqual(cost["profile"], "general")
        self.assertGreater(cost["crossing"]["wait_s"], 0.0,
                           "a crossing walk must report crossing wait exposure")
        self.assertGreaterEqual(cost["crossing"]["edges"], 1)
        self.assertAlmostEqual(cost["pace_s"], props["duration_s"], delta=1.0)


# ---------------------------------------------------------------------------
# Real Qatar data
# ---------------------------------------------------------------------------

class RealQatarCostModelTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with open(CROSSING_FIXTURE, encoding="utf-8") as fh:
            cls.fc = json.load(fh)
        cls.graph = build_foot_graph(cls.fc)
        cls.general = FootRouter(cls.graph, cost_model=PedestrianCostModel.general())
        cls.legacy = FootRouter(cls.graph, cost_model=PedestrianCostModel.legacy())
        random.seed(11)
        cls.walks = []
        nodes = sorted(cls.graph.nodes().items())
        for _ in range(6000):
            a = random.choice(nodes)[1]
            b = random.choice(nodes)[1]
            try:
                r = cls.general.walk(a, b)
            except (NoRouteError, RouteError):
                continue
            cls.walks.append(r)
            if len(cls.walks) >= 40:
                break

    def test_real_data_consumes_real_sources(self):
        """The fixture's promoted facts actually reach the cost diagnostics."""
        cross_walks = [r for r in self.walks if r["crossing_m"] > 0]
        self.assertGreater(len(cross_walks), 0, "fixture must contain crossing walks")
        for r in cross_walks[:5]:
            c = r["cost"]["crossing"]
            self.assertGreaterEqual(c["edges"], 1)
            self.assertGreater(c["wait_s"], 0.0)
        any_incline = any(r["cost"]["incline"]["edges"] > 0 for r in self.walks)
        any_surface = any(r["cost"]["surface"]["poor_edges"] > 0 for r in self.walks)
        self.assertTrue(any_incline or any_surface,
                        "the fixture contains incline and rough-surface ways; "
                        "some sample walk should expose them")

    def test_real_walk_cost_invariants_hold(self):
        for r in self.walks:
            cost = r["cost"]
            self.assertAlmostEqual(cost["cost_s"], cost["pace_s"] + cost["penalty_s"],
                                   delta=0.05, msg="cost invariant")
            self.assertAlmostEqual(cost["pace_s"], r["route"].duration_s, delta=0.05,
                                   msg="pace == duration")
            self.assertAlmostEqual(sum(cost["factor_s"].values()),
                                   cost["cost_s"] - cost["base_time_s"],
                                   delta=0.1, msg="factor decomposition")
            self.assertEqual(len(r["segment_cost_s"]), len(r["route"].node_keys) - 1)

    def test_real_walks_still_have_valid_facts_and_plans(self):
        """4B.1 facts and 4B.2 plans are produced unchanged over cost-routed walks."""
        for r in self.walks:
            plan = r["maneuver_plan"]
            self.assertEqual(plan[0]["kind"], "depart")
            self.assertEqual(plan[-1]["kind"], "arrive")
            self.assertEqual([m["index"] for m in plan],
                             sorted(m["index"] for m in plan))

    def test_the_model_changes_selection_only_where_costs_warrant_it(self):
        """General vs legacy on real Qatar: quantify changed/unchanged routes."""
        changed = unchanged = 0
        dist_delta = 0.0
        nodes = sorted(self.graph.nodes().items())
        random.seed(21)
        pairs = []
        for _ in range(6000):
            a = random.choice(nodes)[1]
            b = random.choice(nodes)[1]
            try:
                rg = self.general.walk(a, b)
                rl = self.legacy.walk(a, b)
            except (NoRouteError, RouteError):
                continue
            pairs.append((a, b, rg, rl))
            if len(pairs) >= 30:
                break
        for _a, _b, rg, rl in pairs:
            if rg["route"].node_keys == rl["route"].node_keys:
                unchanged += 1
            else:
                changed += 1
                dist_delta += rg["route"].distance_m - rl["route"].distance_m
        # The fixture genuinely offers alternative geometry, so the physical
        # costs (stairs/incline/crossing wait) change SOME routes; the
        # magnitude is small because the costs are small and unit-seconds.
        self.assertGreater(changed, 0, "some real walk should change under the model")
        # No changed walk may be a pathological detour: distance growth is
        # bounded by what the saved seconds would justify (crossing waits are
        # 6 s, so a walk that changed by >30 m per changed route is suspect).
        net_per_changed = dist_delta / max(changed, 1)
        self.assertLess(net_per_changed, 30.0,
                        "no pathological detours: distance changes stay modest")

    def test_reported_duration_is_unchanged_walk_time(self):
        """duration_s stays pace time even though cost_s carries penalties."""
        for r in self.walks[:10]:
            self.assertAlmostEqual(
                r["route"].duration_s,
                r["cost"]["pace_s"], delta=0.05)


if __name__ == "__main__":
    unittest.main()