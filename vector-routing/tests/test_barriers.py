"""Barrier catalog: parsing, the severing it drives, and the honesty it restores.

The recon measured the defect on real Qatar data: 8,715 ``barrier=*`` nodes,
8,573 of them on foot-routable ways, 2,164 of them gates tagged
``access=private/no`` or ``locked=yes`` — and Vector's walking router routed
through all of them, because the tag was dropped at ingestion. These tests pin
the fix:

* the catalog is parsed from the SEPARATE ``<region>_barriers.geojson``
  artifact (the same separate-artifact doctrine as signals and cameras);
* a ``pedestrian_effect=block`` node is severed OUT of the walking graph — no
  edge incident to it survives, so it cannot be walked to, from, or through;
* a ``pedestrian_effect=pass`` node (an unmarked gate, a bollard) keeps its
  edges — deleting every barrier node would sever 5,692 legitimate Qatar
  gates and is exactly what the task says not to do;
* the decision is read from the artifact, not re-derived here (the walking
  equivalent of trusting the ``foot`` boolean: one classifier, decided once,
  tested at ingestion);
* the real-Qatar fixture proves it end to end against genuine OSM: a walk
  that used to END at a private gate refuses now, while a walk through an
  unmarked gate still routes.

The fixture files were clipped from the whole-Qatar bake by
``.scratch/vector-product/V7.4-EVIDENCE/make_barrier_fixture.py`` — real OSM,
real ``osm_to_geojson.py``, coordinates preserved so a barrier point and the
way node it sits on share a routing node key.
"""

import json
import os
import tempfile
import unittest

from vector_routing.barriers import (
    BLOCK,
    PASS,
    BarrierCatalog,
    apply_barriers,
    blocked_node_keys,
    sever_blocked_nodes,
)
from vector_routing.errors import EndpointTooFarError, NoRouteError, RouteError
from vector_routing.foot_graph import FootRouter, build_foot_graph
from vector_routing.graph import RoutingGraph
from vector_routing.serve import default_barriers_path, default_foot_graph_path
from vector_routing.service import RoutingService

HERE = os.path.dirname(os.path.abspath(__file__))
FOOT_FIXTURE = os.path.join(HERE, "data", "qatar-foot-fixture.geojson")
BARRIER_FIXTURE = os.path.join(HERE, "data", "qatar-barriers-fixture.geojson")


def load_fixture(path):
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def barrier_pt(lon, lat, effect=PASS, **tags):
    """A barrier feature in the shape osm_to_geojson.py emits."""
    props = {"kind": "barrier", "barrier": tags.pop("barrier", "gate"),
             "pedestrian_effect": effect}
    props.update(tags)
    return {"type": "Feature", "id": tags.pop("id", "n1"),
            "geometry": {"type": "Point", "coordinates": [lon, lat]},
            "properties": props}


def footway(coords, **props):
    """A walkable way in the shape osm_to_geojson.py emits for a footway."""
    p = {"kind": "road", "highway": "footway", "foot": True}
    p.update(props)
    return {"type": "Feature", "properties": p,
            "geometry": {"type": "LineString", "coordinates": coords}}


class BarrierCatalogTest(unittest.TestCase):
    def test_missing_file_is_an_empty_catalog(self):
        cat = BarrierCatalog.from_path("/nonexistent/barriers.geojson")
        self.assertEqual(len(cat), 0)
        self.assertEqual(cat.stats["features"], 0)

    def test_only_kind_barrier_features_are_loaded(self):
        fc = {"features": [
            barrier_pt(51.53, 25.29, BLOCK),
            {"type": "Feature", "id": "w1",
             "geometry": {"type": "LineString", "coordinates": [[51.0, 25.0], [51.1, 25.0]]},
             "properties": {"kind": "road", "highway": "footway"}},
            {"type": "Feature", "id": "n9", "geometry": {"type": "Point",
             "coordinates": [51.54, 25.29]}, "properties": {"kind": "signal"}},
        ]}
        cat = BarrierCatalog.from_feature_collection(fc)
        self.assertEqual(len(cat), 1)
        self.assertEqual(cat.stats["features"], 1)

    def test_malformed_and_non_finite_coordinates_are_skipped(self):
        fc = {"features": [
            {"type": "Feature", "id": "n1", "geometry": {"type": "Point",
             "coordinates": ["bad", 25.0]}, "properties": {"kind": "barrier",
             "pedestrian_effect": BLOCK}},
            {"type": "Feature", "id": "n2", "geometry": {"type": "Point",
             "coordinates": [float("nan"), 25.0]}, "properties": {"kind": "barrier",
             "pedestrian_effect": BLOCK}},
        ]}
        cat = BarrierCatalog.from_feature_collection(fc)
        self.assertEqual(len(cat), 0)
        self.assertEqual(cat.stats["skipped"], 2)

    def test_an_unknown_effect_degrades_to_pass_and_is_counted(self):
        """Blocking on a mis-baked artifact severs legitimate crossings.

        The safe degradation is the known pre-barrier behaviour (nothing is
        severed) plus a count the operator can see on /footz.
        """
        fc = {"features": [barrier_pt(51.53, 25.29, "puzzling")]}
        cat = BarrierCatalog.from_feature_collection(fc)
        self.assertEqual(cat.stats["degraded"], 1)
        self.assertEqual(cat.stats["pass"], 1)
        self.assertEqual(cat._barriers[0]["effect"], PASS)
        self.assertEqual(cat.stats["block"], 0)

    def test_provenance_tags_survive_for_reporting(self):
        fc = {"features": [
            {"type": "Feature", "id": "n4833056498", "geometry": {"type": "Point",
             "coordinates": [51.4606573, 25.256606]},
             "properties": {"kind": "barrier", "barrier": "gate",
                            "pedestrian_effect": BLOCK, "access": "private"}},
        ]}
        cat = BarrierCatalog.from_feature_collection(fc)
        self.assertEqual(cat._barriers[0]["access"], "private")
        self.assertEqual(cat._barriers[0]["id"], "n4833056498")


class SeveringTest(unittest.TestCase):
    """The mechanics: a blocked node's edges are removed, a pass node's are not."""

    def gate_graph(self):
        """Two footways joined through an interior gate node G.

        A---G---B, where G is the shared node of the two ways — the exact
        shape OSM makes when a gate is a node ON a footway. Pre-sever a walk
        from A to B passes through G; post-sever the network is severed. The
        flanking stems keep the two sides departable after the sever, as they
        are in a real network (a gate never isolates a single node — the ways
        keep going past both ends of it).
        """
        return build_foot_graph([
            footway([[51.5190, 25.2900], [51.5200, 25.2900], [51.5220, 25.2900]]),
            footway([[51.5220, 25.2900], [51.5240, 25.2900], [51.5260, 25.2900]]),
        ])

    KEY_A = RoutingGraph.node_key(51.5200, 25.2900)
    KEY_G = RoutingGraph.node_key(51.5220, 25.2900)
    KEY_B = RoutingGraph.node_key(51.5240, 25.2900)

    def test_a_blocked_gate_is_severed_from_the_graph(self):
        g = self.gate_graph()
        cat = BarrierCatalog.from_feature_collection({"features": [
            barrier_pt(51.5220, 25.2900, BLOCK, access="private", id="n2")]})
        applied = apply_barriers(g, cat)
        self.assertEqual(applied["blocked"], 1)
        # All four incident directed edges are removed: the two out of the
        # gate (G->A, G->B) and the two into it (A->G, B->G).
        self.assertEqual(applied["edges_removed"], 4)
        self.assertEqual(len(g._adjacency[self.KEY_G]), 0,
                         "the gate node is no longer a departable node")
        self.assertFalse(
            any(k == self.KEY_G for (k, _w, _p) in g._adjacency.get(self.KEY_A, [])),
            "the edge INTO the gate from A is gone")
        self.assertFalse(
            any(k == self.KEY_G for (k, _w, _p) in g._adjacency.get(self.KEY_B, [])),
            "the edge INTO the gate from B is gone")

    def test_a_walk_that_used_to_go_through_the_gate_now_refuses(self):
        g = self.gate_graph()
        router = FootRouter(g)
        before = router.route_by_node(self.KEY_A, self.KEY_B)
        self.assertIn(self.KEY_G, before.node_keys,
                      "the fixture: the gate node is interior to the walk")

        g2 = self.gate_graph()
        apply_barriers(g2, BarrierCatalog.from_feature_collection({"features": [
            barrier_pt(51.5220, 25.2900, BLOCK, access="private")]}))
        blocked = FootRouter(g2)
        with self.assertRaises(NoRouteError):
            blocked.route_by_node(self.KEY_A, self.KEY_B)
        with self.assertRaises(NoRouteError):
            blocked.route_by_node(self.KEY_B, self.KEY_A)

    def test_an_unmarked_gate_stays_a_through_route(self):
        """OSM's permissive default: a gate with no access tag is passable."""
        for effect in (PASS, "gate-no-access"):
            g = self.gate_graph()
            cat = BarrierCatalog.from_feature_collection({"features": [
                barrier_pt(51.5220, 25.2900, effect)]})
            apply_barriers(g, cat)
            self.assertEqual(len(g._adjacency[self.KEY_G]), 2, effect)
            route = FootRouter(g).route_by_node(self.KEY_A, self.KEY_B)
            self.assertIn(self.KEY_G, route.node_keys, effect)

    def test_a_blocked_node_is_not_a_snap_candidate_but_a_pass_gate_is(self):
        """Endpoint snapping only offers departable nodes (see _candidates).

        The gate node is severed, so standing on it, the walker's nearest
        departable node is a neighbour, not the gate — and a walk across the
        locked point is refused as a split, not routed through it. The same
        coordinate with a PASS gate keeps its edges and IS the snap target.
        """
        blocked = self.gate_graph()
        apply_barriers(blocked, BarrierCatalog.from_feature_collection({"features": [
            barrier_pt(51.5220, 25.2900, BLOCK)]}))
        # Cross the gate: honest refusal, the two sides are now separate
        # pedestrian networks (PedestrianNetworkSplitError IS a NoRouteError).
        with self.assertRaises(NoRouteError) as cm:
            FootRouter(blocked).walk((51.5200, 25.2900), (51.5240, 25.2900))
        self.assertIn("separate pedestrian networks", str(cm.exception))
        # Stand right on a PASS gate on the same geometry: it is departable
        # and it is what the snap picks.
        opened = self.gate_graph()
        apply_barriers(opened, BarrierCatalog.from_feature_collection({"features": [
            barrier_pt(51.5220, 25.2900, PASS)]}))
        router = FootRouter(opened)
        src, _tgt, cid = router._snap_pair((51.5220, 25.2900), (51.5260, 25.2900))
        self.assertEqual(src, self.KEY_G,
                         "a pass gate is a valid place to start a walk")

    def test_unmatched_and_inert_are_reported(self):
        g = self.gate_graph()
        cat = BarrierCatalog.from_feature_collection({"features": [
            # Off the walking network (no node here at all).
            barrier_pt(51.99, 26.99, BLOCK),
            # A real blocked gate.
            barrier_pt(51.5220, 25.2900, BLOCK),
        ]})
        blocked, stats = blocked_node_keys(g, cat)
        self.assertEqual(blocked, {self.KEY_G})
        self.assertEqual(stats["matched"], 1)
        self.assertEqual(stats["unmatched"], 1)

    def test_car_routing_is_unaffected_by_a_barrier_bake(self):
        """Barriers are read only by the pedestrian graph loader.

        The car graph is built from ``<region>_roads.geojson``, which never
        contains barrier features (the separate-artifact doctrine), and
        ``load_foot_graph`` only ever mutates the graph it is handed. The
        byte-identical car bake before/after is asserted in the stage doc;
        here the claim is structural: the car router in the same service
        still routes after a barrier bake is loaded for /foot.
        """
        car = build_foot_graph([  # a car-routable residential street
            footway([[51.5200, 25.2900], [51.5210, 25.2900]],
                    foot=True, car=True, highway="residential")])
        svc = RoutingService(graph=car)
        svc.load_foot_graph(FOOT_FIXTURE, barriers_path=BARRIER_FIXTURE)
        r = svc.route((51.5200, 25.2900), (51.5210, 25.2900))
        self.assertGreater(r.distance_m, 0.0)


class FixtureBarrierTest(unittest.TestCase):
    """The real-Qatar regression: 148 barrier features clipped from the bake."""

    @classmethod
    def setUpClass(cls):
        cls.foot_fc = load_fixture(FOOT_FIXTURE)
        cls.barrier_fc = load_fixture(BARRIER_FIXTURE)
        # The fixture's own guard: it must actually contain what the tests
        # below claim about the real data, or every assertion passes on air.
        effects = [f["properties"]["pedestrian_effect"]
                   for f in cls.barrier_fc["features"]]
        cls.block_count = effects.count(BLOCK)
        cls.pass_count = effects.count(PASS)
        cls.unsevered = build_foot_graph(cls.foot_fc)
        cls.severed = build_foot_graph(cls.foot_fc)
        cls.catalog = BarrierCatalog.from_feature_collection(cls.barrier_fc)
        cls.stats = apply_barriers(cls.severed, cls.catalog)

    def test_the_fixture_is_real_and_dense(self):
        self.assertGreater(self.unsevered.edge_count(), 1500,
                           "the pocket is a real walking network")
        self.assertGreaterEqual(self.block_count, 50,
                                f"block count was {self.block_count}")
        self.assertGreaterEqual(self.pass_count, 20,
                                f"pass count was {self.pass_count}")

    def test_blocked_nodes_were_severed_and_reported(self):
        self.assertGreater(self.stats["blocked"], 10)
        self.assertGreater(self.stats["edges_removed"], 20)
        self.assertGreater(self.stats["matched"], 10)

    def test_a_walk_that_used_to_end_at_a_private_gate_now_refuses(self):
        """The recon's wrong claim, on real data, before and after.

        Gate n4833056498 (barrier=gate, access=private) sits on a foot-way
        node. Before barriers existed the router walked 1,056 m to it and made
        the gate node the END of the walk — the gate was a walkable destination.
        After severing, that node has no incident edges and the same walk
        cannot exist.
        """
        gate_key = RoutingGraph.node_key(51.4606573, 25.256606)
        start_key = RoutingGraph.node_key(51.4546954, 25.2607795)

        before = FootRouter(self.unsevered).route_by_node(start_key, gate_key)
        self.assertIn(gate_key, before.node_keys)
        self.assertTrue(self.unsevered._adjacency.get(gate_key),
                        "pre-sever the gate node was departable")
        self.assertIn(
            "n4833056498",
            [f["id"] for f in self.barrier_fc["features"]
             if f["geometry"]["coordinates"] == [51.4606573, 25.256606]])

        severed_router = FootRouter(self.severed)
        self.assertEqual(len(self.severed._adjacency.get(gate_key, [])), 0,
                         "the private gate is no longer a routable node")
        with self.assertRaises((NoRouteError, RouteError)):
            severed_router.route_by_node(start_key, gate_key)

    def test_an_unmarked_gate_is_still_crossed_after_severing(self):
        """Gate n4732450842 (barrier=gate, no access tag) keeps its edges.

        A walk across its two neighbours still runs THROUGH the gate node —
        the legitimate-crossing half of the task's acceptance box.
        """
        pass_key = RoutingGraph.node_key(51.4619766, 25.2542603)
        catalog_ids = [b["id"] for b in self.catalog._barriers]
        self.assertIn("n4732450842", catalog_ids)
        self.assertEqual(
            len(self.severed._adjacency.get(pass_key, [])), 2,
            "the unmarked gate has both edges after severing")
        router = FootRouter(self.severed)
        neigh = [to for to, _w, _p in self.severed.neighbors(pass_key)]
        self.assertEqual(len(neigh), 2)
        route = router.route_by_node(neigh[0], neigh[1])
        self.assertIn(pass_key, route.node_keys,
                      "the legitimate gate is crossed, not blocked")

    def test_more_routes_survive_than_are_refused(self):
        """Most of the pocket still routes: severing blocks gates, not streets."""
        router = FootRouter(self.severed)
        nodes = sorted(self.severed.nodes().items())[::11]
        ok = refused = 0
        for (_ka, a), (_kb, b) in zip(nodes, nodes[3:]):
            try:
                router.walk(a, b)
                ok += 1
            except (NoRouteError, RouteError, EndpointTooFarError):
                refused += 1
            if ok + refused >= 40:
                break
        self.assertGreater(ok, refused,
                           f"only {ok} of {ok + refused} pocket walks survived")


class ServiceBarrierTest(unittest.TestCase):
    """The service wiring: load_foot_graph applies the catalog when given one."""

    def _svc(self):
        return RoutingService(graph=RoutingGraph())

    def test_load_foot_graph_applies_the_barrier_catalog(self):
        svc = self._svc()
        svc.load_foot_graph(FOOT_FIXTURE, barriers_path=BARRIER_FIXTURE)
        status = svc.foot_status()
        self.assertTrue(status["available"])
        barriers = status["barriers"]
        self.assertTrue(barriers["loaded"])
        self.assertGreater(barriers["block"], 50)
        self.assertGreater(barriers["pass"], 20)
        self.assertGreater(barriers["blocked_nodes"], 10)
        self.assertGreater(barriers["edges_removed"], 20)

    def test_without_a_catalog_the_graph_is_not_severed(self):
        svc = self._svc()
        svc.load_foot_graph(FOOT_FIXTURE)
        status = svc.foot_status()
        self.assertFalse(status["barriers"]["loaded"])
        self.assertEqual(status["barriers"]["blocked_nodes"], 0)
        # And a blocked gate node from the fixture is still routable.
        gate_key = RoutingGraph.node_key(51.4606573, 25.256606)
        r = svc.foot_route((51.4546954, 25.2607795), (51.4606573, 25.256606))
        self.assertIn(gate_key, r["route"].node_keys)

    def test_a_missing_barrier_file_is_a_valid_noop(self):
        svc = self._svc()
        svc.load_foot_graph(FOOT_FIXTURE, barriers_path="/nonexistent/barriers.geojson")
        status = svc.foot_status()
        self.assertFalse(status["barriers"]["loaded"])
        self.assertEqual(status["barriers"]["features"], 0)

    def test_missing_foot_graph_with_barriers_still_reports_unavailable(self):
        svc = self._svc()
        svc.load_foot_graph("/nonexistent/foot.geojson",
                            barriers_path=BARRIER_FIXTURE)
        self.assertFalse(svc.foot_status()["available"])

class SiblingPathTest(unittest.TestCase):
    """The no-compose-change convention: barriers ride beside the foot graph."""

    def test_default_barriers_path_derives_beside_the_foot_graph(self):
        with tempfile.TemporaryDirectory() as d:
            foot = os.path.join(d, "qatar_foot.geojson")
            barriers = os.path.join(d, "qatar_barriers.geojson")
            self.assertIsNone(default_barriers_path(foot))  # sibling absent
            with open(barriers, "w", encoding="utf-8") as fh:
                fh.write("{}")
            self.assertEqual(default_barriers_path(foot), barriers)
        self.assertIsNone(default_barriers_path("/x/y.routing"))
        self.assertIsNone(default_barriers_path("/x/qatar_roads.geojson"),
                          "the roads file derives the FOOT graph, not the barriers")

    def test_the_full_ladder_from_graph_to_barriers(self):
        """--graph -> _foot.geojson -> _barriers.geojson, one derivation each."""
        with tempfile.TemporaryDirectory() as d:
            roads = os.path.join(d, "qatar_roads.geojson")
            foot = os.path.join(d, "qatar_foot.geojson")
            barriers = os.path.join(d, "qatar_barriers.geojson")
            for p in (foot, barriers):
                with open(p, "w", encoding="utf-8") as fh:
                    fh.write("{}")
            self.assertEqual(default_foot_graph_path(roads), foot)
            self.assertEqual(default_barriers_path(
                default_foot_graph_path(roads)), barriers)


if __name__ == "__main__":
    unittest.main()