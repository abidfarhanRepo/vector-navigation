"""V7 traffic lights: the signal-to-crossing association and its honesty rules.

What these tests pin, as behaviour a consumer observes:

* a signal is attached to a crossing by GRAPH NODE IDENTITY — the signal's own
  coordinate is a vertex of the crossing way — and never by proximity, so a
  signal 20 m away that is not on the crossing is not claimed;
* a crossing with no signal carries an EMPTY list, which is not a claim that the
  crossing is uncontrolled;
* a signal that is on the graph but not on a crossing is reachable by the census
  and appears on no cross fact;
* a signal that is not a vertex of the walk's graph is not claimed at all —
  unassociated is a real state, not something to paper over;
* the fact reaches the plan and therefore the wire, carrying ``id``, ``source``
  and ``node`` and NO timing-shaped key;
* a deployment with no catalog routes exactly as before.

Every fixture here is a hand-built graph with invented coordinates; the real
Qatar bake is exercised by the evidence scripts, not by unit tests.
"""

import unittest

from vector_geo.graph import RoutingGraph

from vector_routing.foot_graph import FootRouter
from vector_routing.pedestrian_maneuvers import (
    CrossingCatalog,
    build_pedestrian_maneuvers,
)
from vector_routing.pedestrian_plan import build_pedestrian_plan
from vector_routing.signals import SignalCatalog, SignalIndex, osm_source

# One degree of longitude at the equator; the fixtures are small enough that the
# flat approximation is well inside every tolerance used here.
M_PER_DEG = 111_320.0


def _node(g, lon, lat):
    return g.add_node(lon, lat)


def _edge(g, a, b, **props):
    g._add_edge(a, b, M_PER_DEG * 0.0001, props)
    g._add_edge(b, a, M_PER_DEG * 0.0001, props)


def _crossing_graph(signal_on="crossing", extra_on=None):
    """A pavement, a crossing over it, and a pavement on the far side.

    Laid out on a small grid so the geometry is trivially checkable: the walk
    goes east along a ``footway``, crosses 11 m north on a
    ``footway=crossing`` carrying an explicit ``crossing=*`` type, then
    continues east.

    ``signal_on`` names the graph node a surveyed signal stands on —
    ``"crossing"`` (the far kerb, a vertex OF the crossing way), ``"approach"``
    (the last node before it, ~15.7 m away and NOT on the crossing), or
    ``None``. ``extra_on`` places a second signal on another node.

    Signals are given the exact coordinate of the node they stand on, because
    that coincidence IS the association the implementation reads.
    """
    g = RoutingGraph()
    a0 = _node(g, 0.0000, 0.0000)   # approach: 15.7 m from the crossing
    a1 = _node(g, 0.0001, 0.0000)   # the kerb the crossing starts at
    c1 = _node(g, 0.0001, 0.0001)   # the far kerb, 11.1 m north
    b1 = _node(g, 0.0002, 0.0001)   # the pavement beyond

    _edge(g, a0, a1, highway="footway", footway="sidewalk")
    _edge(g, a1, c1, highway="footway", footway="crossing", crossing="traffic_signals")
    _edge(g, c1, b1, highway="footway", footway="sidewalk")

    nodes = {"approach": a0, "crossing": c1, "kerb": a1}
    by_id = {"approach": "n1001", "crossing": "n1001", "kerb": "n1001", "extra": "n2002"}
    signals = []
    for slot, node in (("crossing", signal_on), ("extra", extra_on)):
        if node is None:
            continue
        coord = g.node_coord(nodes[node])
        signals.append({"id": by_id[slot], "lon": coord[0], "lat": coord[1],
                        "direction": None})
    return g, SignalCatalog(signals), [a0, a1, c1, b1]


class SignalIndexTest(unittest.TestCase):

    def test_signal_on_a_graph_node_is_indexed(self):
        g, catalog, _keys = _crossing_graph()
        idx = SignalIndex.from_catalog(catalog, g)
        self.assertEqual(len(idx), 1)
        entry = idx.at(g.node_key(0.0001, 0.0001))
        self.assertEqual(entry["id"], "n1001")
        self.assertEqual(entry["source"], "osm:node:1001")

    def test_signal_off_the_graph_is_unassociated_not_snapped(self):
        """A signal near a node but not ON it is not indexed.

        This is the whole difference between identity and a radius: 5 m off is
        not a mapper statement that the signal is at this vertex, and rounding
        the position to make it match would be inventing the association.
        """
        g, _cat, _keys = _crossing_graph(signal_on=None)
        catalog = SignalCatalog([
            {"id": "n1001", "lon": 0.0001 + 5.0 / M_PER_DEG, "lat": 0.0001, "direction": None},
        ])
        self.assertEqual(len(SignalIndex.from_catalog(catalog, g)), 0)

    def test_no_catalog_and_no_graph_are_both_empty_indexes(self):
        g, catalog, _keys = _crossing_graph()
        self.assertEqual(len(SignalIndex.from_catalog(None, g)), 0)
        self.assertEqual(len(SignalIndex.from_catalog(catalog, None)), 0)
        self.assertEqual(len(SignalIndex.from_catalog(SignalCatalog([]), g)), 0)

    def test_osm_source_labels_only_real_osm_node_ids(self):
        self.assertEqual(osm_source("n123456"), "osm:node:123456")
        self.assertEqual(osm_source("fixture-signal"), "signal:fixture-signal")


class CrossFactSignalTest(unittest.TestCase):

    def _plan(self, graph, signals, keys):
        facts = build_pedestrian_maneuvers(
            keys, graph, crossings=CrossingCatalog(), road_graph=None,
            signals=signals)
        return facts, build_pedestrian_plan(facts)

    def test_surveyed_signal_on_the_crossing_reaches_the_plan(self):
        g, catalog, keys = _crossing_graph()
        idx = SignalIndex.from_catalog(catalog, g)
        facts, plan = self._plan(g, idx, keys)
        crosses = [f for f in facts if f["type"] == "cross"]
        self.assertEqual(len(crosses), 1)
        self.assertEqual([s["id"] for s in crosses[0]["signals"]], ["n1001"])

        cross = [m for m in plan if m["kind"] == "cross"]
        self.assertEqual(len(cross), 1)
        sig = cross[0]["crossing"]["signals"]
        self.assertEqual([s["id"] for s in sig], ["n1001"])
        self.assertEqual(sig[0]["source"], "osm:node:1001")

    def test_crossing_with_no_signal_carries_an_empty_list(self):
        """Empty is 'the map places no signal here', not 'this crossing is uncontrolled'."""
        g, _cat, keys = _crossing_graph(signal_on=None)
        idx = SignalIndex.from_catalog(SignalCatalog([]), g)
        facts, plan = self._plan(g, idx, keys)
        self.assertEqual([f for f in facts if f["type"] == "cross"][0]["signals"], [])
        self.assertEqual([m for m in plan if m["kind"] == "cross"][0]["crossing"]["signals"], [])

    def test_no_catalog_at_all_leaves_the_crossing_fact_unchanged(self):
        """A pre-V7 deployment: the cross fact is exactly what it always was."""
        g, _cat, keys = _crossing_graph()
        facts, plan = self._plan(g, None, keys)
        self.assertEqual([f for f in facts if f["type"] == "cross"][0]["signals"], [])
        cross = [m for m in plan if m["kind"] == "cross"][0]
        self.assertEqual(cross["crossing"]["signals"], [])
        # Nothing else about the maneuver moved.
        self.assertEqual(cross["crossing"]["type"], "traffic_signals")
        self.assertEqual(cross["crossing"]["type_source"], "way")

    def test_signal_near_but_not_on_the_crossing_is_not_claimed(self):
        """20 m away on the same pavement: not on the crossing, so not claimed.

        This is the defect a proximity rule would ship — a walker told to expect
        a signal at a crossing the mapper never associated one with.
        """
        g, catalog, keys = _crossing_graph(signal_on="approach")
        idx = SignalIndex.from_catalog(catalog, g)
        # It IS associated: it stands on a real vertex of the walking network.
        self.assertEqual(idx.at(g.node_key(0.0000, 0.0000))["id"], "n1001")
        facts, _plan = self._plan(g, idx, keys)
        # And it is NOT on the crossing, so the crossing claims no signal.
        self.assertEqual([f for f in facts if f["type"] == "cross"][0]["signals"], [])

    def test_signal_on_the_graph_but_not_on_a_crossing_sits_at_a_junction(self):
        """Both halves of the census, on one fixture.

        A junction signal is a real, correctly associated signal that no
        crossing carries. It must be counted as associated and NOT as
        on-crossing, because a walker is never told to cross at it.
        """
        g, catalog, keys = _crossing_graph(signal_on="crossing", extra_on="approach")
        router = FootRouter(g, signals=SignalIndex.from_catalog(catalog, g))
        census = router.signal_census()
        self.assertEqual(census["associated"], 2)
        self.assertEqual(census["on_crossing"], 1)
        self.assertEqual(census["at_junction"], 1)

        facts = build_pedestrian_maneuvers(
            keys, g, crossings=CrossingCatalog(),
            signals=SignalIndex.from_catalog(catalog, g))
        claimed = [s["id"] for f in facts if f["type"] == "cross" for s in f["signals"]]
        self.assertEqual(claimed, ["n1001"])

    def test_the_signal_carries_its_own_vertex_on_the_walk_geometry(self):
        """The marker's placement comes from the backend, not client matching.

        A map places the signal at `geometry[index]`. The index must therefore
        be the signal's OWN vertex — an index into the geometry the walk ships,
        inside the crossing's own span — or the marker lands somewhere the
        survey says no signal is.
        """
        g, catalog, keys = _crossing_graph()
        idx = SignalIndex.from_catalog(catalog, g)
        facts = build_pedestrian_maneuvers(
            keys, g, crossings=CrossingCatalog(), signals=idx)
        cross = [f for f in facts if f["type"] == "cross"][0]
        entry = cross["signals"][0]
        # The signal's vertex is the crossing's far kerb, and its coordinate is
        # exactly that vertex's.
        self.assertEqual(entry["index"], cross["leave_index"])
        coord = g.node_coord(keys[entry["index"]])
        self.assertEqual(
            (round(coord[0], 7), round(coord[1], 7)),
            tuple(float(v) for v in entry["node"].split(",")),
        )

    def test_the_wire_fact_carries_no_timing_shaped_key(self):
        """The invariant, on the entry that actually ships.

        Location and provenance only: there is no phase, cycle, offset or state
        key, and no numeric field at all that a client could render as a
        countdown.
        """
        g, catalog, keys = _crossing_graph()
        idx = SignalIndex.from_catalog(catalog, g)
        facts = build_pedestrian_maneuvers(
            keys, g, crossings=CrossingCatalog(), signals=idx)
        entry = [f for f in facts if f["type"] == "cross"][0]["signals"][0]
        self.assertEqual(set(entry.keys()), {"id", "source", "node", "index"})
        self.assertFalse([k for k in entry if any(
            h in k.lower() for h in
            ("time", "timing", "phase", "cycle", "green", "red", "amber",
             "state", "second", "countdown", "wait"))])


class SignalCensusTest(unittest.TestCase):

    def test_census_is_empty_without_a_catalog(self):
        g, _cat, _keys = _crossing_graph(signal_on=None)
        self.assertEqual(
            FootRouter(g).signal_census(),
            {"associated": 0, "on_crossing": 0, "at_junction": 0})

    def test_rebinding_recomputes_the_census(self):
        """The service loads the foot graph before the catalog; set_signals must win.

        Pins the load order the deployment actually uses: a foot router built
        with no index, then handed one, must report the new association — and
        its walks must use it.
        """
        g, catalog, keys = _crossing_graph()
        router = FootRouter(g)
        self.assertEqual(router.signal_census()["on_crossing"], 0)
        router.set_signals(SignalIndex.from_catalog(catalog, g))
        self.assertEqual(router.signal_census(), {
            "associated": 1, "on_crossing": 1, "at_junction": 0})
        result = router.walk((0.0000, 0.0000), (0.0002, 0.0001))
        plan = result["maneuver_plan"]
        self.assertEqual(
            [s["id"] for m in plan if m["kind"] == "cross"
             for s in m["crossing"]["signals"]],
            ["n1001"])


if __name__ == "__main__":
    unittest.main()
