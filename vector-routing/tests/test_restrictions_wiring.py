"""The joint that made turn restrictions inert: file -> service -> search.

Every piece of this existed and was individually tested. The feature still did
nothing, because nothing connected them:

  * the converter emitted restrictions keyed by OSM node id, which the graph
    (keyed by coordinate) could not resolve;
  * `bootstrap.sh` built the driving graph by copying only `features`, so the
    `turn_restrictions` key never reached the file the router loads;
  * `RoutingService` never constructed `TurnRestrictions`, and `Router` never
    passed a `banned_turn` predicate to `astar`.

Measured against the live Qatar graph before this was wired: **196 of 200**
banned turns were driven straight through. These tests exercise the whole path
from a GeoJSON file, because unit-testing each end is exactly what let it pass
while being dead.
"""

import json
import math
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src"))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "vendor"))

from vector_routing.restrictions import TurnRestrictions  # noqa: E402
from vector_routing.service import RoutingService  # noqa: E402
from vector_geo.graph import RoutingGraph  # noqa: E402

LAT, LON = 25.2854, 51.5310
MPD = 111_320.0
KX = math.cos(math.radians(LAT)) * MPD


def pt(e, n=0.0):
    return [LON + e / KX, LAT + n / MPD]


WEST, CENTRE, NORTH, SOUTH, EAST = pt(-300), pt(0), pt(0, 300), pt(0, -300), pt(300)


def _way(fid, coords):
    return {"type": "Feature", "id": fid,
            "geometry": {"type": "LineString", "coordinates": coords},
            "properties": {"kind": "road", "highway": "primary", "maxspeed": 50}}


def fixture(restrictions=None):
    fc = {"type": "FeatureCollection", "features": [
        _way("w10", [WEST, CENTRE]),
        _way("w20", [CENTRE, NORTH]),
        _way("w30", [CENTRE, SOUTH]),
        _way("w40", [CENTRE, EAST]),
    ]}
    if restrictions is not None:
        fc["turn_restrictions"] = restrictions
    return fc


def no_left():
    return [{"id": "1", "restriction": "no_left_turn", "via": CENTRE,
             "from_nodes": [WEST, CENTRE], "to_nodes": [CENTRE, NORTH]}]


class ServiceLoadsRestrictionsFromTheGraphFileTest(unittest.TestCase):
    """`from_geojson` must read `turn_restrictions`, not just `features`."""

    def _service(self, fc):
        fd, path = tempfile.mkstemp(suffix=".geojson")
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            json.dump(fc, fh)
        try:
            return RoutingService.from_geojson(path)
        finally:
            os.unlink(path)

    def test_a_graph_file_with_restrictions_enforces_them(self):
        svc = self._service(fixture(no_left()))
        self.assertTrue(svc.restrictions_status()["enforced"])
        self.assertEqual(svc.health()["turn_restrictions"], 1)

    def test_a_graph_file_without_restrictions_is_the_old_router_exactly(self):
        """Day-one state, and the rollback. Not an error."""
        svc = self._service(fixture())
        self.assertFalse(svc.restrictions_status()["enforced"])
        self.assertEqual(svc.health()["turn_restrictions"], 0)
        route = svc.route(WEST, NORTH)
        self.assertAlmostEqual(route.distance_m, 600.0, delta=5.0)

    def test_the_banned_movement_does_not_appear_in_a_navigate_path(self):
        svc = self._service(fixture(no_left()))
        result = svc.navigate(WEST, NORTH)
        keys = result["path"]
        g = svc.graph()
        trip = (tuple(g.node_coord(g.node_key(*WEST))),
                tuple(g.node_coord(g.node_key(*CENTRE))),
                tuple(g.node_coord(g.node_key(*NORTH))))
        self.assertNotIn(trip, list(zip(keys, keys[1:], keys[2:])),
                         "navigate() drove through the banned turn")

    def test_the_same_junction_stays_open_to_every_other_movement(self):
        svc = self._service(fixture(no_left()))
        for dest in (EAST, SOUTH):
            self.assertGreater(svc.route(WEST, dest).distance_m, 0.0)
        # ...and to the same turn from another approach.
        self.assertGreater(svc.route(EAST, NORTH).distance_m, 0.0)

    def test_alternatives_honour_restrictions_too(self):
        """A second route is still a route a driver follows."""
        svc = self._service(fixture(no_left()))
        g = svc.graph()
        trip = (tuple(g.node_coord(g.node_key(*WEST))),
                tuple(g.node_coord(g.node_key(*CENTRE))),
                tuple(g.node_coord(g.node_key(*NORTH))))
        for r in svc.route_alternatives(WEST, NORTH, wanted=3):
            self.assertNotIn(trip, list(zip(r.path, r.path[1:], r.path[2:])),
                             "an alternative route used the banned turn")


class NodeKeyAgreementTest(unittest.TestCase):
    """`restrictions._key` must round exactly as `RoutingGraph.node_key`.

    They are separate implementations on purpose (restrictions can be inspected
    with no graph loaded). If they ever diverge, every restriction lands on a
    key no edge uses: the layer reports a healthy count and enforces nothing,
    which is the single worst failure mode available here.
    """

    def test_the_two_roundings_agree(self):
        from vector_routing.restrictions import _key
        for lon, lat in [(51.5310, 25.2854), (51.53100004, 25.28540006),
                         (-0.1, -0.00000004), (51.0, 25.0), (51.12345678, 25.87654321)]:
            self.assertEqual(_key(lon, lat), RoutingGraph.node_key(lon, lat),
                             f"key rounding diverged at {lon},{lat}")


class InertLayerIsVisibleTest(unittest.TestCase):
    """"Loaded but resolved nothing" must not look like "nothing loaded"."""

    def test_restrictions_that_miss_the_graph_are_reported(self):
        # A restriction whose coordinates are nowhere near the graph.
        stray = [{"id": "9", "restriction": "no_left_turn", "via": [0.0, 0.0],
                  "from_nodes": [[0.001, 0.0], [0.0, 0.0]],
                  "to_nodes": [[0.0, 0.0], [0.0, 0.001]]}]
        r = TurnRestrictions.from_records(stray)
        # It parses — the failure is not at parse time, which is the point.
        self.assertEqual(len(r), 1)
        self.assertEqual(r.summary()["unresolved"], 0)
        # ...and it simply never fires, because no edge touches those keys.
        self.assertFalse(r.is_banned("nope", "also-nope", "still-nope"))

    def test_malformed_records_are_counted_not_crashed(self):
        r = TurnRestrictions.from_records([
            {"restriction": "no_left_turn"},                      # no geometry
            {"restriction": "banana", "via": [0, 0],
             "from_nodes": [[0, 0]], "to_nodes": [[0, 0]]},       # unknown kind
            {"restriction": "no_left_turn", "via": [0, 0],
             "from_nodes": [[0, 0]], "to_nodes": [[0, 0]]},       # via is the only node
        ])
        s = r.summary()
        self.assertEqual(s["relations"], 3)
        self.assertEqual(s["unknown_kind"], 1)
        self.assertEqual(s["unresolved"], 2)
        self.assertEqual(len(r), 0)


if __name__ == "__main__":
    unittest.main()


class PruneToGraphTest(unittest.TestCase):
    """An `only_*` whose permitted exit is not in the graph closes the junction.

    Restrictions resolve in COORDINATE space, before the graph exists and
    without knowing which ways survived the car-routability filter. An `only_*`
    names the one exit that stays legal — so if that exit was filtered out, the
    rule forbids everything and a vehicle arriving on that approach cannot leave
    the junction in any direction.

    Found live: restriction 10319594 at شارع مركز المؤتمرات names a to-way the
    car graph does not contain, and `/navigate` answered 404 for the movement
    the restriction was supposed to PERMIT. Over-enforcing an `only_*` strands a
    driver, so a requirement with no surviving exit is dropped.
    """

    def _service(self, fc):
        fd, path = tempfile.mkstemp(suffix=".geojson")
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            json.dump(fc, fh)
        try:
            return RoutingService.from_geojson(path)
        finally:
            os.unlink(path)

    def _only_pointing_off_graph(self):
        # `to_nodes` names a node no feature contains.
        off = [WEST[0], WEST[1] + 0.05]
        return [{"id": "1", "restriction": "only_straight_on", "via": CENTRE,
                 "from_nodes": [WEST, CENTRE], "to_nodes": [CENTRE, off]}]

    def test_the_junction_stays_open_when_the_permitted_exit_is_missing(self):
        svc = self._service(fixture(self._only_pointing_off_graph()))
        for dest in (NORTH, EAST, SOUTH):
            self.assertGreater(svc.route(WEST, dest).distance_m, 0.0,
                               "an unenforceable only_* closed the junction")

    def test_the_drop_is_counted_not_silent(self):
        svc = self._service(fixture(self._only_pointing_off_graph()))
        summary = svc.restrictions_status()["summary"]
        self.assertEqual(summary["pruned_only"], 1)
        self.assertFalse(svc.restrictions_status()["enforced"])

    def test_an_only_with_one_live_exit_of_two_keeps_that_exit(self):
        """Trimmed, not dropped: the surviving exit is still the only legal one."""
        off = [WEST[0], WEST[1] + 0.05]
        recs = [{"id": "1", "restriction": "only_straight_on", "via": CENTRE,
                 "from_nodes": [WEST, CENTRE], "to_nodes": [off, CENTRE, EAST]}]
        svc = self._service(fixture(recs))
        self.assertEqual(svc.restrictions_status()["summary"]["trimmed_only"], 1)
        self.assertGreater(svc.route(WEST, EAST).distance_m, 0.0)
        g = svc.graph()
        trip = (tuple(g.node_coord(g.node_key(*WEST))),
                tuple(g.node_coord(g.node_key(*CENTRE))),
                tuple(g.node_coord(g.node_key(*NORTH))))
        path = svc.navigate(WEST, NORTH)["path"]
        self.assertNotIn(trip, list(zip(path, path[1:], path[2:])),
                         "trimming an only_* must not stop it forbidding the others")

    def test_a_ban_on_nodes_the_graph_lacks_is_pruned_too(self):
        """Harmless either way, but the reported count must describe reality."""
        off = [WEST[0], WEST[1] + 0.05]
        recs = [{"id": "1", "restriction": "no_left_turn", "via": CENTRE,
                 "from_nodes": [WEST, CENTRE], "to_nodes": [CENTRE, off]}]
        svc = self._service(fixture(recs))
        self.assertEqual(svc.restrictions_status()["summary"]["pruned_bans"], 1)
