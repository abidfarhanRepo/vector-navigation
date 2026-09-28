"""Structured pedestrian maneuver FACTS (V7.4 4B.1).

This is the factual layer between the graph and a future instruction
generator. The doctrine, restated by the recon: Vector must not say "cross the
road" unless the map can establish a crossing — and the mirror: it must not be
silent where the map clearly did. These tests pin the layer's truth claims:

* a genuine ``footway=crossing`` walk generates a cross fact, with approach /
  enter / leave positions and the metres walked ON the crossing;
* a plain road intersection generates NO cross fact — exactly the "do not
  infer a crossing from two roads crossing" rule;
* the crossing TYPE comes from the crossing's own ``crossing=*`` tag first,
  and from the ``<region>_crossings.geojson`` artifact (highway=crossing nodes)
  second;
* the crossed ROAD is named only when a road graph is wired and a road shares
  nodes with the crossing — otherwise the fact is ``road: null``, honestly;
* stairs facts require ``highway=steps`` edges, and ``step_count``/``handrail``/
  ``incline`` appear only where the graph promoted them;
* way transitions, turns and continues are geometry facts, never prose;
* every fact carries a ``source`` (edge props, artifact, or measurement window)
  so nothing here is a guess.

The real-data half runs against three committed clips of the real 260912 Qatar
bake, all in the same central-Doha bbox: ``qatar-crossing-fixture.geojson``
(foot ways, 4A.4), ``qatar-crossings-fixture.geojson`` (the artifact points),
and ``qatar-crossing-roads-fixture.geojson`` (the car ways) — regenerate with
``V7.4-EVIDENCE/make_maneuver_fixtures.py``.
"""

import json
import os
import random
import unittest

from vector_routing.algorithms import build_graph_from_features
from vector_routing.errors import NoRouteError, RouteError
from vector_routing.foot_graph import FootRouter, build_foot_graph
from vector_routing.pedestrian_maneuvers import (
    CROSSING_NODE_RADIUS_M,
    CrossingCatalog,
    build_pedestrian_maneuvers,
)
from vector_routing.router import Router
from vector_routing.graph import RoutingGraph

HERE = os.path.dirname(os.path.abspath(__file__))
CROSSING_FIXTURE = os.path.join(HERE, "data", "qatar-crossing-fixture.geojson")
CROSSINGS_FIXTURE = os.path.join(HERE, "data", "qatar-crossings-fixture.geojson")
ROADS_FIXTURE = os.path.join(HERE, "data", "qatar-crossing-roads-fixture.geojson")


def road(highway, coords, **props):
    """A converted road feature in the shape osm_to_geojson.py emits."""
    p = {"kind": "road", "highway": highway, "foot": True}
    p.update(props)
    return {"type": "Feature", "properties": p,
            "geometry": {"type": "LineString", "coordinates": coords}}


def sidewalk_walk():
    """Sidewalk -- crossing -- sidewalk, ~10 m per segment at lat 25.29."""
    return build_foot_graph([
        road("footway", [[51.5200, 25.2900], [51.5201, 25.2900]], footway="sidewalk"),
        road("footway", [[51.5201, 25.2900], [51.5202, 25.2900]], footway="crossing"),
        road("footway", [[51.5202, 25.2900], [51.5203, 25.2900]], footway="sidewalk"),
    ])


class CrossingFactsTest(unittest.TestCase):
    """A real crossing generates a cross fact; an ordinary intersection does not."""

    def test_a_genuine_crossing_generates_one_cross_fact(self):
        router = FootRouter(sidewalk_walk())
        result = router.walk((51.5200, 25.2900), (51.5203, 25.2900))
        facts = result["maneuvers"]
        types = [f["type"] for f in facts]
        self.assertEqual(types, ["depart", "cross", "arrive"])
        cross = facts[1]
        # The three positions of the crossing, on the walked geometry:
        # approach is the last node before the crossed road (vertex 0 here),
        # enter the first node ON it (vertex 1), leave the first node after
        # (vertex 2).
        self.assertEqual(cross["approach_index"], 0)
        self.assertEqual(cross["enter_index"], 1)
        self.assertEqual(cross["leave_index"], 2)
        self.assertEqual(cross["index"], 1)
        self.assertAlmostEqual(cross["crossing_distance_m"], 10.07, delta=1.0)
        # The approach leg (the last node before the crossing to the crossing)
        # is one sidewalk segment long; the approach itself sits at the walk's
        # start.
        self.assertAlmostEqual(cross["distance_to_crossing_m"], 10.07, delta=1.0)
        self.assertAlmostEqual(cross["approach_distance_m"], 0.0, delta=0.01)
        # Sourced: the walked edge said footway=crossing. Nothing inferred.
        self.assertEqual(cross["source"], {"footway": "crossing"})
        # The enrichments are absent when their sources are absent.
        self.assertIsNone(cross["crossing"])
        self.assertIsNone(cross["road"])
        self.assertIsNone(cross["road_source"])

    def test_crossing_m_and_the_cross_fact_agree(self):
        """crossing_m (4A.4) and the maneuver fact measure the same metres."""
        result = FootRouter(sidewalk_walk()).walk((51.5200, 25.2900), (51.5203, 25.2900))
        self.assertGreater(result["crossing_m"], 0.0)
        self.assertAlmostEqual(result["crossing_m"],
                               result["maneuvers"][1]["crossing_distance_m"], delta=1.0)

    def test_a_plain_road_intersection_generates_no_cross_fact(self):
        """THE negative: two roads crossing is a junction, not a crossing.

        The recon's sentence is the rule: a crossing fact may only exist where
        the map data draws a crossing. Here the pedestrian graph contains two
        residential roads that meet at a T-junction with no footway=crossing
        anywhere; a route through the junction must produce NO cross fact.
        """
        graph = build_foot_graph([
            road("residential", [[51.5200, 25.2900], [51.5210, 25.2900]],
                 foot=True, name="East Road"),
            road("residential", [[51.5205, 25.2895], [51.5205, 25.2905]],
                 foot=True, name="North Road"),
        ])
        router = FootRouter(graph)
        # Straight along East Road through the junction...
        r1 = router.walk((51.5200, 25.2900), (51.5210, 25.2900))
        # ...and turning at it, which is the strongest form of "the route
        # passes through the intersection without a crossing".
        r2 = router.walk((51.5200, 25.2900), (51.5205, 25.2905))
        for r in (r1, r2):
            self.assertEqual(
                [f for f in r["maneuvers"] if f["type"] == "cross"], [],
                "an ordinary road junction must not invent a crossing")
            self.assertEqual(r["crossing_m"], 0.0)

    def test_a_crossing_drawn_as_many_ways_is_one_fact(self):
        """OSM draws long diagonals as several consecutive crossing ways; the
        pedestrian performs ONE crossing."""
        graph = build_foot_graph([
            road("footway", [[51.5200, 25.2900], [51.5201, 25.2900]], footway="sidewalk"),
            road("footway", [[51.5201, 25.2900], [51.52011, 25.29001]],
                 footway="crossing"),
            road("footway", [[51.52011, 25.29001], [51.52012, 25.29002]],
                 footway="crossing"),
            road("footway", [[51.52012, 25.29002], [51.5202, 25.29003]],
                 footway="crossing"),
            road("footway", [[51.5202, 25.29003], [51.5203, 25.29003]], footway="sidewalk"),
        ])
        result = FootRouter(graph).walk((51.5200, 25.2900), (51.5203, 25.29003))
        crosses = [f for f in result["maneuvers"] if f["type"] == "cross"]
        self.assertEqual(len(crosses), 1, "several crossing ways are one crossing")
        self.assertEqual(crosses[0]["enter_index"], 1)
        self.assertEqual(crosses[0]["leave_index"], 4)
        self.assertAlmostEqual(
            crosses[0]["crossing_distance_m"], result["crossing_m"], delta=1.0)

    def test_the_cross_fact_carries_the_way_crossing_type(self):
        graph = build_foot_graph([
            road("footway", [[51.5200, 25.2900], [51.5201, 25.2900]], footway="sidewalk"),
            road("footway", [[51.5201, 25.2900], [51.5202, 25.2900]],
                 footway="crossing", crossing="zebra"),
            road("footway", [[51.5202, 25.2900], [51.5203, 25.2900]], footway="sidewalk"),
        ])
        cross = [f for f in (FootRouter(graph).walk(
            (51.5200, 25.2900), (51.5203, 25.2900))["maneuvers"])
            if f["type"] == "cross"][0]
        self.assertEqual(cross["crossing"], "zebra")
        self.assertEqual(cross["crossing_type_source"], "way")
        self.assertIn("crossing", cross["source"])

    def test_the_catalog_supplies_the_type_when_the_way_has_none(self):
        """Only 168 of 2,961 Qatari crossing ways carry their own type; the
        highway=crossing artifact NODE is where it usually lives."""
        graph = build_foot_graph([
            road("footway", [[51.5200, 25.2900], [51.5201, 25.2900]], footway="sidewalk"),
            road("footway", [[51.5201, 25.2900], [51.52011, 25.2900]],
                 footway="crossing"),
            road("footway", [[51.52011, 25.2900], [51.52012, 25.2900]],
                 footway="crossing"),
            road("footway", [[51.52012, 25.2900], [51.5202, 25.2900]], footway="sidewalk"),
        ])
        catalog = CrossingCatalog.from_feature_collection({
            "type": "FeatureCollection",
            "features": [{
                "type": "Feature", "id": "n100",
                "geometry": {"type": "Point", "coordinates": [51.52011, 25.2900]},
                "properties": {"kind": "crossing", "crossing": "marked"},
            }],
        })
        cross = [f for f in (FootRouter(graph, crossings=catalog).walk(
            (51.5200, 25.2900), (51.5202, 25.2900))["maneuvers"])
            if f["type"] == "cross"][0]
        self.assertEqual(cross["crossing"], "marked")
        self.assertEqual(cross["crossing_type_source"], "catalog")
        # Nothing about the artifact's type leaked into the fact's source: the
        # fact's source is what the WALKED EDGE promoted, its type enrichment is
        # separately attributed via crossing_type_source.
        self.assertEqual(cross["source"], {"footway": "crossing"})

    def test_the_way_tag_beats_the_catalog(self):
        """A crossing that SAYS what it is is richer than a node near it."""
        graph = build_foot_graph([
            road("footway", [[51.5200, 25.2900], [51.5201, 25.2900]], footway="sidewalk"),
            road("footway", [[51.5201, 25.2900], [51.5202, 25.2900]],
                 footway="crossing", crossing="zebra"),
            road("footway", [[51.5202, 25.2900], [51.5203, 25.2900]], footway="sidewalk"),
        ])
        catalog = CrossingCatalog.from_feature_collection({
            "type": "FeatureCollection",
            "features": [{
                "type": "Feature", "id": "n100",
                "geometry": {"type": "Point", "coordinates": [51.52015, 25.2900]},
                "properties": {"kind": "crossing", "crossing": "marked"},
            }],
        })
        cross = [f for f in (FootRouter(graph, crossings=catalog).walk(
            (51.5200, 25.2900), (51.5203, 25.2900))["maneuvers"])
            if f["type"] == "cross"][0]
        self.assertEqual(cross["crossing"], "zebra", "the walked way's own tag wins")
        self.assertEqual(cross["crossing_type_source"], "way")

    def test_the_crossed_road_comes_from_the_road_graph_or_nowhere(self):
        """A cross fact names the road only when a road way shares the
        crossing's nodes — the OSM pattern, measured at 97% coverage on the
        real bake. Without a road graph the fact is complete but roadless."""
        graph = build_foot_graph([
            road("footway", [[51.5200, 25.2900], [51.5201, 25.2900]], footway="sidewalk"),
            road("footway", [[51.5201, 25.2900], [51.5202, 25.2900]], footway="crossing"),
            road("footway", [[51.5202, 25.2900], [51.5203, 25.2900]], footway="sidewalk"),
        ])
        # Without a road graph: the crossing fact is honestly roadless.
        bare = [f for f in (FootRouter(graph).walk(
            (51.5200, 25.2900), (51.5203, 25.2900))["maneuvers"])
            if f["type"] == "cross"][0]
        self.assertIsNone(bare["road"])
        self.assertIsNone(bare["road_source"])

        # With a road graph whose Al Corniche way passes through the crossing's
        # own nodes: the crossing fact names it.
        roads = build_graph_from_features([
            {"type": "Feature", "id": "w1",
             "properties": {"kind": "road", "highway": "residential",
                            "car": True, "name": "Al Corniche"},
             "geometry": {"type": "LineString", "coordinates": [
                 [51.5200, 25.2900], [51.5201, 25.2900], [51.5202, 25.2900],
                 [51.5203, 25.2900]]}},
        ])
        enriched = [f for f in (FootRouter(graph, road_graph=roads).walk(
            (51.5200, 25.2900), (51.5203, 25.2900))["maneuvers"])
            if f["type"] == "cross"][0]
        self.assertEqual(enriched["road"]["name"], "Al Corniche")
        self.assertEqual(enriched["road"]["highway"], "residential")
        self.assertEqual(enriched["road_source"], "road_graph_shared_node")

    def test_at_a_junction_the_road_sharing_more_nodes_wins(self):
        """The crossed road is the one the crossing RUNS ACROSS (shares most of
        the run's nodes), not a tangential road it merely touches."""
        graph = build_foot_graph([
            road("footway", [[51.5200, 25.2900], [51.5201, 25.2900]], footway="sidewalk"),
            road("footway", [[51.5201, 25.2900], [51.5202, 25.2900]], footway="crossing"),
            road("footway", [[51.5202, 25.2900], [51.5203, 25.2900]], footway="sidewalk"),
        ])
        roads = build_graph_from_features([
            {"type": "Feature", "id": "w1",
             "properties": {"kind": "road", "highway": "residential",
                            "car": True, "name": "Crossed Road"},
             "geometry": {"type": "LineString", "coordinates": [
                 [51.5201, 25.2900], [51.5202, 25.2900]]}},
            {"type": "Feature", "id": "w2",
             "properties": {"kind": "road", "highway": "residential",
                            "car": True, "name": "Tangential Road"},
             "geometry": {"type": "LineString", "coordinates": [
                 [51.5201, 25.2900], [51.5201, 25.2901]]}},
        ])
        cross = [f for f in (FootRouter(graph, road_graph=roads).walk(
            (51.5200, 25.2900), (51.5203, 25.2900))["maneuvers"])
            if f["type"] == "cross"][0]
        self.assertEqual(cross["road"]["name"], "Crossed Road")


class StairsFactsTest(unittest.TestCase):
    """Stairs are facts only when the graph says so, with only the promoted step facts."""

    def stairs_walk(self, **steps_props):
        return build_foot_graph([
            road("footway", [[51.5200, 25.2900], [51.5201, 25.2900]], footway="sidewalk"),
            road("steps", [[51.5201, 25.2900], [51.5202, 25.2900]], **steps_props),
            road("footway", [[51.5202, 25.2900], [51.5203, 25.2900]], footway="sidewalk"),
        ])

    def test_a_stair_segment_generates_a_stairs_fact(self):
        result = FootRouter(self.stairs_walk()).walk(
            (51.5200, 25.2900), (51.5203, 25.2900))
        stairs = [f for f in result["maneuvers"] if f["type"] == "stairs"]
        self.assertEqual(len(stairs), 1)
        f = stairs[0]
        self.assertEqual(f["begin_index"], 1)
        self.assertEqual(f["end_index"], 2)
        self.assertEqual(f["index"], 1)
        self.assertAlmostEqual(f["stairs_distance_m"], result["steps_m"], delta=1.0)
        self.assertEqual(f["source"], {"highway": "steps"})

    def test_the_promoted_step_facts_ride_along_only_when_present(self):
        result = FootRouter(self.stairs_walk(step_count="12", handrail="yes",
                                             incline="up")).walk(
            (51.5200, 25.2900), (51.5203, 25.2900))
        f = [x for x in result["maneuvers"] if x["type"] == "stairs"][0]
        self.assertEqual(f["step_count"], "12")
        self.assertEqual(f["handrail"], "yes")
        self.assertEqual(f["incline"], "up")
        self.assertEqual(f["source"]["step_count"], "12")
        self.assertEqual(f["source"]["handrail"], "yes")

    def test_a_bare_stair_has_no_invented_step_facts(self):
        """Absence is preserved: a step with no tags is still stairs, but its
        fact carries no step_count/handrail/incline — 4A.4's rule."""
        f = [x for x in (FootRouter(self.stairs_walk()).walk(
            (51.5200, 25.2900), (51.5203, 25.2900))["maneuvers"])
            if x["type"] == "stairs"][0]
        for key in ("step_count", "handrail", "incline"):
            self.assertNotIn(key, f, key)
        self.assertEqual(f["source"], {"highway": "steps"})

    def test_a_walk_with_no_stairs_generates_no_stairs_fact(self):
        result = FootRouter(sidewalk_walk()).walk(
            (51.5200, 25.2900), (51.5203, 25.2900))
        self.assertEqual([f for f in result["maneuvers"] if f["type"] == "stairs"], [])
        self.assertEqual(result["steps_m"], 0.0)


class TransitionAndTurnFactsTest(unittest.TestCase):
    """Way transitions, ordinary turns and continues — geometry, not prose."""

    def test_a_walkway_to_road_boundary_is_a_transition(self):
        graph = build_foot_graph([
            road("footway", [[51.5200, 25.2900], [51.5201, 25.2900]], footway="sidewalk"),
            road("residential", [[51.5201, 25.2900], [51.5202, 25.2900]],
                 foot=True, name="Night Street"),
        ])
        facts = FootRouter(graph).walk((51.5200, 25.2900), (51.5202, 25.2900))["maneuvers"]
        trans = [f for f in facts if f["type"] == "transition"]
        self.assertEqual(len(trans), 1)
        self.assertEqual(trans[0]["index"], 1)
        self.assertEqual(trans[0]["from"], {"highway": "footway", "footway": "sidewalk"})
        self.assertEqual(trans[0]["to"], {"highway": "residential", "name": "Night Street"})
        self.assertEqual(trans[0]["turn"], "continue",
                         "no turn at the boundary: the transition IS the continue")
        self.assertEqual(trans[0]["source"], {"highway": "footway", "footway": "sidewalk"})

    def test_crossing_boundaries_are_not_also_transitions(self):
        """The cross fact's approach/leave phases ARE the boundary facts."""
        facts = FootRouter(sidewalk_walk()).walk(
            (51.5200, 25.2900), (51.5203, 25.2900))["maneuvers"]
        self.assertEqual([f["type"] for f in facts], ["depart", "cross", "arrive"])

    def test_a_real_corner_generates_one_turn_fact(self):
        graph = build_foot_graph([
            road("footway", [[51.5200, 25.2900], [51.5205, 25.2900]], footway="sidewalk"),
            road("footway", [[51.5205, 25.2900], [51.5205, 25.2905]], footway="sidewalk"),
        ])
        facts = FootRouter(graph).walk((51.5200, 25.2900), (51.5205, 25.2905))["maneuvers"]
        turns = [f for f in facts if f["type"] == "turn"]
        self.assertEqual(len(turns), 1, facts)
        # East, then north: a LEFT turn in navigator convention.
        self.assertEqual(turns[0]["turn"], "turn-left")
        self.assertEqual(turns[0]["index"], 1)
        self.assertGreater(abs(turns[0]["delta_deg"]), 80.0)
        self.assertEqual(turns[0]["road"]["highway"], "footway")
        # The measurement that established it, stated as the source.
        self.assertIn("bearing_window_m", turns[0]["source"])

    def test_a_straight_walk_generates_no_turn_facts(self):
        facts = FootRouter(sidewalk_walk()).walk(
            (51.5200, 25.2900), (51.5203, 25.2900))["maneuvers"]
        self.assertEqual([f for f in facts if f["type"] == "turn"], [])

    def test_a_gentle_bend_is_not_a_turn(self):
        """A < 20-degree deviation is the road continuing, per _classify_turn."""
        graph = build_foot_graph([
            road("footway", [[51.5200, 25.2900], [51.5205, 25.2900],
                             [51.5206, 25.29001]], footway="sidewalk"),
        ])
        facts = FootRouter(graph).walk((51.5200, 25.2900), (51.5206, 25.29001))["maneuvers"]
        self.assertEqual([f for f in facts if f["type"] == "turn"], [])

    def test_a_turn_onto_another_way_is_one_turn_fact_not_a_transition_too(self):
        graph = build_foot_graph([
            road("footway", [[51.5200, 25.2900], [51.5205, 25.2900]], footway="sidewalk"),
            road("residential", [[51.5205, 25.2900], [51.5205, 25.2905]],
                 foot=True, name="Up Street"),
        ])
        facts = FootRouter(graph).walk((51.5200, 25.2900), (51.5205, 25.2905))["maneuvers"]
        turns = [f for f in facts if f["type"] == "turn"]
        trans = [f for f in facts if f["type"] == "transition"]
        self.assertEqual(len(turns), 1)
        self.assertEqual(trans, [], "the turn fact carries the road change")
        self.assertEqual(turns[0]["road"], {"highway": "residential", "name": "Up Street"})


class FactShapeTest(unittest.TestCase):
    """Every fact: positioned on the geometry, traceable, never prose."""

    def test_depart_and_arrive_bound_every_walk(self):
        result = FootRouter(sidewalk_walk()).walk(
            (51.5200, 25.2900), (51.5203, 25.2900))
        facts = result["maneuvers"]
        self.assertEqual(facts[0]["type"], "depart")
        self.assertEqual(facts[0]["index"], 0)
        self.assertEqual(facts[0]["distance_m"], 0.0)
        self.assertEqual(facts[-1]["type"], "arrive")
        self.assertEqual(facts[-1]["index"], len(result["route"].node_keys) - 1)
        self.assertEqual(facts[-1]["distance_to_next_m"], 0.0)

    def test_distances_to_next_line_up_with_the_geometry(self):
        result = FootRouter(sidewalk_walk()).walk(
            (51.5200, 25.2900), (51.5203, 25.2900))
        facts = result["maneuvers"]
        coords = result["route"].path
        from vector_routing.haversine import haversine_meters
        dist = [0.0]
        for a, b in zip(coords, coords[1:]):
            dist.append(dist[-1] + haversine_meters(a, b))
        for f, nxt in zip(facts, facts[1:]):
            self.assertAlmostEqual(
                f["distance_to_next_m"], dist[nxt["index"]] - dist[f["index"]],
                delta=0.2, msg=f)
            self.assertAlmostEqual(f["distance_m"], round(dist[f["index"]], 1),
                                   delta=0.2, msg=f)

    def test_every_fact_is_positioned_and_sourced(self):
        graph = build_foot_graph([
            road("footway", [[51.5200, 25.2900], [51.5201, 25.2900]], footway="sidewalk"),
            road("footway", [[51.5201, 25.2900], [51.5202, 25.2900]], footway="crossing"),
            road("steps", [[51.5202, 25.2900], [51.5203, 25.2900]], step_count="8"),
            road("footway", [[51.5203, 25.2900], [51.5204, 25.2900]], footway="sidewalk"),
        ])
        result = FootRouter(graph).walk((51.5200, 25.2900), (51.5204, 25.2900))
        n = len(result["route"].node_keys)
        for f in result["maneuvers"]:
            self.assertIn("type", f)
            self.assertIn("source", f, f)
            self.assertLessEqual(f["index"], n - 1, f)
            self.assertGreaterEqual(f["index"], 0, f)
            self.assertGreaterEqual(f["distance_m"], 0.0, f)
            self.assertGreaterEqual(f["distance_to_next_m"], 0.0, f)
        # The same route's facts describe the same walk as the 4A.4 numbers.
        result2 = FootRouter(graph).walk((51.5200, 25.2900), (51.5204, 25.2900))
        self.assertEqual(
            sum(f["crossing_distance_m"] for f in result2["maneuvers"]
                if f["type"] == "cross"),
            round(result2["crossing_m"], 1))
        self.assertEqual(
            sum(f["stairs_distance_m"] for f in result2["maneuvers"]
                if f["type"] == "stairs"),
            round(result2["steps_m"], 1))

    def test_a_zero_or_one_node_path_has_no_facts(self):
        graph = build_foot_graph([
            road("footway", [[51.5200, 25.2900], [51.5201, 25.2900]], footway="sidewalk"),
        ])
        node = RoutingGraph.node_key(51.5200, 25.2900)
        router = FootRouter(graph)
        self.assertEqual(router.route_by_node(node, node).node_keys, [node])
        # build_pedestrian_maneuvers refuses a path it cannot make segments of.
        self.assertEqual(
            build_pedestrian_maneuvers([node], graph), [])


class CrossingCatalogTest(unittest.TestCase):
    """The artifact loader: raw facts, radius-bounded, absence-tolerant."""

    def point(self, coord, props):
        return {"type": "Feature", "id": "n123",
                "geometry": {"type": "Point", "coordinates": coord},
                "properties": {"kind": "crossing", **props}}

    def test_from_path_missing_or_unreadable_is_empty(self):
        self.assertEqual(len(CrossingCatalog.from_path("/nonexistent/crossings.json")), 0)
        import tempfile
        with tempfile.NamedTemporaryFile("w", suffix=".geojson", delete=False) as fh:
            fh.write("{not json")
            path = fh.name
        try:
            self.assertEqual(len(CrossingCatalog.from_path(path)), 0)
        finally:
            os.unlink(path)

    def test_kerb_only_points_are_not_crossing_facts(self):
        catalog = CrossingCatalog([
            {"type": "Feature", "id": "n1",
             "geometry": {"type": "Point", "coordinates": [51.52, 25.29]},
             "properties": {"kind": "kerb", "kerb": "lowered"}},
        ])
        self.assertEqual(len(catalog), 0)

    def test_near_is_radius_bounded_and_closest_wins(self):
        catalog = CrossingCatalog([
            {"type": "Feature", "id": "n1",
             "geometry": {"type": "Point", "coordinates": [51.5201, 25.2900]},
             "properties": {"kind": "crossing"}},
            {"type": "Feature", "id": "n2",
             "geometry": {"type": "Point", "coordinates": [51.5200, 25.2900]},
             "properties": {"kind": "crossing", "crossing": "marked",
                            "tactile_paving": "yes"}},
        ])
        hit = catalog.near(51.5200005, 25.2900005)
        self.assertIsNotNone(hit)
        self.assertEqual(hit["id"], "n2")
        self.assertEqual(hit["crossing"], "marked")
        self.assertEqual(hit["tactile_paving"], "yes")
        self.assertLess(hit["distance_m"], 1.0)
        # A node beyond the radius is not that crossing's witness.
        self.assertIsNone(catalog.near(51.5250, 25.2900))

    def test_an_untyped_crossing_node_is_still_a_crossing_node(self):
        catalog = CrossingCatalog([
            {"type": "Feature", "id": "n1",
             "geometry": {"type": "Point", "coordinates": [51.52, 25.29]},
             "properties": {"kind": "crossing"}},
        ])
        hit = catalog.near(51.52, 25.29)
        self.assertIsNotNone(hit)
        self.assertNotIn("crossing", hit, "absence of a type must stay absence")


class RealQatarManeuverFactsTest(unittest.TestCase):
    """The 4B.1 claims against real converted Qatar OSM, three clipped layers."""

    @classmethod
    def setUpClass(cls):
        with open(CROSSING_FIXTURE, encoding="utf-8") as fh:
            cls.foot_fc = json.load(fh)
        cls.graph = build_foot_graph(cls.foot_fc)
        cls.catalog = CrossingCatalog.from_path(CROSSINGS_FIXTURE)
        with open(ROADS_FIXTURE, encoding="utf-8") as fh:
            cls.roads = build_graph_from_features(json.load(fh)["features"])
        cls.router = FootRouter(cls.graph, crossings=cls.catalog,
                                road_graph=cls.roads)
        # Fixed seed over a fixed fixture: the sampling below is deterministic.
        cls.samples = []
        random.seed(7)
        nodes = sorted(cls.graph.nodes().items())
        for _ in range(4000):
            a = random.choice(nodes)[1]
            b = random.choice(nodes)[1]
            try:
                r = cls.router.walk(a, b)
            except (NoRouteError, RouteError):
                continue
            if r["crossing_m"] > 0:
                cls.samples.append(r)
                if len(cls.samples) >= 40:
                    break

    def test_the_fixture_layers_are_fit_for_purpose(self):
        cross_edges = [p for _s, _t, _w, p in self.graph.edges()
                       if (p.get("footway") or "").strip().lower() == "crossing"]
        self.assertGreater(len(cross_edges), 100, "fixture must contain crossings")
        self.assertGreater(len(self.catalog), 100, "fixture artifact must be populated")
        self.assertGreater(len(self.roads.nodes()), 1000, "fixture roads must be populated")

    def test_genuine_crossings_produce_cross_facts_sourced_from_crossing_edges(self):
        self.assertGreaterEqual(len(self.samples), 5,
                                "the fixed sample must include crossing walks")
        for r in self.samples:
            crosses = [f for f in r["maneuvers"] if f["type"] == "cross"]
            self.assertGreaterEqual(len(crosses), 1, r["route"].distance_m)
            for f in crosses:
                self.assertEqual(f["source"].get("footway"), "crossing",
                                 "a cross fact is sourced from footway=crossing")
                self.assertLessEqual(f["leave_index"], len(r["route"].node_keys) - 1)

    def test_cross_facts_measure_exactly_the_crossing_metres_walked(self):
        for r in self.samples:
            walked = sum(f["crossing_distance_m"] for f in r["maneuvers"]
                         if f["type"] == "cross")
            self.assertAlmostEqual(walked, r["crossing_m"], delta=1.0)

    def test_real_cross_facts_carry_type_and_road_when_the_layers_know(self):
        typed = roaded = 0
        for r in self.samples:
            for f in r["maneuvers"]:
                if f["type"] != "cross":
                    continue
                if f["crossing"] in ("marked", "zebra", "traffic_signals",
                                     "uncontrolled", "unmarked"):
                    typed += 1
                if f.get("road") and (f["road"].get("name") or f["road"].get("highway")):
                    roaded += 1
        self.assertGreater(typed, 0, "some real crossing must carry a type")
        self.assertGreater(roaded, 0, "some real crossing must name its road")
        # And every road fact is traceable to the road graph.
        for r in self.samples:
            for f in r["maneuvers"]:
                if f["type"] == "cross" and f.get("road"):
                    self.assertEqual(f["road_source"], "road_graph_shared_node")

    def test_stairs_facts_follow_real_steps_edges(self):
        steps = [p for _s, _t, _w, p in self.graph.edges()
                 if p.get("highway") == "steps"]
        self.assertGreater(len(steps), 0, "the fixture must contain stairs")
        found = 0
        random.seed(3)
        nodes = sorted(self.graph.nodes().items())
        for _ in range(6000):
            a = random.choice(nodes)[1]
            b = random.choice(nodes)[1]
            try:
                r = self.router.walk(a, b)
            except (NoRouteError, RouteError):
                continue
            if r["steps_m"] > 0:
                stairs = [f for f in r["maneuvers"] if f["type"] == "stairs"]
                self.assertGreaterEqual(len(stairs), 1)
                self.assertAlmostEqual(
                    sum(f["stairs_distance_m"] for f in stairs),
                    r["steps_m"], delta=1.0)
                for f in stairs:
                    self.assertEqual(f["source"].get("highway"), "steps")
                found += 1
                if found >= 3:
                    break
        self.assertGreaterEqual(found, 1, "real walks must climb stairs")

    def test_no_plain_intersection_in_a_real_walk_is_a_crossing(self):
        """The crossing_m == 0 walks must have no cross facts either — over the
        same real map, not only the synthetic junction."""
        random.seed(11)
        nodes = sorted(self.graph.nodes().items())
        checked = 0
        for _ in range(6000):
            a = random.choice(nodes)[1]
            b = random.choice(nodes)[1]
            try:
                r = self.router.walk(a, b)
            except (NoRouteError, RouteError):
                continue
            if r["crossing_m"] == 0:
                self.assertEqual(
                    [f for f in r["maneuvers"] if f["type"] == "cross"], [],
                    "0 crossing metres implies 0 cross facts")
                checked += 1
                if checked >= 25:
                    break
        self.assertGreaterEqual(checked, 10)


class FootWireManeuversTest(unittest.TestCase):
    """/foot carries the facts additively (extension of the tests in test_foot_graph)."""

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
        """A deterministic walk with crossing_m > 0 in the fixture."""
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

    def test_foot_wire_carries_the_maneuver_facts(self):
        a, b = self._a_walk_that_crosses()
        status, body = self._get(f"/foot?from={b[1]},{b[0]}&to={a[1]},{a[0]}")
        self.assertEqual(status, 200)
        props = body["features"][0]["properties"]
        maneuvers = props["maneuvers"]
        self.assertIsInstance(maneuvers, list)
        self.assertGreaterEqual(len(maneuvers), 3)
        self.assertEqual(maneuvers[0]["type"], "depart")
        self.assertEqual(maneuvers[-1]["type"], "arrive")
        n_segments = len(props["classes"])
        for f in maneuvers:
            self.assertIn("type", f)
            self.assertIn("source", f)
            self.assertIn("distance_m", f)
            self.assertIn("distance_to_next_m", f)
            self.assertLessEqual(f["index"], n_segments, f)
        self.assertTrue(any(f["type"] == "cross" for f in maneuvers))
        # Every existing field still rides along untouched (additive wire).
        for key in ("distance_m", "duration_s", "steps_m", "crossing_m", "nodes",
                    "classes", "footway", "crossing", "lit", "snap", "snap_max_m",
                    "snap_within_preferred", "component", "component_nodes",
                    "detour_ratio", "walk_speed_ms"):
            self.assertIn(key, props, key)


if __name__ == "__main__":
    unittest.main()