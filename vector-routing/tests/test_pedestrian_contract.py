"""The public /foot contract (V7.4 4B.4).

The final backend walking-contract stage before 4C. These tests pin the
contract that the Android client (and any other consumer) reads:

* **profile selection**: ``/foot?profile=general`` is the only wired profile;
  absent defaults to it; ANY other name is a loud 400/RouteError naming the
  valid set — never silently general. The response echoes the profile as
  ``walking_profile`` (distinct from the mode field ``profile: "foot"``).
* **contract version**: ``contract_version`` is a stable marker of the
  response shape, present and deterministic.
* **ETA/cost decision**: ``duration_s`` keeps its pre-4B.3 meaning — pure
  walking pace time, equal to ``cost.pace_s`` by construction — and the
  cost-inclusive reading is reported separately (``cost.cost_s``,
  ``cost.penalty_s``, ``cost.crossing.wait_s``). The decision is declared on
  /footz under ``contract.eta`` so a client never has to infer it.
* **affected vs observed**: ``cost.selection_factors`` lists what could have
  changed route selection (stairs + the profile's live flags); ``factor_s``
  only holds non-zero contributions; exposure blocks (stairs/incline/
  crossing/surface/lit/width/sidewalk) are observed regardless of flags, so
  OFF-by-default preferences never read as having affected a general route.
* **maneuver contract**: the 4B.2 plan is deterministic, its ``kind`` comes
  from the documented vocabulary, every maneuver carries
  index/distance_m/distance_to_next_m/road/source_facts, and crossing
  provenance (road ``null`` stays ``null``; ``type_source`` four-state) and
  stair facts ride through untouched — no invented road names, no invented
  attributes.
* **compatibility**: every pre-4B.3 /foot field remains with its meaning;
  segment arrays stay geometry-aligned; empty/absent data serializes
  consistently; split/no-route responses keep their shapes (and 400 for an
  unknown profile pre-empts them).
"""

import json
import os
import threading
import unittest
import urllib.request

from vector_routing.algorithms import build_graph_from_features
from vector_routing.errors import NoRouteError, RouteError
from vector_routing.foot_graph import FootRouter, build_foot_graph
from vector_routing.graph import RoutingGraph
from vector_routing.pedestrian_plan import build_pedestrian_plan
from vector_routing.service import FOOT_CONTRACT_VERSION, WALKING_PROFILES
from vector_routing.serve import make_server
from vector_routing.service import RoutingService

HERE = os.path.dirname(os.path.abspath(__file__))
CROSSING_FIXTURE = os.path.join(HERE, "data", "qatar-crossing-fixture.geojson")


def road(highway, coords, **props):
    """A converted road feature in the shape osm_to_geojson.py emits."""
    p = {"kind": "road", "highway": highway, "foot": True}
    p.update(props)
    return {"type": "Feature", "properties": p,
            "geometry": {"type": "LineString", "coordinates": coords}}


MANEUVER_KINDS = ("depart", "cross", "stairs", "turn_left", "turn_right",
                  "slight_left", "slight_right", "uturn", "continue", "arrive")


def _simple_foot_graph():
    """A linear walk with one marked crossing and one staircase."""
    return build_foot_graph([
        road("footway", [[51.5200, 25.2900], [51.5201, 25.2900]], footway="sidewalk"),
        road("footway", [[51.5201, 25.2900], [51.5202, 25.2900]],
             footway="crossing", crossing="marked"),
        road("footway", [[51.5202, 25.2900], [51.5203, 25.2900]], footway="sidewalk"),
        road("steps", [[51.5203, 25.2900], [51.5204, 25.2900]], step_count="12"),
        road("footway", [[51.5204, 25.2900], [51.5205, 25.2900]]),
    ])


def _split_foot_graph():
    """Two disconnected components > 400 m apart: B cannot be reached from A
    (a genuine pedestrian-network split, not a snap-adjacent pair)."""
    return build_foot_graph([
        road("footway", [[51.5200, 25.2900], [51.5201, 25.2900]]),
        road("footway", [[51.5250, 25.2900], [51.5251, 25.2900]]),
    ])


class _LiveServer(unittest.TestCase):
    """A threaded make_server around a RoutingService with a foot graph."""

    @classmethod
    def setUpClass(cls):
        svc = RoutingService(graph=build_graph_from_features([]))
        svc.set_foot_graph(cls.foot_graph())
        cls.server = make_server(0, svc)
        cls.port = cls.server.server_address[1]
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    @classmethod
    def foot_graph(cls):
        return _simple_foot_graph()

    def _get(self, path, raw=False):
        import http.client
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=30)
        try:
            conn.request("GET", path)
            resp = conn.getresponse()
            body = resp.read()
            if raw:
                return resp.status, body
            return resp.status, json.loads(body)
        finally:
            conn.close()


class ProfileContractTest(_LiveServer):
    """Selection of the pedestrian cost profile, and its failure mode."""

    def test_absent_profile_defaults_to_general_on_the_wire(self):
        status, body = self._get(
            "/foot?from=25.2900,51.5200&to=25.2900,51.5205")
        self.assertEqual(status, 200)
        props = body["features"][0]["properties"]
        self.assertEqual(props["walking_profile"], "general")
        self.assertEqual(props["profile"], "foot",
                         "the MODE profile field keeps its meaning")

    def test_explicit_general_profile_is_byte_identical_to_default(self):
        default = self._get("/foot?from=25.2900,51.5200&to=25.2900,51.5205")[1]
        explicit = self._get(
            "/foot?from=25.2900,51.5200&to=25.2900,51.5205&profile=general")[1]
        self.assertEqual(default, explicit,
                         "profile=general must be exactly the default route")

    def test_unknown_profile_is_a_loud_400_never_silent_general(self):
        for bad in ("comfort", "accessibility", "pleasant", "bogus"):
            status, body = self._get(
                f"/foot?from=25.2900,51.5200&to=25.2900,51.5205&profile={bad}")
            self.assertEqual(status, 400, bad)
            self.assertEqual(body["error"], "unknown walking profile")
            self.assertEqual(body["requested"], bad)
            self.assertEqual(body["valid_profiles"], ["general"])

    def test_400_for_unknown_profile_pre_empts_split_404(self):
        """Profile validation happens BEFORE routing: a bad profile on an
        unroutable pair is still a profile error, not a confusing no-route."""
        status, body = self._get(
            "/foot?from=25.2900,51.5200&to=25.2902,51.5202&profile=comfort")
        self.assertEqual(status, 400)
        self.assertEqual(body["error"], "unknown walking profile")

    def test_service_rejects_unwired_profiles_directly(self):
        svc = RoutingService(graph=build_graph_from_features([]))
        svc.set_foot_graph(_simple_foot_graph())
        with self.assertRaises(RouteError) as cm:
            svc.foot_route((51.5200, 25.2900), (51.5205, 25.2900), profile="comfort")
        self.assertIn("general", str(cm.exception))
        # The wired name works through the same path.
        ok = svc.foot_route((51.5200, 25.2900), (51.5205, 25.2900), profile="general")
        self.assertEqual(ok["walking_profile"], "general")

    def test_contract_version_is_stable_and_present(self):
        a = self._get("/foot?from=25.2900,51.5200&to=25.2900,51.5205")[1]
        b = self._get("/foot?from=25.2900,51.5200&to=25.2900,51.5205")[1]
        pa, pb = a["features"][0]["properties"], b["features"][0]["properties"]
        self.assertEqual(pa["contract_version"], FOOT_CONTRACT_VERSION)
        self.assertEqual(pa["contract_version"], pb["contract_version"])
        self.assertEqual(pa, pb, "identical requests -> byte-identical responses")

    def test_walking_profile_is_deterministic(self):
        seen = set()
        for _ in range(3):
            props = self._get("/foot?from=25.2900,51.5200&to=25.2900,51.5205")[1] \
                ["features"][0]["properties"]
            seen.add((props["walking_profile"], props["contract_version"]))
        self.assertEqual(seen, {("general", FOOT_CONTRACT_VERSION)})


class EtaCostContractTest(_LiveServer):
    """The decision: duration_s stays pure pace; cost-inclusive is separate."""

    def _walk(self):
        _, body = self._get("/foot?from=25.2900,51.5200&to=25.2900,51.5205")
        return body["features"][0]["properties"]

    def test_duration_s_equals_cost_pace_s_exactly(self):
        p = self._walk()
        self.assertEqual(round(p["duration_s"], 2), p["cost"]["pace_s"],
                         "duration_s IS the pure pace time (the contract's ETA)")
        self.assertNotEqual(p["cost"]["cost_s"], p["cost"]["pace_s"],
                            "the crossing wait plus incline must show as penalty")
        self.assertGreater(p["cost"]["penalty_s"], 0.0)

    def test_expected_crossing_delay_is_reported_separately(self):
        p = self._walk()
        # One marked crossing edge in this walk -> 8 s expected delay, on top
        # of the pure pace ETA, and explicitly NOT inside duration_s.
        self.assertEqual(p["cost"]["crossing"]["wait_s"], 8.0)
        self.assertEqual(p["cost"]["crossing"]["typed_edges"], 1)
        self.assertEqual(p["cost"]["penalty_s"],
                         round(p["cost"]["crossing"]["wait_s"], 2))

    def test_footz_declares_the_eta_decision(self):
        status, body = self._get("/footz")
        self.assertEqual(status, 200)
        contract = body["contract"]
        self.assertEqual(contract["version"], FOOT_CONTRACT_VERSION)
        self.assertEqual(contract["eta"]["field"], "duration_s")
        self.assertIn("pure walking pace", contract["eta"]["definition"])
        self.assertEqual(contract["eta"]["cost_inclusive_equivalent"], "cost.cost_s")
        self.assertEqual(contract["profiles"]["valid"], ["general"])
        self.assertEqual(contract["profiles"]["default"], "general")


class AffectedVsObservedTest(_LiveServer):
    """selection_factors vs exposure: OFF preferences never look causal."""

    def test_general_selection_factors_are_the_physical_set_only(self):
        p = self._walk()
        self.assertEqual(p["cost"]["selection_factors"],
                         ["stairs", "incline", "crossing_wait"])
        # This walk has a marked crossing (8 s wait) and a stair run (pace
        # uplift), so exactly those two factors contributed seconds.
        self.assertEqual(set(p["cost"]["factor_s"]), {"stairs", "crossing_wait"})

    def test_off_preferences_are_observed_but_never_costed(self):
        p = self._walk()
        self.assertNotIn("surface", p["cost"]["selection_factors"])
        self.assertNotIn("surface", p["cost"]["factor_s"])
        self.assertNotIn("lit", p["cost"]["selection_factors"])
        self.assertNotIn("width", p["cost"]["selection_factors"])
        self.assertNotIn("sidewalk", p["cost"]["selection_factors"])
        # The exposure blocks exist (observed regardless of flags) — including
        # the OFF preferences, so a client can SEE them without mistaking them
        # for costs.
        for block in ("stairs", "incline", "crossing", "surface", "lit",
                      "width", "sidewalk"):
            self.assertIn(block, p["cost"])

    def _walk(self):
        _, body = self._get("/foot?from=25.2900,51.5200&to=25.2900,51.5205")
        return body["features"][0]["properties"]


class ManeuverContractTest(_LiveServer):
    """The plan is deterministic, vocabulary-bounded and provenance-honest."""

    def test_every_maneuver_has_the_stable_semantics(self):
        p = self._walk()
        plan = p["maneuver_plan"]
        self.assertEqual(plan[0]["kind"], "depart")
        self.assertEqual(plan[-1]["kind"], "arrive")
        for m in plan:
            self.assertIn(m["kind"], MANEUVER_KINDS)
            self.assertIsInstance(m["index"], int)
            self.assertIsInstance(m["distance_m"], (int, float))
            self.assertIsInstance(m["distance_to_next_m"], (int, float))
            self.assertIn("road", m)
            self.assertIsInstance(m["source_facts"], list)
            self.assertTrue(all(isinstance(f, dict) for f in m["source_facts"]))

    def test_the_plan_is_deterministic_across_requests(self):
        a = self._get("/foot?from=25.2900,51.5200&to=25.2900,51.5205")[1]
        b = self._get("/foot?from=25.2900,51.5200&to=25.2900,51.5205")[1]
        self.assertEqual(a["features"][0]["properties"]["maneuver_plan"],
                         b["features"][0]["properties"]["maneuver_plan"])

    def test_crossing_provenance_and_uncertainty_are_preserved(self):
        p = self._walk()
        cross = next(m for m in p["maneuver_plan"] if m["kind"] == "cross")
        # This crossing's type comes from the WAY tag (crossing=marked).
        self.assertEqual(cross["crossing"]["type"], "marked")
        self.assertEqual(cross["crossing"]["type_source"], "way")
        # The crossed road is unknown (no road graph in this server): None
        # stays None — never a guessed road name.
        self.assertIsNone(cross["road"])
        self.assertIsNone(cross["crossed_road_source"])

    def test_stairs_attributes_ride_only_where_sourced(self):
        p = self._walk()
        stairs = next(m for m in p["maneuver_plan"] if m["kind"] == "stairs")
        self.assertEqual(stairs["stairs"]["step_count"], "12")
        self.assertNotIn("handrail", stairs["stairs"],
                         "absence stays absence — no invented handrail")

    def test_facts_and_plan_are_unchanged_by_the_profile_parameter(self):
        with_profile = self._get(
            "/foot?from=25.2900,51.5200&to=25.2900,51.5205&profile=general")[1]
        without = self._get("/foot?from=25.2900,51.5200&to=25.2900,51.5205")[1]
        self.assertEqual(
            with_profile["features"][0]["properties"]["maneuver_plan"],
            without["features"][0]["properties"]["maneuver_plan"])

    def _walk(self):
        _, body = self._get("/foot?from=25.2900,51.5200&to=25.2900,51.5205")
        return body["features"][0]["properties"]


class CompatibilityContractTest(_LiveServer):
    """Nothing pre-4B.4 moves; arrays align; empty/absent stays consistent."""

    PRE_43_FIELDS = [
        "profile", "distance_m", "duration_s", "steps_m", "crossing_m", "nodes",
        "classes", "footway", "crossing", "lit", "enclosed", "area", "maneuvers",
        "maneuver_plan", "snap", "snap_max_m", "snap_within_preferred",
        "component", "component_nodes", "straight_m", "requested_straight_m",
        "snap_straight_m", "route_m", "detour_ratio", "walk_speed_ms", "from", "to",
    ]
    NEW_FIELDS = ["contract_version", "walking_profile", "cost", "segment_cost_s"]

    def test_all_pre_43_fields_remain_with_their_meanings(self):
        p = self._walk()
        missing = [k for k in self.PRE_43_FIELDS if k not in p]
        self.assertEqual(missing, [])
        self.assertEqual(p["profile"], "foot")
        self.assertAlmostEqual(p["walk_speed_ms"], 1.35, delta=1e-6)

    def test_new_fields_are_additive(self):
        p = self._walk()
        for k in self.NEW_FIELDS:
            self.assertIn(k, p)
        self.assertEqual(p["contract_version"], FOOT_CONTRACT_VERSION)
        self.assertEqual(p["walking_profile"], "general")

    def test_segment_arrays_are_geometry_aligned(self):
        p = self._walk()
        n_segments = p["nodes"] - 1
        for key in ("classes", "footway", "crossing", "lit", "enclosed", "area",
                    "segment_cost_s"):
            self.assertEqual(len(p[key]), n_segments, key)

    def test_empty_absent_attributes_serialize_consistently(self):
        # A straight walk with no crossings/stairs in a fresh graph.
        svc = RoutingService(graph=build_graph_from_features([]))
        svc.set_foot_graph(build_foot_graph([
            road("footway", [[51.5200, 25.2900], [51.5201, 25.2900]]),
            road("footway", [[51.5201, 25.2900], [51.5202, 25.2900]]),
        ]))
        router = svc._foot_router
        result = router.walk((51.5200, 25.2900), (51.5202, 25.2900))
        p = result["cost"]
        # exposure blocks present with honest zeros
        self.assertEqual(p["crossing"]["edges"], 0)
        self.assertEqual(p["crossing"]["wait_s"], 0.0)
        self.assertEqual(p["stairs"]["edges"], 0)
        self.assertEqual(p["factor_s"], {}, "nothing penalized -> empty factor_s")
        self.assertEqual(p["selection_factors"], ["stairs", "incline", "crossing_wait"])
        self.assertEqual(p["penalty_s"], 0.0)
        # plan is exactly depart/arrive
        kinds = [m["kind"] for m in result["maneuver_plan"]]
        self.assertEqual(kinds, ["depart", "arrive"])

    def test_degenerate_zero_length_walk_serializes(self):
        svc = RoutingService(graph=build_graph_from_features([]))
        svc.set_foot_graph(build_foot_graph([
            road("footway", [[51.5200, 25.2900], [51.5201, 25.2900]]),
        ]))
        result = svc.foot_route((51.5200, 25.2900), (51.5200, 25.2900),
                                profile="general")
        from vector_routing.serve import foot_to_geojson
        body = foot_to_geojson(result, (51.5200, 25.2900), (51.5200, 25.2900))
        props = body["features"][0]["properties"]
        self.assertEqual(props["distance_m"], 0.0)
        self.assertEqual(props["nodes"], 1)
        self.assertEqual(props["segment_cost_s"], [])
        self.assertEqual(props["classes"], [])
        self.assertEqual(props["walking_profile"], "general")
        # Valid JSON through the serializer (the client skips empty segments).
        json.dumps(body)

    def _walk(self):
        _, body = self._get("/foot?from=25.2900,51.5200&to=25.2900,51.5205")
        return body["features"][0]["properties"]


class SplitAndNoRouteContractTest(unittest.TestCase):
    """Split/no-route responses keep their shapes under the final contract."""

    def test_a_split_pair_still_404s_with_the_reason(self):
        svc = RoutingService(graph=build_graph_from_features([]))
        svc.set_foot_graph(_split_foot_graph())
        from vector_routing.foot_graph import PedestrianNetworkSplitError
        with self.assertRaises(PedestrianNetworkSplitError):
            svc.foot_route((51.5200, 25.2900), (51.5250, 25.2900))

    def test_split_404_payload_keeps_its_shape_through_http(self):
        import threading
        import http.client
        svc = RoutingService(graph=build_graph_from_features([]))
        svc.set_foot_graph(_split_foot_graph())
        server = make_server(0, svc)
        port = server.server_address[1]
        t = threading.Thread(target=server.serve_forever, daemon=True)
        t.start()
        try:
            conn = http.client.HTTPConnection("127.0.0.1", port, timeout=30)
            conn.request("GET", "/foot?from=25.2900,51.5200&to=25.2900,51.5250")
            resp = conn.getresponse()
            body = json.loads(resp.read())
            conn.close()
            self.assertEqual(resp.status, 404)
            self.assertEqual(body["reason"], "pedestrian_network_split")
            self.assertIn("message", body)
            self.assertIn("origin_snap_m", body)
        finally:
            server.shutdown()
            server.server_close()

    def test_unknown_profile_400_on_a_split_pair(self):
        import threading
        import http.client
        svc = RoutingService(graph=build_graph_from_features([]))
        svc.set_foot_graph(_split_foot_graph())
        server = make_server(0, svc)
        port = server.server_address[1]
        t = threading.Thread(target=server.serve_forever, daemon=True)
        t.start()
        try:
            conn = http.client.HTTPConnection("127.0.0.1", port, timeout=30)
            conn.request("GET", "/foot?from=25.2900,51.5200&to=25.2900,51.5250&profile=comfort")
            resp = conn.getresponse()
            body = json.loads(resp.read())
            conn.close()
            self.assertEqual(resp.status, 400)
            self.assertEqual(body["error"], "unknown walking profile")
        finally:
            server.shutdown()
            server.server_close()


class FootzContractDeclarationTest(_LiveServer):
    def test_footz_carries_the_full_contract_declaration(self):
        status, body = self._get("/footz")
        self.assertEqual(status, 200)
        self.assertEqual(body["contract"]["version"], FOOT_CONTRACT_VERSION)
        self.assertEqual(body["contract"]["maneuver_kinds"], list(MANEUVER_KINDS))
        self.assertIn("cost_model", body)
        self.assertIn("interpretation", body)
        self.assertEqual(body["cost_model"]["profile"], "general")


class RealQatarContractTest(unittest.TestCase):
    """The committed real-Doha fixture under the final contract."""

    @classmethod
    def setUpClass(cls):
        with open(CROSSING_FIXTURE, encoding="utf-8") as fh:
            cls.fc = json.load(fh)
        cls.graph = build_foot_graph(cls.fc)
        cls.svc = RoutingService(graph=build_graph_from_features([]))
        cls.svc.set_foot_graph(cls.graph)

    def test_real_crossing_walk_carries_the_whole_contract(self):
        # A deterministic crossing walk in the fixture (seed 5, as 4B.1's
        # wire test).
        import random
        router = self.svc._foot_router
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
        self.assertIsNotNone(pair)
        result = self.svc.foot_route(pair[0], pair[1], profile="general")
        self.assertEqual(result["walking_profile"], "general")
        self.assertEqual(result["contract_version"], FOOT_CONTRACT_VERSION)
        c = result["cost"]
        self.assertEqual(c["selection_factors"], ["stairs", "incline", "crossing_wait"])
        self.assertGreaterEqual(c["crossing"]["edges"], 1)
        width = len(result["segment_cost_s"])
        self.assertEqual(width, len(result["route"].node_keys) - 1)
        self.assertEqual(len(result["segment_tags"]), width)
        self.assertEqual(result["maneuver_plan"][0]["kind"], "depart")
        self.assertEqual(result["maneuver_plan"][-1]["kind"], "arrive")
        self.assertAlmostEqual(c["pace_s"], result["route"].duration_s, delta=0.05)


if __name__ == "__main__":
    unittest.main()