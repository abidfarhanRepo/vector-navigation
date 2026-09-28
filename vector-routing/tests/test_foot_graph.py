"""The pedestrian graph: what it contains, what it must never contain, and how long a walk takes.

The classifier half of this was already true and already tested before any of
this existed: ``vector_ingestion.classify.is_pedestrian_routable`` has known
since the footway-in-the-car-graph fix that a staircase is walkable and a
motorway is not. What did NOT exist was anywhere for that answer to go. The one
routing graph Vector baked was filtered to ``properties.car``, so the 40,437
Qatar ways that are walkable and not drivable were discarded before the router
saw them, and "is this walkable?" was a question the engine could answer and
never act on.

So these tests are deliberately mostly about the GRAPH rather than the rules. A
passing classifier beside a car-only graph is exactly the state this phase
found, and only a graph-level assertion can tell the two apart.

The real-data cases run against ``data/msheireb.geojson`` — 710 features of
genuine OSM, converted by the real ``osm_to_geojson.py`` from the Qatar extract
and clipped to Msheireb Downtown Doha. Real data because the interesting
failures are not constructible by hand: Al Rayyan Road is in that file as 25
car-only ``primary`` ways *and* as three walkable frontage roads that share its
name, and no fixture anyone invented would have both.
"""

import json
import os
import threading
import unittest
import urllib.error
import urllib.request

from vector_routing.algorithms import build_graph_from_features
from vector_routing.errors import EndpointTooFarError, NoRouteError, RouteError
from vector_routing.foot_graph import (
    SNAP_MAX_M,
    SNAP_PREFERRED_M,
    FootRouter,
    PedestrianNetworkSplitError,
    build_foot_graph,
    is_foot_feature,
    label_components,
)
from vector_routing.graph import RoutingGraph
from vector_routing.router import Router
from vector_routing.serve import make_server
from vector_routing.service import RoutingService
from vector_routing.speeds import WALK_SPEED_MS, walk_speed_ms

MSHEIREB = os.path.join(os.path.dirname(__file__), "data", "msheireb.geojson")

# Classes a person is never routed along in the Qatar data.
#
# `motorway` is absent from the classifier's PEDESTRIAN_CLASSES and no Qatar
# motorway carries `foot=yes`, so it cannot reach the walking graph at all.
#
# The other arterial classes are NOT on this list, and the reason is a fact
# about the data rather than a concession. 543 Qatar ways are `primary`,
# `trunk`, `secondary` or a link of one AND explicitly tagged `foot=yes` —
# 135 primary, 99 primary_link, 109 secondary_link, 87 secondary, 68 trunk,
# 42 trunk_link, 3 motorway_link — and they carry `sidewalk=right` or
# `sidewalk=both` beside it. Those are arterials with a mapped pavement, signed
# walkable by the people who surveyed them. `is_pedestrian_routable` admits
# exactly them, via its explicit-permission rule, and excluding them here would
# be overruling the survey with a guess: Al Corniche and Jasim Bin Hamad Street
# have pavements, and a pedestrian graph that refuses to use them cannot get
# anyone along the Corniche.
NEVER_WALKABLE = ("motorway",)


def feat(highway, coords, **props):
    """A converted road feature, in the shape osm_to_geojson.py emits."""
    p = {"kind": "road", "highway": highway}
    p.update(props)
    return {"type": "Feature", "properties": p,
            "geometry": {"type": "LineString", "coordinates": coords}}


def load_msheireb():
    with open(MSHEIREB, encoding="utf-8") as fh:
        return json.load(fh)


def edges_along(graph, keys):
    """The edge props actually traversed by a path of node keys."""
    out = []
    for a, b in zip(keys, keys[1:]):
        for to, w, p in graph.neighbors(a):
            if to == b:
                out.append((w, p))
                break
    return out


class FootGraphMembershipTest(unittest.TestCase):
    """What gets into the pedestrian graph, decided by ingestion and nothing else."""

    def test_a_staircase_is_a_foot_edge_and_not_a_car_edge(self):
        """The inverse of the car graph's headline bug.

        380 flights of steps were once edges in the DRIVING graph. The fix
        removed them from it; this is the other half, and the half that was
        missing — they have to end up somewhere, or a footbridge is a wall.
        """
        features = [feat("steps", [[51.52, 25.29], [51.5201, 25.2901]], foot=True, car=False)]
        foot = build_foot_graph(features)
        self.assertEqual(foot.edge_count(), 2, "a staircase is walkable in both directions")

        car = build_graph_from_features(
            [f for f in features if f["properties"].get("car")])
        self.assertEqual(car.edge_count(), 0, "a staircase is never a car edge")

    def test_a_motorway_is_not_in_the_pedestrian_graph(self):
        features = [feat("motorway", [[51.52, 25.29], [51.53, 25.29]], foot=False, car=True)]
        self.assertEqual(build_foot_graph(features).edge_count(), 0)

    def test_a_motorway_tagged_foot_yes_is(self):
        """Causeways and some bridges. The classifier already allows it; the graph must too."""
        features = [feat("trunk", [[51.52, 25.29], [51.53, 25.29]], foot=True, car=True)]
        self.assertEqual(build_foot_graph(features).edge_count(), 2)

    def test_a_missing_foot_flag_means_not_walkable(self):
        """The opposite default to the car filter, on purpose.

        ``bootstrap.sh`` treats a missing ``car`` flag as drivable so an old
        extract degrades to the previous behaviour. There is no previous
        pedestrian behaviour to degrade to, and "unknown means walkable" fails
        SILENTLY — by routing someone down a motorway — while "unknown means
        not walkable" fails loudly, with an empty graph and a /foot that says so.
        """
        self.assertFalse(is_foot_feature(feat("motorway", [[51.5, 25.2], [51.6, 25.2]])))
        self.assertEqual(build_foot_graph([feat("footway", [[51.5, 25.2], [51.6, 25.2]])]).edge_count(), 0)

    def test_a_non_road_feature_is_never_a_foot_edge(self):
        park = {"type": "Feature", "properties": {"kind": "park", "foot": True},
                "geometry": {"type": "LineString", "coordinates": [[51.5, 25.2], [51.6, 25.2]]}}
        self.assertFalse(is_foot_feature(park))

    def test_a_one_way_street_is_walkable_in_both_directions(self):
        """`oneway` is a rule for traffic, and a person is not traffic.

        30,012 of Qatar's foot-routable ways carry it. Honouring it would route
        a walk up a one-way street the long way round the block — a plausible
        looking answer to a question nobody asked.
        """
        features = [feat("residential", [[51.52, 25.29], [51.5205, 25.29]],
                         foot=True, car=True, oneway="yes")]
        foot = build_foot_graph(features)
        self.assertEqual(foot.edge_count(), 2)
        # And the vehicular reading is dropped, not merely ignored: a property
        # that is present but unenforced is a trap for the next reader.
        for _src, _dst, _w, props in foot.edges():
            self.assertNotIn("oneway", props)

    def test_the_car_graph_is_untouched_by_any_of_this(self):
        """The car graph is built from the same features by the same builder."""
        features = [
            feat("motorway", [[51.52, 25.29], [51.53, 25.29]], foot=False, car=True),
            feat("footway", [[51.52, 25.29], [51.521, 25.291]], foot=True, car=False),
        ]
        car = build_graph_from_features([f for f in features if f["properties"]["car"]])
        foot = build_foot_graph(features)
        self.assertEqual(car.edge_count(), 2)
        self.assertEqual(foot.edge_count(), 2)
        self.assertEqual(
            set(car.nodes()) & set(foot.nodes()), {RoutingGraph.node_key(51.52, 25.29)},
            "the two graphs share only the junction they genuinely share",
        )


class WalkingSpeedTest(unittest.TestCase):
    def test_level_ground_is_the_flat_walking_speed(self):
        for c in ("footway", "pedestrian", "residential", "service", "path", "corridor"):
            self.assertEqual(walk_speed_ms({"highway": c}), WALK_SPEED_MS, c)

    def test_maxspeed_is_a_statement_about_vehicles_and_is_ignored(self):
        self.assertEqual(walk_speed_ms({"highway": "residential", "maxspeed": "50"}), WALK_SPEED_MS)

    def test_steps_are_slower_but_not_impossible(self):
        """Penalised, not banned — the difference between a longer walk and no walk.

        A staircase is frequently the ONLY link between a footbridge and the
        street, or a car park deck and the mall beside it. Banning stairs does
        not lengthen the route, it disconnects the graph and answers "no route"
        for a journey a person makes in thirty seconds.
        """
        steps = walk_speed_ms({"highway": "steps"})
        self.assertGreater(steps, 0.0, "stairs must remain traversable")
        self.assertLess(steps, WALK_SPEED_MS, "stairs must be worth avoiding")

    def test_a_router_prefers_a_short_detour_to_a_staircase(self):
        """...and takes the stairs when the detour is long enough."""
        # A staircase from A to B, plus a level detour of a settable length.
        def graph_with_detour(detour_lon):
            return build_foot_graph([
                feat("steps", [[51.5200, 25.2900], [51.5202, 25.2900]], foot=True),
                feat("footway", [[51.5200, 25.2900], [51.5200, detour_lon]], foot=True),
                feat("footway", [[51.5200, detour_lon], [51.5202, detour_lon]], foot=True),
                feat("footway", [[51.5202, detour_lon], [51.5202, 25.2900]], foot=True),
            ])
        src = RoutingGraph.node_key(51.5200, 25.2900)
        tgt = RoutingGraph.node_key(51.5202, 25.2900)

        tight = graph_with_detour(25.29005)   # a very short way round
        used = edges_along(tight, FootRouter(tight).route_by_node(src, tgt).node_keys)
        self.assertNotIn("steps", [p.get("highway") for _w, p in used],
                         "a cheap level detour should beat the stairs")

        far = graph_with_detour(25.2930)      # a long way round
        used = edges_along(far, FootRouter(far).route_by_node(src, tgt).node_keys)
        self.assertIn("steps", [p.get("highway") for _w, p in used],
                      "stairs must still be usable when the alternative is worse")

    def test_a_staircase_that_is_the_only_link_is_still_routable(self):
        g = build_foot_graph([
            feat("footway", [[51.5200, 25.2900], [51.5201, 25.2900]], foot=True),
            feat("steps", [[51.5201, 25.2900], [51.5202, 25.2900]], foot=True),
            feat("footway", [[51.5202, 25.2900], [51.5203, 25.2900]], foot=True),
        ])
        route = FootRouter(g).route_by_node(
            RoutingGraph.node_key(51.5200, 25.2900), RoutingGraph.node_key(51.5203, 25.2900))
        self.assertGreater(route.distance_m, 0)


class MsheirebRealDataTest(unittest.TestCase):
    """Real Doha, real OSM, real footways."""

    # A pair either side of Msheireb Downtown, ~290 m apart in a straight line.
    # Chosen because the CAR answer between the same two points runs down Al
    # Rayyan Road — so this is a genuine divergence between the two graphs, not
    # a pair that happens to have no arterial near it.
    FROM = (51.522626, 25.289291)   # (lon, lat)
    TO = (51.520109, 25.290704)

    @classmethod
    def setUpClass(cls):
        fc = load_msheireb()
        cls.fc = fc
        cls.foot = build_foot_graph(fc)
        cls.car = build_graph_from_features(
            [f for f in fc["features"]
             if f.get("properties", {}).get("kind") == "road"
             and f.get("properties", {}).get("car")])

    def test_the_fixture_really_does_contain_pedestrian_infrastructure(self):
        """Guards the tests below: all of them pass trivially on an empty graph."""
        self.assertGreater(self.foot.edge_count(), 5000)
        classes = {p.get("highway") for _s, _t, _w, p in self.foot.edges()}
        self.assertTrue({"footway", "pedestrian", "steps"} <= classes, classes)

    def test_no_motorway_is_an_edge_in_the_pedestrian_graph(self):
        """Graph separation, asserted over real data rather than a constructed case."""
        present = {p.get("highway") for _s, _t, _w, p in self.foot.edges()}
        self.assertEqual(present & set(NEVER_WALKABLE), set())

    def test_the_arterials_that_are_present_are_the_ones_with_pavements(self):
        """A `primary` in a walking graph looks wrong until you read the survey.

        Al Khaleej Street is tagged `foot=yes` with `sidewalk=right` in OSM, and
        is the only named arterial in this fixture that reaches the foot graph.
        Asserted so that an arterial appearing here is a deliberate, reviewed
        state rather than something that quietly crept in.
        """
        arterials = {
            (p.get("highway"), p.get("name:en") or p.get("name"))
            for _s, _t, _w, p in self.foot.edges()
            if p.get("highway") in ("motorway", "motorway_link", "trunk", "trunk_link",
                                    "primary", "primary_link", "secondary", "secondary_link")
        }
        self.assertEqual(arterials, {("primary", "Al Khaleej Street"), ("primary_link", None)})

    def test_the_arterial_is_in_the_car_graph_and_absent_from_the_foot_graph(self):
        """The point of two graphs, stated as one assertion.

        Al Rayyan Road's ``primary`` carriageway is 25 ways in this fixture. A
        car uses it; a person may not walk along it. If it appeared in both,
        there would be no reason to have built a second graph.
        """
        def arterial_named(graph):
            return {k for _s, _t, _w, p in graph.edges()
                    for k in [(p.get("name:en") or p.get("name"))]
                    if k == "Al Rayyan Road" and p.get("highway") == "primary"}

        self.assertEqual(arterial_named(self.car), {"Al Rayyan Road"})
        self.assertEqual(arterial_named(self.foot), set())

    def test_a_300m_msheireb_walk_uses_footways_and_not_al_rayyan_road(self):
        """The acceptance case from the V7 assessment."""
        result = FootRouter(self.foot).walk(self.FROM, self.TO)
        route = result["route"]

        # Roughly 300 m: a real walk is longer than the straight line (~290 m)
        # because pavements go round things. Bounded rather than pinned — the
        # geometry is real data and must be free to change with the next
        # extract without this test becoming a transcription of it.
        self.assertGreater(route.distance_m, 250)
        self.assertLess(route.distance_m, 600)
        self.assertLess(result["snap_max_m"], 25, "both endpoints are on the network")

        used = edges_along(self.foot, route.node_keys)
        self.assertTrue(used)
        walked_on = {props.get("highway") for _w, props in used}
        self.assertTrue(
            walked_on <= {"footway", "pedestrian", "path", "steps", "corridor",
                          "cycleway", "living_street", "residential", "service"},
            f"the walk should be on pedestrian infrastructure, got {walked_on}")
        for _w, props in used:
            self.assertNotEqual(props.get("name:en") or props.get("name"), "Al Rayyan Road")

    def test_the_car_between_the_same_two_points_does_use_al_rayyan_road(self):
        """Without this the test above proves nothing.

        A walk that avoids the arterial is only interesting if the arterial was
        the obvious way to go. It is: the driving answer between these two
        points spends most of itself on Al Rayyan Road.
        """
        route = Router(self.car).route(self.FROM, self.TO)
        on_arterial = sum(
            w for w, p in edges_along(self.car, route.node_keys)
            if (p.get("name:en") or p.get("name")) == "Al Rayyan Road")
        self.assertGreater(on_arterial, 100.0)

    def test_a_pedestrian_route_never_touches_an_edge_only_a_car_may_use(self):
        """Swept over many real pairs, not just the headline one.

        The check is against the CAR-ONLY edge set rather than against a list of
        highway classes, because that is the invariant that actually matters and
        the one a future change could break: the two graphs are built from one
        conversion, and nothing but ``properties.foot`` decides which ways cross
        into the walking one.
        """
        car_only = {
            (s, t) for s, t, _w, _p in self.car.edges()
        } - {(s, t) for s, t, _w, _p in self.foot.edges()}
        self.assertGreater(len(car_only), 100, "the fixture must contain car-only edges")

        router = FootRouter(self.foot)
        nodes = sorted(self.foot.nodes().items())[::97]
        checked = 0
        for (_ka, a), (_kb, b) in zip(nodes, nodes[7:]):
            try:
                route = router.route(a, b)
            except (NoRouteError, RouteError):
                continue
            for x, y in zip(route.node_keys, route.node_keys[1:]):
                self.assertNotIn((x, y), car_only)
            for _w, props in edges_along(self.foot, route.node_keys):
                self.assertNotIn(props.get("highway"), NEVER_WALKABLE)
            checked += 1
            if checked >= 25:
                break
        self.assertGreaterEqual(checked, 10, "not enough real routes were exercised")

    def test_walking_duration_matches_distance_over_the_walking_speed(self):
        """duration_s within ±20 % of distance_m / 1.35, over real routes."""
        router = FootRouter(self.foot)
        nodes = sorted(self.foot.nodes().items())[::89]
        samples = 0
        for (_ka, a), (_kb, b) in zip(nodes, nodes[11:]):
            try:
                route = router.route(a, b)
            except (NoRouteError, RouteError):
                continue
            if route.distance_m < 50:
                continue
            expected = route.distance_m / WALK_SPEED_MS
            self.assertAlmostEqual(
                route.duration_s / expected, 1.0, delta=0.20,
                msg=f"{route.distance_m:.0f} m took {route.duration_s:.0f} s, "
                    f"expected ~{expected:.0f} s",
            )
            samples += 1
            if samples >= 30:
                break
        self.assertGreaterEqual(samples, 10, "not enough real routes were measured")


class FootServiceTest(unittest.TestCase):
    def test_a_service_without_a_pedestrian_graph_says_so(self):
        """And says it as a 503 with a reason, not a 404 reading "not connected"."""
        svc = RoutingService(graph=build_graph_from_features(
            [feat("primary", [[51.52, 25.29], [51.53, 25.29]], car=True)]))
        self.assertFalse(svc.foot_status()["available"])
        with self.assertRaises(RouteError):
            svc.foot_route((51.52, 25.29), (51.53, 25.29))

    def test_loading_a_graph_makes_it_available(self):
        svc = RoutingService(graph=RoutingGraph())
        svc.set_foot_graph(build_foot_graph(load_msheireb()))
        status = svc.foot_status()
        self.assertTrue(status["available"])
        self.assertGreater(status["edges"], 5000)
        self.assertEqual(status["walk_speed_ms"], WALK_SPEED_MS)

    def test_load_foot_graph_tolerates_a_missing_file(self):
        svc = RoutingService(graph=RoutingGraph())
        self.assertEqual(svc.load_foot_graph("/nonexistent/foot.geojson"), 0)
        self.assertFalse(svc.foot_status()["available"])

    def test_load_foot_graph_reads_a_real_bake(self):
        svc = RoutingService(graph=RoutingGraph())
        self.assertGreater(svc.load_foot_graph(MSHEIREB), 5000)
        result = svc.foot_route(MsheirebRealDataTest.FROM, MsheirebRealDataTest.TO)
        self.assertGreater(result["route"].distance_m, 250)

    def test_a_car_only_bake_loaded_as_a_foot_graph_is_reported_unavailable(self):
        """The silent-no-op failure: the right file name, the wrong filter."""
        import tempfile
        fc = {"type": "FeatureCollection", "features": [
            feat("motorway", [[51.52, 25.29], [51.53, 25.29]], car=True)]}
        with tempfile.NamedTemporaryFile("w", suffix=".geojson", delete=False) as fh:
            json.dump(fc, fh)
            path = fh.name
        try:
            svc = RoutingService(graph=RoutingGraph())
            self.assertEqual(svc.load_foot_graph(path), 0)
            self.assertFalse(svc.foot_status()["available"])
        finally:
            os.unlink(path)


class FootHttpTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        svc = RoutingService(graph=build_graph_from_features(
            [f for f in load_msheireb()["features"]
             if f.get("properties", {}).get("kind") == "road"
             and f.get("properties", {}).get("car")]))
        svc.set_foot_graph(build_foot_graph(load_msheireb()))
        cls.server = make_server(0, svc)
        cls.port = cls.server.server_address[1]
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def _get(self, path):
        with urllib.request.urlopen(f"http://127.0.0.1:{self.port}{path}") as r:
            return r.status, json.loads(r.read())

    def test_foot_returns_the_geometry_and_the_two_numbers_a_client_needs(self):
        status, body = self._get(
            "/foot?from=25.289291,51.522626&to=25.290704,51.520109")
        self.assertEqual(status, 200)
        props = body["features"][0]["properties"]
        self.assertEqual(props["profile"], "foot")
        self.assertGreater(props["distance_m"], 250)
        self.assertGreater(props["duration_s"], 0)
        self.assertAlmostEqual(
            props["duration_s"] / (props["distance_m"] / WALK_SPEED_MS), 1.0, delta=0.20)
        self.assertEqual(props["walk_speed_ms"], WALK_SPEED_MS)
        coords = body["features"][0]["geometry"]["coordinates"]
        self.assertGreater(len(coords), 2)
        # GeoJSON order, same as every other endpoint here.
        self.assertTrue(all(51.0 < lon < 52.0 and 25.0 < lat < 26.0 for lon, lat in coords))

    def test_foot_describes_every_segment_so_a_client_can_model_shade(self):
        """The per-segment arrays line up with the geometry, one entry per pair.

        The on-device shade model assumes a facade whose height depends on the
        road class. Without this the client would have to assume ONE class for
        the whole walk, which on a route that touches an arterial means
        promising shade along a road the model treats as unshadeable.
        """
        status, body = self._get(
            "/foot?from=25.289291,51.522626&to=25.290704,51.520109")
        self.assertEqual(status, 200)
        feat = body["features"][0]
        props = feat["properties"]
        coords = feat["geometry"]["coordinates"]

        for key in ("classes", "enclosed", "area"):
            self.assertIn(key, props)
            self.assertEqual(
                len(props[key]), len(coords) - 1,
                f"{key} must carry one entry per geometry segment")

        # Real classes, not blanks: this route is walked on named OSM ways.
        self.assertTrue(any(c for c in props["classes"]))
        # Every class present is one the pedestrian classifier admits, and a
        # motorway is never one of them.
        self.assertNotIn("motorway", props["classes"])
        self.assertTrue(all(isinstance(b, bool) for b in props["enclosed"]))
        self.assertTrue(all(isinstance(b, bool) for b in props["area"]))

    def test_segment_tags_and_steps_agree_about_the_same_walk(self):
        """`steps_m` and the `classes` array must describe one route, not two."""
        svc = RoutingService(graph=build_graph_from_features([]))
        svc.set_foot_graph(build_foot_graph(load_msheireb()))
        result = svc.foot_route((51.522626, 25.289291), (51.520109, 25.290704))
        tags = result["segment_tags"]
        self.assertEqual(len(tags), len(result["route"].node_keys) - 1)
        stepped = sum(1 for t in tags if t["highway"] == "steps")
        if result["steps_m"] > 0:
            self.assertGreater(stepped, 0)
        else:
            self.assertEqual(stepped, 0)

    def test_foot_rejects_a_malformed_endpoint_the_same_way_route_does(self):
        for bad in ("/foot?from=&to=25.29,51.52",
                    "/foot?from=nonsense&to=25.29,51.52",
                    "/foot?from=95.0,51.52&to=25.29,51.52"):
            with self.assertRaises(urllib.error.HTTPError) as cm:
                self._get(bad)
            self.assertEqual(cm.exception.code, 400, bad)

    def test_footz_reports_what_is_loaded(self):
        status, body = self._get("/footz")
        self.assertEqual(status, 200)
        self.assertTrue(body["available"])
        self.assertGreater(body["edges"], 5000)

    def test_footz_reports_the_crossing_fact_sources(self):
        """V7.4 4B.1: whether cross facts can be type/road-enriched (the
        artifact is separate from the graph, so only these numbers say so)."""
        _status, body = self._get("/footz")
        self.assertIn("crossings", body)
        self.assertIn("features", body["crossings"])
        self.assertIn("crossing_road_source", body)
        # This suite's service has no artifact and no car graph wired, so the
        # sources are absent and the status must SAY so — the doctrine: a
        # loaded-but-empty and never-loaded are distinguishable.
        self.assertEqual(body["crossings"]["features"], 0)
        self.assertIsInstance(body["crossing_road_source"], bool)

    def test_footz_reports_how_fragmented_the_network_is(self):
        """The census, without which a split graph looks healthy (V7.4).

        Node and edge counts do not distinguish Qatar's pedestrian graph --
        2,811 components, the largest holding 32% of nodes -- from a connected
        one. Both report a large plausible graph and both answer short walks.
        """
        _status, body = self._get("/footz")
        for key in ("components", "largest_component_nodes",
                    "largest_component_share", "snap_preferred_m", "snap_max_m"):
            self.assertIn(key, body, key)
        self.assertGreaterEqual(body["components"], 1)
        self.assertLessEqual(body["largest_component_share"], 1.0)
        # The pedestrian limits, not the car's 800/2000.
        self.assertEqual(body["snap_preferred_m"], SNAP_PREFERRED_M)
        self.assertEqual(body["snap_max_m"], SNAP_MAX_M)

    def test_foot_carries_the_fields_that_make_a_long_walk_readable(self):
        """A client must be able to tell a correct long walk from a broken one."""
        _status, body = self._get(
            "/foot?from=25.289291,51.522626&to=25.290704,51.520109")
        props = body["features"][0]["properties"]
        for key in ("snap_within_preferred", "component", "component_nodes",
                    "straight_m", "detour_ratio"):
            self.assertIn(key, props, key)
        self.assertIsInstance(props["snap_within_preferred"], bool)
        self.assertGreater(props["component_nodes"], 0)
        self.assertGreater(props["straight_m"], 0.0)
        # V7.4 4A.4: the walking wire carries crossing facts and the raw
        # per-segment pedestrian tags.
        self.assertIn("crossing_m", props, "crossing_m")
        n_segments = len(props["classes"])
        for key in ("footway", "crossing", "lit"):
            self.assertIn(key, props, key)
            self.assertEqual(
                len(props[key]), n_segments,
                f"{key} must carry one entry per geometry segment")
        # V7.4 4B.1: the wire carries the structured maneuver facts, positioned
        # on the geometry (each index is a vertex index of the LineString).
        maneuvers = props["maneuvers"]
        self.assertIsInstance(maneuvers, list)
        self.assertGreaterEqual(len(maneuvers), 2)
        self.assertEqual(maneuvers[0]["type"], "depart")
        self.assertEqual(maneuvers[-1]["type"], "arrive")
        for f in maneuvers:
            self.assertIn("source", f, f)
            self.assertIn("distance_m", f, f)
            self.assertIn("distance_to_next_m", f, f)
            self.assertLessEqual(f["index"], n_segments, f)
        # V7.4 4A: the three distances are explicit, and the ratio is measured
        # snap-to-snap so it can never read below 1.0.
        for key in ("requested_straight_m", "snap_straight_m", "route_m"):
            self.assertIn(key, props, key)
        self.assertAlmostEqual(props["straight_m"], props["requested_straight_m"], places=1)
        self.assertGreaterEqual(props["detour_ratio"], 1.0 - 1e-9,
                                f"route {props['route_m']} m vs snap straight "
                                f"{props['snap_straight_m']} m")


class PedestrianConnectivityTest(unittest.TestCase):
    """The archipelago, and refusing to paper over it (V7.4).

    Qatar's pedestrian graph is 2,811 disconnected components whose largest
    holds 32.00% of the nodes, against the car graph's 98.25% -- because the
    arterials a person may not walk along are the ways that joined the pockets
    (adding the 24,699 car-only ways back takes the largest component to
    98.64%). On a graph like that, snapping each endpoint independently to the
    nearest node -- which is what a car router does, correctly -- stops being a
    detail and starts inventing answers.
    """

    def two_islands(self):
        """Two walkways that never meet, with the stub CLOSER to the query.

        Deliberately arranged so the nearest node to the origin is on the
        island and the second-nearest is on the network that actually reaches
        the destination. That is the real geometry: a path inside a compound is
        often closer to you than the street outside it.
        """
        return build_foot_graph([
            # The mainland: the walkway the destination sits on.
            feat("footway", [[51.5200, 25.2900], [51.5210, 25.2900],
                             [51.5220, 25.2900]], foot=True),
            # The island: a short stub, closer to the origin, going nowhere.
            feat("footway", [[51.5199, 25.2899], [51.51995, 25.2899]], foot=True),
        ])

    def test_components_are_labelled_and_counted(self):
        graph = self.two_islands()
        component, sizes = label_components(graph)
        self.assertEqual(len(sizes), 2, "two walkways that never meet")
        self.assertEqual(sorted(sizes), [2, 3])
        mainland = component[RoutingGraph.node_key(51.5200, 25.2900)]
        island = component[RoutingGraph.node_key(51.5199, 25.2899)]
        self.assertNotEqual(mainland, island)

    def test_component_labelling_is_undirected_which_is_exact_for_foot(self):
        """A one-way street is walkable both ways, so reachability is symmetric.

        This is why the flood fill may ignore direction here and could not on
        the car graph: ``foot_features`` strips ``oneway`` before construction.
        """
        graph = build_foot_graph([
            feat("residential", [[51.52, 25.29], [51.521, 25.29]],
                 foot=True, oneway="yes"),
        ])
        _component, sizes = label_components(graph)
        self.assertEqual(sizes, [2], "a one-way street is one walking component")

    def test_the_nearest_node_loses_to_the_one_that_is_actually_connected(self):
        """The headline behaviour: snap the PAIR, not each endpoint alone."""
        router = FootRouter(self.two_islands())
        origin = (51.51993, 25.28993)        # closest to the island stub
        destination = (51.5220, 25.2900)     # far end of the mainland
        src, tgt, cid = router._snap_pair(origin, destination)
        self.assertEqual(router._component[src], router._component[tgt])
        self.assertEqual(router._component[src], cid)
        self.assertEqual(
            router._component_sizes[cid], 3,
            "it must choose the mainland, not the closer dead-end island")
        result = router.walk(origin, destination)
        self.assertGreater(result["route"].distance_m, 0.0)
        self.assertEqual(result["component_nodes"], 3)

    def test_two_genuinely_separate_networks_say_so_rather_than_no_route(self):
        """The reason is the payload. A bare "no route" reads as a router bug."""
        # Two networks further apart than the snap radius. The separation has
        # to exceed SNAP_MAX_M or this is not the case under test: at 111 m
        # apart, BOTH components are within reach of both endpoints, a shared
        # one genuinely exists, and routing inside it is the correct answer.
        graph = build_foot_graph([
            feat("footway", [[51.5200, 25.2900], [51.5210, 25.2900]], foot=True),
            feat("footway", [[51.5200, 25.2960], [51.5210, 25.2960]], foot=True),
        ])
        router = FootRouter(graph)
        with self.assertRaises(PedestrianNetworkSplitError) as cm:
            # Both points are ON the walking network -- just not the same one.
            router.walk((51.5200, 25.2900), (51.5210, 25.2960))
        err = cm.exception
        self.assertIsInstance(err, NoRouteError,
                              "existing 404 handlers must still catch it")
        self.assertIn("separate pedestrian networks", str(err))
        self.assertGreaterEqual(err.origin_snap_m, 0.0)
        self.assertEqual(err.radius_m, SNAP_MAX_M)

    def test_a_pedestrian_is_not_moved_as_far_as_a_car_is(self):
        """The inherited 800 m / 2 km were tuned for a vehicle.

        Measured over 2,999 real Qatar POIs, pedestrian snapping is p50 21.6 m
        and p95 115.8 m with a tail to 1,016 m. A driver put on the road 40 m
        away is on the road; a walker put 600 m away has been answered about a
        different place -- which is what ``/foot`` across the Corniche did,
        returning a 452 m walk whose endpoints had moved 683 m.
        """
        self.assertLess(FootRouter.MAX_SNAP_M, Router.MAX_SNAP_M)
        self.assertEqual(FootRouter.MAX_SNAP_M, SNAP_MAX_M)
        self.assertLess(SNAP_PREFERRED_M, SNAP_MAX_M)

        graph = build_foot_graph([
            feat("footway", [[51.5200, 25.2900], [51.5210, 25.2900]], foot=True),
        ])
        router = FootRouter(graph)
        far = (51.5200, 25.3035)     # ~1.5 km: inside a car's limit, not a walk's
        with self.assertRaises(EndpointTooFarError) as cm:
            router.walk(far, (51.5210, 25.2900))
        self.assertEqual(cm.exception.limit_m, SNAP_MAX_M)

    def test_a_walk_reports_how_far_it_went_against_the_straight_line(self):
        """Phase 2 left "long walk" and "missing crossing" indistinguishable.

        Education City -> Qatar University is 8.6 km apart and returns a 24.7 km
        walk. That may well be correct -- Qatar's footways genuinely go around
        compounds -- but nothing on the wire said so. This is the number that
        lets a client notice, reported rather than enforced because a high ratio
        is frequently right.

        V7.4 4A: the ratio is measured against the SNAPPED straight line (the
        route connects the snapped points), and the requested-point distance is
        reported separately -- see the walk() docstring for the Festival City
        0.68 case that made the old denominator indefensible.
        """
        graph = build_foot_graph([
            feat("footway", [[51.5200, 25.2900], [51.5200, 25.2930],
                             [51.5210, 25.2930], [51.5210, 25.2900]], foot=True),
        ])
        result = FootRouter(graph).walk((51.5200, 25.2900), (51.5210, 25.2900))
        self.assertGreater(result["detour_ratio"], 2.0)
        self.assertAlmostEqual(
            result["detour_ratio"],
            result["route_m"] / result["snap_straight_m"], places=1)
        # Both endpoints are exactly on the network, so the snapped straight
        # line equals the requested one here -- and the ratio still agrees
        # with the route distance either way.
        self.assertAlmostEqual(result["snap_straight_m"], result["requested_straight_m"], places=1)
        self.assertAlmostEqual(result["route_m"], result["route"].distance_m, places=0)

    def test_detour_ratio_can_never_read_below_one(self):
        """The Festival City 0.68 case, made impossible by construction.

        The old ratio divided the route length by the straight line between the
        REQUESTED points. Snapping can pull two endpoints closer together
        (Festival City's snapped pair was 0.68 of the requested pair), so a
        correct route could report a detour ratio under 1.0 -- implying it was
        shorter than a straight line. A path between two points is never
        shorter than the straight line between them, so measured against the
        SNAPPED straight line the ratio is >= 1.0 by construction. Asserted
        over the real route shape rather than the formula, because the formula
        is the fix and a future change could reintroduce the bug in the
        denominator.
        """
        graph = build_foot_graph([
            feat("footway", [[51.5200, 25.2900], [51.5210, 25.2900]], foot=True),
            feat("footway", [[51.5210, 25.2900], [51.5220, 25.2900]], foot=True),
        ])
        # A destination that snaps noticeably off the requested point.
        result = FootRouter(graph).walk((51.5200, 25.2900), (51.5230, 25.2899))
        ratio = result["detour_ratio"]
        self.assertIsNotNone(ratio)
        # A path between two points is never shorter than the straight line
        # between them, so the ratio against the SNAPPED straight line is
        # bounded below by 1.0 even when snapping moved the endpoints closer
        # together (the old requested-distance denominator read 0.68).
        self.assertGreaterEqual(ratio, 1.0 - 1e-9, result)
        # And the reported distances are the ones the ratio was computed from.
        self.assertAlmostEqual(
            result["route_m"], result["route"].distance_m, delta=0.05)
        self.assertGreater(result["snap_straight_m"], 0.0)

    def test_the_component_census_is_the_shape_footz_publishes(self):
        census = FootRouter(self.two_islands()).component_census()
        self.assertEqual(census["components"], 2)
        self.assertEqual(census["largest_component_nodes"], 3)
        self.assertAlmostEqual(census["largest_component_share"], 0.6, places=3)
        self.assertEqual(census["top_component_nodes"], [3, 2])


class RealPedestrianConnectivityTest(unittest.TestCase):
    """The same claims against real converted OSM, not a hand-built fixture."""

    @classmethod
    def setUpClass(cls):
        cls.router = FootRouter(build_foot_graph(load_msheireb()))

    def test_real_msheireb_is_itself_more_than_one_walking_network(self):
        """Even 710 features of central Doha are not one connected pavement."""
        census = self.router.component_census()
        self.assertGreater(
            census["components"], 1,
            "if this ever becomes 1, the fragmentation claim needs re-measuring")
        self.assertEqual(sum(self.router._component_sizes),
                         len(self.router._component))

    def test_every_walk_it_answers_stays_inside_one_component(self):
        """The invariant: a route never spans a gap it cannot actually cross."""
        result = self.router.walk((51.522626, 25.289291), (51.520109, 25.290704))
        keys = result["route"].node_keys
        components = {self.router._component[k] for k in keys}
        self.assertEqual(len(components), 1, "a route crossed a network split")
        self.assertEqual(components.pop(), result["component"])


if __name__ == "__main__":
    unittest.main()
