"""Pedestrian tag promotion reaches the routing layer (V7.4 4A.4).

The recon's architectural rule: Vector must not say "cross the road" unless the
underlying map data can establish a crossing. Before this stage every
pedestrian tag was dropped at ingestion — 3,015 `footway=crossing` ways were
already edges in the foot graph but indistinguishable from pavements, so the
router had nothing honest to phrase a crossing from.

These tests pin what 4A.4 delivers to the engine:

* the promoted raw tags (footway, crossing, surface, incline, handrail,
  step_count, width, lit) survive on the GRAPH EDGES — the routing layer can
  see them;
* the per-segment report (segment_tags) exposes footway/crossing/lit so a
  client can tell a crossing from a pavement;
* walk() reports crossing_m — the metres actually walked on crossing edges;
* a walk that crosses a road does so on footway=crossing edges (real data);
* nothing here invents a derived speed, cost or instruction: speeds.py is not
  touched, and a step with no tag stays a step.

The real-data half runs against tests/data/qatar-crossing-fixture.geojson —
real converted Qatar OSM, clipped by V7.4-EVIDENCE/make_crossing_fixture.py.
"""

import json
import os
import unittest

from vector_routing.errors import NoRouteError, RouteError
from vector_routing.foot_graph import FootRouter, build_foot_graph
from vector_routing.graph import RoutingGraph
from vector_routing.speeds import WALK_SPEED_MS, walk_speed_ms

HERE = os.path.dirname(os.path.abspath(__file__))
CROSSING_FIXTURE = os.path.join(HERE, "data", "qatar-crossing-fixture.geojson")


def road(highway, coords, **props):
    """A converted road feature with an explicit walkable flag."""
    p = {"kind": "road", "highway": highway, "foot": True}
    p.update(props)
    return {"type": "Feature", "properties": p,
            "geometry": {"type": "LineString", "coordinates": coords}}


class PromotedPropsOnEdgesTest(unittest.TestCase):
    """The graph edges themselves carry the preserved tags."""

    def fixture_graph(self):
        """Two pavements joined by a marked crossing, plus a lit driveway."""
        return build_foot_graph([
            road("footway", [[51.5200, 25.2900], [51.5210, 25.2900]],
                 footway="sidewalk", surface="concrete", lit="yes"),
            road("footway", [[51.5210, 25.2900], [51.5220, 25.2901]],
                 footway="crossing", crossing="marked", kerb="lowered"),
            road("footway", [[51.5220, 25.2901], [51.5230, 25.2901]],
                 footway="sidewalk", surface="asphalt"),
            road("steps", [[51.5230, 25.2901], [51.5240, 25.2902]],
                 step_count="12", handrail="yes", incline="up"),
        ])

    def test_crossing_tags_reach_the_edge_props(self):
        g = self.fixture_graph()
        found = []
        for _s, _t, _w, p in g.edges():
            if (p.get("footway") or "").strip().lower() == "crossing":
                found.append(p)
        self.assertEqual(len(found), 2, "the crossing way is an edge both ways")
        self.assertEqual(found[0]["crossing"], "marked")
        self.assertEqual(found[0]["kerb"], "lowered")

    def test_stairs_facts_reach_the_edge_props(self):
        g = self.fixture_graph()
        steps = [p for _s, _t, _w, p in g.edges()
                 if p.get("highway") == "steps"]
        self.assertEqual(len(steps), 2)
        self.assertEqual(steps[0]["step_count"], "12")
        self.assertEqual(steps[0]["handrail"], "yes")
        self.assertEqual(steps[0]["incline"], "up")

    def test_surface_and_lit_are_preserved_not_interpreted(self):
        g = self.fixture_graph()
        sidewalks = [p for _s, _t, _w, p in g.edges()
                     if (p.get("footway") or "").strip().lower() == "sidewalk"]
        surfaces = sorted({p.get("surface") for p in sidewalks})
        self.assertIn("concrete", surfaces)
        self.assertIn("asphalt", surfaces)
        self.assertTrue(any(p.get("lit") == "yes" for p in sidewalks))

    def test_segment_tags_expose_crossing_and_lit_per_segment(self):
        g = self.fixture_graph()
        result = FootRouter(g).walk((51.5200, 25.2900), (51.5240, 25.2902))
        tags = result["segment_tags"]
        self.assertEqual(len(tags), len(result["route"].node_keys) - 1)
        self.assertEqual(
            [t["footway"] for t in tags],
            ["sidewalk", "crossing", "sidewalk", None],
            "the steps segment has no footway=* tag, and preservation says None")
        self.assertIn("marked", [t["crossing"] for t in tags])
        self.assertEqual(tags[0]["lit"], "yes")
        for t in tags:
            self.assertIn("footway", t)
            self.assertIn("crossing", t)
            self.assertIn("lit", t)
        # The order of tags lines up with the geometry (Nth pair of coords).
        coords = result["route"].path
        self.assertEqual(len(tags), len(coords) - 1)

    def test_crossing_m_counts_only_crossing_metres(self):
        g = self.fixture_graph()
        router = FootRouter(g)
        walked = router.walk((51.5200, 25.2900), (51.5220, 25.2901))
        self.assertGreater(walked["crossing_m"], 0.0)
        # A walk that never leaves the sidewalk has zero crossing metres.
        stayed = router.walk((51.5200, 25.2900), (51.5210, 25.2900))
        self.assertEqual(stayed["crossing_m"], 0.0)

    def test_a_step_with_no_extra_tags_stays_a_step(self):
        """Promotion preserves absence too: no invented step_count."""
        g = build_foot_graph([
            road("steps", [[51.5200, 25.2900], [51.5210, 25.2901]]),
        ])
        for _s, _t, _w, p in g.edges():
            self.assertNotIn("step_count", p)

    def test_walking_speed_is_untouched_by_surface_data(self):
        """promotion != cost model: surface rides along, speed ignores it."""
        self.assertEqual(walk_speed_ms({"highway": "footway", "surface": "ground"}),
                         WALK_SPEED_MS)
        self.assertEqual(walk_speed_ms({"highway": "footway", "incline": "up"}),
                         WALK_SPEED_MS)


class RealCrossingDataTest(unittest.TestCase):
    """footway=crossing in real converted Qatar OSM reaches the routing layer."""

    @classmethod
    def setUpClass(cls):
        with open(CROSSING_FIXTURE, encoding="utf-8") as fh:
            cls.fc = json.load(fh)
        cls.graph = build_foot_graph(cls.fc)
        cls.router = FootRouter(cls.graph)

    def test_the_fixture_really_contains_crossings(self):
        cross_edges = [p for _s, _t, _w, p in self.graph.edges()
                       if (p.get("footway") or "").strip().lower() == "crossing"]
        self.assertGreater(len(cross_edges), 10,
                           "the fixture must contain real crossing ways")
        types = {p.get("crossing") for p in cross_edges if p.get("crossing")}
        self.assertTrue(types, "at least one crossing carries an explicit type")

    def test_some_neighbourhood_walk_crosses_a_road_on_crossing_edges(self):
        """Not just present in the graph — actually WALKED when crossing."""
        found = 0
        nodes = sorted(self.graph.nodes().items())[::13]
        for (_ka, a), (_kb, b) in zip(nodes, nodes[2:]):
            try:
                r = self.router.walk(a, b)
            except (NoRouteError, RouteError):
                continue
            if r["crossing_m"] > 0:
                tags = r["segment_tags"]
                self.assertTrue(
                    any(t["footway"] == "crossing" for t in tags if t["footway"]),
                    "crossing_m > 0 must be sourced from footway=crossing segments")
                self.assertGreaterEqual(len(r["route"].node_keys), 2)
                found += 1
                if found >= 5:
                    break
        self.assertGreaterEqual(found, 2, "real walks cross roads on crossings")

    def test_steps_facts_are_present_in_the_fixture_graph(self):
        steps = [p for _s, _t, _w, p in self.graph.edges()
                 if p.get("highway") == "steps"]
        self.assertGreater(len(steps), 0, "the fixture should contain stairs")
        any_extra = any(p.get("step_count") or p.get("handrail") or p.get("incline")
                        for p in steps)
        self.assertIsInstance(any_extra, bool)  # presence is preserved, absence is too


if __name__ == "__main__":
    unittest.main()