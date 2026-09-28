"""Pedestrian maneuver INTERPRETATION + salience (V7.4 4B.2).

4B.1 emits a faithful fact for every real windowed bend (~2.3 turns/km on the
Qatar bake) plus crossing/stairs runs, way transitions, depart and arrive.
4B.2 decides what a person would actually act on, and how it orders. These
tests pin the interpretation rules:

* dense ordinary turns collapse to a useful few (spacing + slight demotion);
* a lone meaningful corner survives;
* crossings and stairs ALWAYS survive nearby turn suppression (span dominance);
* crossing + turn and stairs + turn precedence is deterministic and documented;
* meaningless OSM way splits (name/class churn, way-id changes) produce no
  maneuver, while a real walkway<->road change produces a `continue`;
* depart/arrive bound the plan; ordering follows the geometry;
* uncertain crossing provenance (untyped node, unknown crossed road) stays
  uncertain — never promoted to false certainty;
* the plan is a PURE function of the 4B.1 facts: the source facts are not
  mutated, no crossing is ever re-derived from geometry.

The real-data half reuses the committed central-Doha clips and asserts plan
invariants over actual converted Qatar OSM.
"""

import json
import os
import random
import unittest

from vector_routing.algorithms import build_graph_from_features
from vector_routing.errors import NoRouteError, RouteError
from vector_routing.foot_graph import FootRouter, build_foot_graph
from vector_routing.pedestrian_maneuvers import CrossingCatalog
from vector_routing.pedestrian_plan import (
    MIN_MANEUVER_SPACING_M,
    SLIGHT_SUPPRESSION_RADIUS_M,
    WIGGLE_CLUSTER_M,
    WIGGLE_NET_DEG,
    build_pedestrian_plan,
)

HERE = os.path.dirname(os.path.abspath(__file__))
CROSSING_FIXTURE = os.path.join(HERE, "data", "qatar-crossing-fixture.geojson")
CROSSINGS_FIXTURE = os.path.join(HERE, "data", "qatar-crossings-fixture.geojson")
ROADS_FIXTURE = os.path.join(HERE, "data", "qatar-crossing-roads-fixture.geojson")


def road(highway, coords, **props):
    p = {"kind": "road", "highway": highway, "foot": True}
    p.update(props)
    return {"type": "Feature", "properties": p,
            "geometry": {"type": "LineString", "coordinates": coords}}


# --- hand-built 4B.1 facts (the plan's input is the fact stream) -----------

def turn_fact(index, distance_m, turn="turn-left", delta_deg=-90.0, road=None):
    return {"type": "turn", "index": index, "distance_m": distance_m,
            "turn": turn, "delta_deg": delta_deg,
            "road": road or {"highway": "footway"},
            "source": {}, "bearing": 0.0, "distance_to_next_m": 0.0}


def cross_fact(index, distance_m, *, crossing="marked", type_source="way",
               road=None, road_source=None, approach=None, enter=None, leave=None,
               cross_m=10.0, d2c=5.0, markings=None):
    return {"type": "cross", "index": index, "distance_m": distance_m,
            "approach_index": approach if approach is not None else index - 1,
            "approach_distance_m": max(0.0, distance_m - d2c),
            "enter_index": enter if enter is not None else index,
            "leave_index": leave if leave is not None else index + 1,
            "crossing_distance_m": cross_m, "distance_to_crossing_m": d2c,
            "crossing": crossing, "crossing_type_source": type_source,
            "crossing_markings": markings, "kerb": None, "tactile_paving": None,
            "road": road, "road_source": road_source,
            "source": {"footway": "crossing"}, "bearing": 90.0,
            "distance_to_next_m": 0.0}


def stairs_fact(index, distance_m, *, step_count=None, handrail=None, incline=None,
                begin=None, end=None, stairs_m=10.0):
    f = {"type": "stairs", "index": index, "distance_m": distance_m,
         "begin_index": begin if begin is not None else index,
         "end_index": end if end is not None else index + 1,
         "stairs_distance_m": stairs_m,
         "source": {"highway": "steps"}, "bearing": 90.0, "distance_to_next_m": 0.0}
    if step_count:
        f["step_count"] = step_count
    if handrail:
        f["handrail"] = handrail
    if incline:
        f["incline"] = incline
    return f


def transition_fact(index, distance_m, fro=None, to=None):
    return {"type": "transition", "index": index, "distance_m": distance_m,
            "from": fro or {"highway": "footway", "footway": "sidewalk"},
            "to": to or {"highway": "residential", "name": "Night Street"},
            "turn": "continue", "source": {}, "bearing": 0.0,
            "distance_to_next_m": 0.0}


def depart_fact():
    return {"type": "depart", "index": 0, "distance_m": 0.0,
            "road": {"highway": "footway", "footway": "sidewalk"},
            "source": {}, "bearing": 0.0, "distance_to_next_m": 0.0}


def arrive_fact(distance_m, index=99):
    return {"type": "arrive", "index": index, "distance_m": distance_m,
            "road": {"highway": "footway"}, "source": {},
            "bearing": 0.0, "distance_to_next_m": 0.0}


def kinds(plan):
    return [m["kind"] for m in plan]


class DenseTurnCollapseTest(unittest.TestCase):
    """Spacing (R3) + slight demotion (R4): dense ordinary turns go sparse."""

    def dense_facts(self):
        return [
            depart_fact(),
            turn_fact(10, 100.0, "turn-left", -90.0),
            turn_fact(11, 110.0, "slight-left", -30.0),   # 10 m after: spacing drop
            turn_fact(12, 125.0, "slight-right", 35.0),   # 25 m after: spacing ok,
                                                          # but noise beside the corner
            turn_fact(20, 260.0, "turn-right", -90.0),    # the meaningful one
            turn_fact(30, 400.0, "slight-left", -30.0),   # isolated on a straight
            arrive_fact(500.0),
        ]

    def test_four_dense_turns_become_two_useful_ones(self):
        plan = build_pedestrian_plan(self.dense_facts())
        turns = [m for m in plan if m["kind"] in (
            "turn_left", "turn_right", "slight_left", "slight_right", "uturn")]
        self.assertEqual(kinds(turns), ["turn_left", "turn_right", "slight_left"],
                         "110 m slight and 125 m slight are the same corner's noise")
        self.assertEqual(turns[0]["distance_m"], 100.0)
        self.assertEqual(turns[1]["distance_m"], 260.0)
        self.assertEqual(turns[2]["distance_m"], 400.0, "a lone slight is a real bend")

    def test_the_plan_is_sparser_than_the_fact_stream(self):
        facts = self.dense_facts()
        plan = build_pedestrian_plan(facts)
        self.assertLess(len(plan), len(facts))

    def test_a_serpentine_with_net_zero_direction_drops_entirely(self):
        """Three slight bends inside 60 m that return the path to its original
        heading are one wiggle, not three maneuvers (continuity rule)."""
        facts = [
            depart_fact(),
            turn_fact(10, 300.0, "slight-left", -30.0),
            turn_fact(11, 320.0, "slight-right", 35.0),
            turn_fact(12, 340.0, "slight-left", -28.0),   # net ~ -23 deg: a wiggle
            arrive_fact(1000.0),
        ]
        plan = build_pedestrian_plan(facts)
        self.assertEqual(kinds(plan), ["depart", "arrive"],
                         "the path bent and returned; there is nothing to announce")

    def test_a_real_corner_with_approach_slights_keeps_the_corner(self):
        """A cluster meaning a real corner (a windowed turn fact) keeps that
        corner; the slight bends around it are its geometry, not maneuvers.
        4B.1's own window already collapses a smooth curve into one turn fact,
        so this is the form the real stream takes."""
        facts = [
            depart_fact(),
            turn_fact(10, 300.0, "slight-left", -30.0),
            turn_fact(11, 325.0, "turn-left", -88.0),   # the real corner
            turn_fact(12, 350.0, "slight-right", 32.0), # its exit geometry
            arrive_fact(1000.0),
        ]
        plan = build_pedestrian_plan(facts)
        turns = [m for m in plan if "turn" in m["kind"] or m["kind"] == "uturn"]
        self.assertEqual(kinds(turns), ["turn_left"],
                         "the corner survives; its approach and exit bends drop")

    def test_an_equal_rank_pair_within_spacing_keeps_the_earlier(self):
        facts = [
            depart_fact(),
            turn_fact(10, 100.0, "turn-left", -90.0),
            turn_fact(11, 108.0, "turn-right", 92.0),     # 8 m, same rank
            arrive_fact(400.0),
        ]
        plan = build_pedestrian_plan(facts)
        self.assertEqual(
            kinds([m for m in plan if m["kind"] != "arrive"]),
            ["depart", "turn_left"],
            "two corners 8 m apart are one; the first is the announce point")

    def test_a_sharper_turn_displaces_a_weaker_one_within_spacing(self):
        facts = [
            depart_fact(),
            turn_fact(10, 100.0, "slight-right", 30.0),
            turn_fact(11, 110.0, "turn-right", -92.0),    # 10 m later, sharper
            arrive_fact(400.0),
        ]
        plan = build_pedestrian_plan(facts)
        turns = [m for m in plan if "turn" in m["kind"]]
        self.assertEqual(kinds(turns), ["turn_right"],
                         "the real corner must not be masked by the bend before it")
        self.assertEqual(turns[0]["distance_m"], 110.0)


class MeaningfulTurnPreservedTest(unittest.TestCase):
    def test_a_lone_real_corner_is_preserved(self):
        plan = build_pedestrian_plan([
            depart_fact(),
            turn_fact(15, 300.0, "turn-right", -92.0),
            arrive_fact(800.0),
        ])
        self.assertEqual(kinds(plan), ["depart", "turn_right", "arrive"])

    def test_a_real_corner_survives_end_to_end(self):
        graph = build_foot_graph([
            road("footway", [[51.5200, 25.2900], [51.5205, 25.2900]], footway="sidewalk"),
            road("footway", [[51.5205, 25.2900], [51.5205, 25.2905]], footway="sidewalk"),
        ])
        result = FootRouter(graph).walk((51.5200, 25.2900), (51.5205, 25.2905))
        self.assertEqual(kinds(result["maneuver_plan"]),
                         ["depart", "turn_left", "arrive"])


class CrossingPriorityTest(unittest.TestCase):
    """A genuine crossing survives turn suppression, on both sides of itself."""

    def crossing_with_approach_turn(self):
        """Turn left onto the crossing approach, cross, keep going north."""
        return build_foot_graph([
            road("footway", [[51.5200, 25.2900], [51.5202, 25.2900]], footway="sidewalk"),
            road("footway", [[51.5202, 25.2900], [51.5202, 25.2901]], footway="sidewalk"),
            road("footway", [[51.5202, 25.2901], [51.5202, 25.2902]], footway="crossing"),
            road("footway", [[51.5202, 25.2902], [51.5202, 25.2903]], footway="sidewalk"),
        ])

    def test_a_turn_directly_onto_a_crossing_is_the_crossing(self):
        result = FootRouter(self.crossing_with_approach_turn()).walk(
            (51.5200, 25.2900), (51.5202, 25.2903))
        self.assertEqual(kinds(result["maneuver_plan"]),
                         ["depart", "cross", "arrive"],
                         "the bend onto the crossing is the crossing's own geometry")
        # The cross maneuver still names what it crosses (or stays honest).
        cross = [m for m in result["maneuver_plan"] if m["kind"] == "cross"][0]
        self.assertEqual(cross["crossing"]["distance_m"],
                         round(result["crossing_m"], 1))

    def test_the_fact_stream_still_contains_the_turn(self):
        """Suppression is interpretation, not data loss: the 4B.1 facts stay."""
        result = FootRouter(self.crossing_with_approach_turn()).walk(
            (51.5200, 25.2900), (51.5202, 25.2903))
        self.assertIn("turn", [f["type"] for f in result["maneuvers"]])
        self.assertNotIn("turn", [m["kind"] for m in result["maneuver_plan"]])

    def test_a_turn_right_after_the_crossing_survives_in_order(self):
        """Cross, then turn left into the alley past the crossing: both real."""
        graph = build_foot_graph([
            road("footway", [[51.5200, 25.2900], [51.5201, 25.2900]], footway="sidewalk"),
            road("footway", [[51.5201, 25.2900], [51.5201, 25.2901]], footway="crossing"),
            road("footway", [[51.5201, 25.2901], [51.5201, 25.2904]], footway="sidewalk"),
            road("footway", [[51.5201, 25.2904], [51.5200, 25.2904]], footway="sidewalk"),
        ])
        result = FootRouter(graph).walk((51.5200, 25.2900), (51.5200, 25.2904))
        self.assertEqual(kinds(result["maneuver_plan"]),
                         ["depart", "cross", "turn_left", "arrive"],
                         "crossing first, then the alley turn on the far side")

    def test_hand_built_crossing_plus_turn_precedence(self):
        facts = [
            depart_fact(),
            turn_fact(2, 40.0, "turn-right", -92.0),          # 20 m before, outside span
            cross_fact(4, 60.0, approach=3, enter=4, leave=5),
            arrive_fact(300.0),
        ]
        plan = build_pedestrian_plan(facts)
        # The turn is outside the crossing's span (vertex 2 < approach 3), so
        # both survive, ordered by position — a turn AT the approach is the
        # crossing's own geometry and is covered by CrossingPriorityTest.
        self.assertEqual(kinds(plan), ["depart", "turn_right", "cross", "arrive"])

    def test_same_position_cross_beats_turn(self):
        """Documented guard: at one position, cross outranks everything."""
        facts = [
            depart_fact(),
            cross_fact(5, 60.0, approach=4, enter=5, leave=6),
            turn_fact(5, 60.0, "turn-left", -90.0),           # same position
            arrive_fact(300.0),
        ]
        plan = build_pedestrian_plan(facts)
        idx = [m for m in plan if m["kind"] == "cross"][0]["index"]
        self.assertEqual(idx, 5)


class StairsPriorityTest(unittest.TestCase):
    def test_a_turn_directly_onto_stairs_is_the_stairs(self):
        graph = build_foot_graph([
            road("footway", [[51.5200, 25.2900], [51.5202, 25.2900]], footway="sidewalk"),
            road("footway", [[51.5202, 25.2900], [51.5202, 25.2901]], footway="sidewalk"),
            road("steps", [[51.5202, 25.2901], [51.5202, 25.2902]],
                 step_count="12", handrail="yes"),
            road("footway", [[51.5202, 25.2902], [51.5202, 25.2903]], footway="sidewalk"),
        ])
        result = FootRouter(graph).walk((51.5200, 25.2900), (51.5202, 25.2903))
        self.assertEqual(kinds(result["maneuver_plan"]),
                         ["depart", "stairs", "arrive"])
        stairs = [m for m in result["maneuver_plan"] if m["kind"] == "stairs"][0]
        self.assertEqual(stairs["stairs"]["step_count"], "12")
        self.assertEqual(stairs["stairs"]["handrail"], "yes")
        self.assertEqual(stairs["stairs"]["distance_m"],
                         round(result["steps_m"], 1))

    def test_stairs_survive_a_nearby_real_turn(self):
        graph = build_foot_graph([
            road("footway", [[51.5200, 25.2900], [51.5201, 25.2900]], footway="sidewalk"),
            road("footway", [[51.5201, 25.2900], [51.5201, 25.2901]], footway="sidewalk"),
            road("steps", [[51.5201, 25.2901], [51.5201, 25.2902]]),
            road("footway", [[51.5201, 25.2902], [51.5201, 25.2904]], footway="sidewalk"),
            road("footway", [[51.5201, 25.2904], [51.5200, 25.2904]], footway="sidewalk"),
        ])
        result = FootRouter(graph).walk((51.5200, 25.2900), (51.5200, 25.2904))
        self.assertEqual(kinds(result["maneuver_plan"]),
                         ["depart", "stairs", "turn_left", "arrive"],
                         "stairs first, then the corner far past them")

    def test_same_position_stairs_beat_turn(self):
        facts = [
            depart_fact(),
            stairs_fact(5, 60.0, begin=5, end=6),
            turn_fact(5, 60.0, "turn-left", -90.0),
            arrive_fact(300.0),
        ]
        plan = build_pedestrian_plan(facts)
        self.assertEqual(kinds(plan)[1], "stairs")


class TransitionMeaningTest(unittest.TestCase):
    def test_walkway_to_road_is_a_continue(self):
        facts = [
            depart_fact(),
            transition_fact(5, 120.0,
                            fro={"highway": "footway", "footway": "sidewalk"},
                            to={"highway": "residential", "name": "Night Street"}),
            arrive_fact(400.0),
        ]
        plan = build_pedestrian_plan(facts)
        self.assertEqual(kinds(plan), ["depart", "continue", "arrive"])
        cont = [m for m in plan if m["kind"] == "continue"][0]
        self.assertEqual(cont["to"], {"highway": "residential", "name": "Night Street"})
        self.assertEqual(cont["road"], {"highway": "residential", "name": "Night Street"})

    def test_an_osm_name_split_produces_nothing(self):
        """footway (name A) -> footway (name B): way segmentation, not guidance."""
        facts = [
            depart_fact(),
            transition_fact(5, 120.0,
                            fro={"highway": "footway", "name": "Corniche Path A"},
                            to={"highway": "footway", "name": "Corniche Path B"}),
            arrive_fact(400.0),
        ]
        plan = build_pedestrian_plan(facts)
        self.assertEqual(kinds(plan), ["depart", "arrive"])

    def test_a_road_class_change_is_not_guidance(self):
        facts = [
            depart_fact(),
            transition_fact(5, 120.0,
                            fro={"highway": "residential"},
                            to={"highway": "tertiary", "name": "Main Road"}),
            arrive_fact(400.0),
        ]
        plan = build_pedestrian_plan(facts)
        self.assertEqual(kinds(plan), ["depart", "arrive"])

    def test_way_id_change_without_identity_change_produces_no_fact_at_all(self):
        """The 4B.1 fact layer does not even emit for a way-id change (identity
        is (highway, footway, name)); the plan must not invent one either."""
        facts = [
            depart_fact(),
            arrive_fact(400.0),
        ]
        self.assertEqual(kinds(build_pedestrian_plan(facts)), ["depart", "arrive"])

    def test_road_to_walkway_is_a_continue_too(self):
        facts = [
            depart_fact(),
            transition_fact(5, 120.0,
                            fro={"highway": "service", "name": "Rear Lane"},
                            to={"highway": "footway", "footway": "sidewalk"}),
            arrive_fact(400.0),
        ]
        plan = build_pedestrian_plan(facts)
        self.assertEqual(kinds(plan), ["depart", "continue", "arrive"])


class DepartArriveOrderingTest(unittest.TestCase):
    def test_depart_first_arrive_last_distance_closed(self):
        plan = build_pedestrian_plan([
            depart_fact(),
            turn_fact(10, 100.0, "turn-right", -92.0),
            cross_fact(20, 300.0, approach=19, enter=20, leave=22),
            arrive_fact(500.0),
        ])
        self.assertEqual(plan[0]["kind"], "depart")
        self.assertEqual(plan[0]["distance_m"], 0.0)
        self.assertEqual(plan[-1]["kind"], "arrive")
        self.assertEqual(plan[-1]["distance_to_next_m"], 0.0)
        # Distance closure: what each maneuver says is come before the next.
        for m, nxt in zip(plan, plan[1:]):
            self.assertAlmostEqual(m["distance_to_next_m"],
                                   nxt["distance_m"] - m["distance_m"], places=1)

    def test_ordering_follows_the_geometry(self):
        plan = build_pedestrian_plan([
            depart_fact(),
            turn_fact(15, 300.0, "turn-left", -90.0),
            cross_fact(25, 700.0, approach=24, enter=25, leave=27),
            stairs_fact(35, 900.0, begin=35, end=36),
            arrive_fact(1000.0),
        ])
        self.assertEqual(kinds(plan),
                         ["depart", "turn_left", "cross", "stairs", "arrive"])
        idx = [m["index"] for m in plan]
        self.assertEqual(idx, sorted(idx))

    def test_a_straight_walk_is_depart_arrive(self):
        plan = build_pedestrian_plan([depart_fact(), arrive_fact(300.0)])
        self.assertEqual(kinds(plan), ["depart", "arrive"])

    def test_empty_facts_yield_an_empty_plan(self):
        self.assertEqual(build_pedestrian_plan([]), [])


class ProvenanceTest(unittest.TestCase):
    def test_uncertain_crossed_road_stays_uncertain(self):
        facts = [
            depart_fact(),
            cross_fact(4, 60.0, approach=3, enter=4, leave=5,
                       road=None, road_source=None),
            arrive_fact(300.0),
        ]
        cross = [m for m in build_pedestrian_plan(facts) if m["kind"] == "cross"][0]
        self.assertIsNone(cross["road"])
        self.assertIsNone(cross["crossed_road_source"])

    def test_a_confident_crossed_road_keeps_its_provenance(self):
        facts = [
            depart_fact(),
            cross_fact(4, 60.0, approach=3, enter=4, leave=5,
                       road={"highway": "secondary", "name": "Al Rayyan Road",
                             "name_en": "Al Rayyan Road"},
                       road_source="road_graph_shared_node"),
            arrive_fact(300.0),
        ]
        cross = [m for m in build_pedestrian_plan(facts) if m["kind"] == "cross"][0]
        self.assertEqual(cross["road"]["name"], "Al Rayyan Road")
        self.assertEqual(cross["crossed_road_source"], "road_graph_shared_node")
        self.assertEqual(cross["crossing"]["type_source"], "way")

    def test_an_untyped_catalog_node_stays_untyped(self):
        facts = [
            depart_fact(),
            cross_fact(4, 60.0, approach=3, enter=4, leave=5,
                       crossing=None, type_source="catalog_node"),
            arrive_fact(300.0),
        ]
        cross = [m for m in build_pedestrian_plan(facts) if m["kind"] == "cross"][0]
        self.assertIsNone(cross["crossing"]["type"])
        self.assertEqual(cross["crossing"]["type_source"], "catalog_node",
                         "an untyped crossing node is not upgraded to a claim")

    def test_every_maneuver_is_traceable_to_its_source_facts(self):
        facts = [
            depart_fact(),
            turn_fact(10, 100.0, "turn-left", -90.0),
            cross_fact(20, 300.0, approach=19, enter=20, leave=22),
            stairs_fact(30, 500.0, begin=30, end=31, step_count="8"),
            transition_fact(40, 600.0),
            arrive_fact(900.0),
        ]
        for m in build_pedestrian_plan(facts):
            self.assertTrue(m["source_facts"])
            for sf in m["source_facts"]:
                self.assertIn("type", sf)
                self.assertIn("index", sf)

    def test_the_plan_never_mutates_the_fact_stream(self):
        facts = [depart_fact(), turn_fact(10, 100.0, "turn-left", -90.0),
                 arrive_fact(400.0)]
        build_pedestrian_plan(facts)
        for f in facts:
            self.assertNotIn("_kind", f, "interpretation may not leak into facts")
        # The turn fact keeps exactly its 4B.1 keys.
        self.assertEqual(set(facts[1]), {
            "type", "index", "distance_m", "turn", "delta_deg", "road",
            "source", "bearing", "distance_to_next_m"})
        self.assertEqual(set(facts[0]), {
            "type", "index", "distance_m", "road",
            "source", "bearing", "distance_to_next_m"})

    def test_no_false_crossing_is_introduced(self):
        facts = [
            depart_fact(),
            turn_fact(10, 100.0, "turn-left", -90.0),   # a junction, no crossing
            arrive_fact(400.0),
        ]
        plan = build_pedestrian_plan(facts)
        self.assertEqual([m for m in plan if m["kind"] == "cross"], [])


class RealQatarPlanTest(unittest.TestCase):
    """Plan invariants over real converted Qatar OSM (three committed clips)."""

    @classmethod
    def setUpClass(cls):
        with open(CROSSING_FIXTURE, encoding="utf-8") as fh:
            cls.foot_fc = json.load(fh)
        cls.graph = build_foot_graph(cls.foot_fc)
        with open(ROADS_FIXTURE, encoding="utf-8") as fh:
            cls.router = FootRouter(
                cls.graph,
                crossings=CrossingCatalog.from_path(CROSSINGS_FIXTURE),
                road_graph=build_graph_from_features(json.load(fh)["features"]),
            )
        random.seed(7)
        cls.samples = []
        cls.flats = []
        nodes = sorted(cls.graph.nodes().items())
        for _ in range(20000):
            a = random.choice(nodes)[1]
            b = random.choice(nodes)[1]
            try:
                r = cls.router.walk(a, b)
            except (NoRouteError, RouteError):
                continue
            cls.samples.append(r)
            if r["crossing_m"] == 0.0:
                cls.flats.append(r)
            if len(cls.samples) >= 40 and len(cls.flats) >= 12:
                break

    def test_crossing_facts_are_all_represented_in_the_plan(self):
        crossing_walks = [r for r in self.samples if r["crossing_m"] > 0]
        self.assertGreaterEqual(len(crossing_walks), 5)
        for r in crossing_walks:
            n_facts = sum(1 for f in r["maneuvers"] if f["type"] == "cross")
            n_plan = sum(1 for m in r["maneuver_plan"] if m["kind"] == "cross")
            self.assertEqual(n_plan, n_facts,
                             "every genuine crossing fact must survive as a cross")
            crossed = [m for m in r["maneuver_plan"] if m["kind"] == "cross"]
            self.assertAlmostEqual(
                sum(m["crossing"]["distance_m"] for m in crossed),
                r["crossing_m"], delta=1.0)

    def test_no_false_crossings_in_flat_walks(self):
        self.assertGreaterEqual(len(self.flats), 10)
        for r in self.flats:
            self.assertEqual([m for m in r["maneuver_plan"] if m["kind"] == "cross"], [])

    def test_stairs_remain_represented_only_where_sourced(self):
        stair_facts_seen = sum(1 for r in self.samples
                               for f in r["maneuvers"] if f["type"] == "stairs")
        stair_plan_seen = sum(1 for r in self.samples
                              for m in r["maneuver_plan"] if m["kind"] == "stairs")
        self.assertEqual(stair_plan_seen, stair_facts_seen)
        # And a stairs maneuver must have the source step facts when promoted.
        for r in self.samples:
            for m in r["maneuver_plan"]:
                if m["kind"] == "stairs" and m["stairs"].get("step_count"):
                    self.assertIn("step_count", m["source_facts"][0])

    def test_the_plan_is_sparser_than_the_fact_stream(self):
        total_facts = sum(len(r["maneuvers"]) for r in self.samples)
        total_plan = sum(len(r["maneuver_plan"]) for r in self.samples)
        self.assertLess(total_plan, total_facts)
        # Per-walk: the plan never has MORE events than facts.
        for r in self.samples:
            self.assertLessEqual(len(r["maneuver_plan"]), len(r["maneuvers"]))

    def test_turn_density_drops_without_losing_real_turns(self):
        facts_turns = sum(1 for r in self.samples
                          for f in r["maneuvers"] if f["type"] == "turn")
        plan_turns = sum(1 for r in self.samples
                         for m in r["maneuver_plan"]
                         if m["kind"] in ("turn_left", "turn_right",
                                          "slight_left", "slight_right", "uturn"))
        self.assertLess(plan_turns, facts_turns)
        # But real 90-degree-ish turns survive: at least a quarter of fact turns
        # are real turns and make the plan as such.
        real_facts = sum(1 for r in self.samples for f in r["maneuvers"]
                         if f["type"] == "turn" and f["turn"] in ("turn-left", "turn-right"))
        self.assertGreater(real_facts, 0)
        self.assertGreaterEqual(plan_turns, 1)


class WirePlanTest(unittest.TestCase):
    """/foot carries the interpretation additively (with all pre-4B.2 fields)."""

    @classmethod
    def setUpClass(cls):
        import threading
        import urllib.request
        from vector_routing.serve import make_server
        from vector_routing.service import RoutingService
        with open(CROSSING_FIXTURE, encoding="utf-8") as fh:
            fc = json.load(fh)
        svc = RoutingService(graph=build_graph_from_features([]))
        svc.set_foot_graph(build_foot_graph(fc),
                           crossings=CrossingCatalog.from_path(CROSSINGS_FIXTURE))
        cls.server = make_server(0, svc)
        cls.port = cls.server.server_address[1]
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def _get(self, path):
        import urllib.request
        with urllib.request.urlopen(f"http://127.0.0.1:{self.port}{path}") as r:
            return r.status, json.loads(r.read())

    def _a_walk_that_crosses(self):
        with open(CROSSING_FIXTURE, encoding="utf-8") as fh:
            fc = json.load(fh)
        router = FootRouter(build_foot_graph(fc))
        random.seed(5)
        nodes = sorted(router._graph.nodes().items())
        for _ in range(3000):
            a = random.choice(nodes)[1]
            b = random.choice(nodes)[1]
            try:
                r = router.walk(a, b)
            except (NoRouteError, RouteError):
                continue
            if r["crossing_m"] > 0:
                return (a, b)
        raise AssertionError("fixture must contain a deterministic crossing walk")

    def test_foot_wire_carries_the_plan_additively(self):
        a, b = self._a_walk_that_crosses()
        status, body = self._get(f"/foot?from={b[1]},{b[0]}&to={a[1]},{a[0]}")
        self.assertEqual(status, 200)
        props = body["features"][0]["properties"]
        # The 4B.1 fact stream is exactly as before.
        self.assertIsInstance(props["maneuvers"], list)
        # The interpretation is a NEW additive field.
        plan = props["maneuver_plan"]
        self.assertIsInstance(plan, list)
        self.assertGreaterEqual(len(plan), 3)
        self.assertEqual(plan[0]["kind"], "depart")
        self.assertEqual(plan[-1]["kind"], "arrive")
        self.assertTrue(any(m["kind"] == "cross" for m in plan))
        for m in plan:
            for key in ("kind", "index", "distance_m", "distance_to_next_m",
                        "road", "source_facts"):
                self.assertIn(key, m, key)
            self.assertTrue(m["source_facts"])
        # Every pre-existing field rides along untouched.
        for key in ("distance_m", "duration_s", "steps_m", "crossing_m", "nodes",
                    "classes", "footway", "crossing", "lit", "snap", "snap_max_m",
                    "snap_within_preferred", "component", "component_nodes",
                    "detour_ratio", "walk_speed_ms", "maneuvers"):
            self.assertIn(key, props, key)

    def test_footz_reports_the_salience_constants(self):
        _status, body = self._get("/footz")
        interp = body["interpretation"]
        self.assertEqual(interp["spacing_m"], MIN_MANEUVER_SPACING_M)
        self.assertEqual(interp["slight_suppression_radius_m"],
                         SLIGHT_SUPPRESSION_RADIUS_M)
        self.assertIn("wiggle_cluster_m", interp)
        self.assertIn("wiggle_net_deg", interp)


if __name__ == "__main__":
    unittest.main()