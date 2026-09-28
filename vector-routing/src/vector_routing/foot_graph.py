"""The pedestrian graph: a second ``RoutingGraph``, built from the ways a person may walk.

Vector's routing graph has always been the CAR graph. ``bootstrap.sh`` filters
the converted OSM to ``properties.car`` and bakes ``<region>_roads.geojson``, so
every footway, staircase, arcade and pedestrian square — 40,437 ways in Qatar
that no car may use — is dropped before the router ever sees it. That filter is
correct and must stay: it is what stopped Doha routes beginning *"Head northwest
on footway road"*. It also means the engine cannot currently route a person from
a parking space to a door, which is the whole of the Last Mile.

So: a SECOND graph, from the same ingestion, over the same machinery.

    OSM -> osm_to_geojson.py -> properties.foot -> foot.geojson -> this module

Three things are deliberately NOT here:

``a second classifier``
    Whether a way is walkable is decided once, at ingestion, by
    ``vector_ingestion.classify.is_pedestrian_routable`` — the same function,
    the same tests, the same rules about ``foot=yes`` on a motorway bridge. This
    module only reads the boolean that decision produced. A pedestrian
    classifier living here as well is how the map and the router would come to
    disagree about what a footpath is.

``a second A*``
    ``vector_geo.algorithms.astar`` is the pathfinder, and ``Router`` is the
    snapping/assembly machine. :class:`FootRouter` overrides the only two things
    that are actually about being a car.

``a second service``
    ``/foot`` is an endpoint on vector-routing. A walking route is the same
    question over a different edge set, not a different system.

## What a pedestrian graph must forget

The car graph carries two vehicular constraints that are simply not true of a
person, and carrying them into the walking graph would produce absurd routes
that still look plausible:

* **One-way streets.** 30,012 of Qatar's foot-routable ways carry a ``oneway``
  tag. ``RoutingGraph.add_way`` honours it by emitting a single directed edge —
  correct for a car, and for a pedestrian it means a walk *up* a one-way street
  is routed the long way round the block, or not at all. The tag is stripped
  here rather than in the bake, so ``foot.geojson`` stays a faithful record of
  the OSM data and the vehicular reading is dropped at exactly the point where
  the vehicle stops being relevant.

* **Turn restrictions.** "No left turn" is a rule for traffic. :class:`FootRouter`
  passes none, which also makes the A* state space plain node-keyed again.

## A surprise worth knowing about before you read a foot route

543 Qatar ways in the ARTERIAL classes are in this graph — 135 ``primary``,
109 ``secondary_link``, 99 ``primary_link``, 87 ``secondary``, 68 ``trunk``,
42 ``trunk_link``, 3 ``motorway_link``. That is not a leak. Each carries an
explicit ``foot=yes``, almost always beside ``sidewalk=right`` or
``sidewalk=both``: they are main roads with a surveyed pavement, and Al Corniche
Street is one of them. ``is_pedestrian_routable`` admits them through its
explicit-permission rule, and it is right to — refusing them would overrule the
survey with a guess and leave no walking route along the Corniche.

What you CANNOT see from a baked foot feature is *which* rule admitted it.
``osm_to_geojson._attrs`` copies the raw OSM ``foot`` tag into ``properties``
and then overwrites that same key with the classifier's boolean, so
``foot=yes`` and "walkable because it is a footway" are indistinguishable
downstream. Harmless for routing — the boolean is the only thing this module
needs, and the decision behind it is tested where it is made — but Phase 3's
shade model wants ``sidewalk`` and will want to widen the promoted tag list
rather than trust ``properties.foot`` to carry the reason.

## The pedestrian graph is an ARCHIPELAGO, and this is the thing to know (V7.4)

Measured on the deployed Qatar bake, undirected components:

    car  graph:   855,362 nodes, 1,019 components, largest holds 98.25%
    foot graph: 1,108,269 nodes, 2,811 components, largest holds **32.00%**

The three largest pedestrian components are 354,692 / 297,853 / 216,053 nodes
-- no single one is even a third of the network -- and 753,577 nodes lie
outside the largest. Central Doha alone contains 181 distinct components.

That is not a bug in this module and not a node-key quantisation artifact: of
the 22,371 500 m cells holding any of the top three components, only 48 hold
two of them, and NO cell has two of them passing within 10 m of each other.
They are genuinely separate territory. The cause is measurable -- adding the
24,699 car-only ways back moves the largest component from 32.00% to 98.64%.
**Qatar's arterials are the connective tissue**, OSM has only ~3,000
``footway=crossing`` ways in the whole country to replace them, and correctly
refusing to walk people down the Salwa Road correctly disconnects the pockets
those roads separate.

So a pedestrian router cannot snap its endpoints the way a car router does.
``Router`` snaps each endpoint INDEPENDENTLY to the nearest routable node
within 800 m, which on a 98%-connected graph is almost always right and on
this one is a trap: two endpoints a few hundred metres apart routinely land in
different components, and the search then either fails (a 404 blamed on the
points) or -- far worse -- succeeds by having silently moved an endpoint
hundreds of metres to somewhere that *is* connected. Measured before this
change: ``/foot`` across the Corniche returned a 452 m walk whose endpoints had
moved 683 m. A correct path between two points nobody asked about, and nothing
on the wire distinguished it from a correct answer.

:func:`label_components` and :meth:`FootRouter._snap_pair` are the fix: the
endpoints are chosen TOGETHER, from a component that contains both, and when no
such component exists the answer is that these two points are not joined by a
walking network -- a different statement from "no route", reported as one.
"""

from typing import Any, Dict, Iterable, Iterator, List, Optional, Tuple

from .algorithms import build_graph_from_features
from .errors import EndpointTooFarError, NoRouteError, RouteError
from .graph import RoutingGraph
from .haversine import haversine_meters
from .pedestrian_maneuvers import (
    CrossingCatalog,
    build_pedestrian_maneuvers,
)
from .pedestrian_plan import build_pedestrian_plan
from .pedestrian_cost import (
    FACTORS,
    MULTIPLICATIVE_ORDER,
    STAIRS,
    SMOOTH_SURFACES,
    PedestrianCostModel,
    _parse_gradient,
    is_crossing_edge,
    is_narrow_width,
)
from .router import Endpoint, Router, _as_coord
from .signals import SignalIndex
from .speeds import WALK_SPEED_MS, walk_speed_ms

# Tags that describe how a VEHICLE may traverse a way. Dropped when the way
# becomes a pedestrian edge; see the module docstring.
VEHICULAR_PROPS = ("oneway",)

#: How far a pedestrian endpoint may be moved to reach the walking network
#: before the answer is considered suspect.
#:
#: NOT the car's 800 m. That was tuned for a vehicle, where the graph is 98%
#: connected and the nearest road is the road you are on. Over 2,999 real Qatar
#: POIs the pedestrian snap distribution is p50 21.6 m, p75 37.9 m, p90 65.4 m,
#: p95 115.8 m -- and then a tail out to 1,016 m. 150 m keeps 96.9% of real
#: destinations and cuts the tail, because past roughly that distance the node
#: being snapped to is usually across something a person cannot cross.
#:
#: Walking is also the mode where snapping is least forgivable: a driver put on
#: the road 40 m away is on the road; a walker put 500 m away has been answered
#: about a different place.
SNAP_PREFERRED_M = 150.0

#: The hard limit, beyond which the endpoint is outside the walking network and
#: the caller is told so rather than given an invented answer. 400 m rather than
#: the car's 2 km for the obvious reason: 2 km of snapping is a 25-minute walk
#: to the start of the walk.
SNAP_MAX_M = 400.0


def label_components(graph: RoutingGraph) -> Tuple[Dict[str, int], List[int]]:
    """Label every node with the connected component it belongs to.

    Returns ``(component_of_node, sizes)`` where ``sizes[cid]`` is the node
    count of component ``cid``.

    Treating the edges as undirected is EXACT here rather than an
    approximation, and only because this is the foot graph: ``foot_features``
    strips ``oneway`` before construction, so ``RoutingGraph.add_way`` emits
    both directions of every edge and reachability is symmetric. Running this
    over the car graph would silently conflate strongly- and weakly-connected
    components, which is why it lives here and not on ``RoutingGraph``.

    Iterative rather than recursive: the largest Qatar component is 354,692
    nodes, and a recursive flood fill would exhaust the stack on real data.
    Costs ~1.7 s over the full Qatar graph, paid once when the graph loads.
    """
    adjacency = graph._adjacency
    component: Dict[str, int] = {}
    sizes: List[int] = []
    for start in adjacency:
        if start in component:
            continue
        cid = len(sizes)
        component[start] = cid
        stack = [start]
        count = 0
        while stack:
            node = stack.pop()
            count += 1
            for to, _w, _p in adjacency.get(node, ()):
                if to not in component:
                    component[to] = cid
                    stack.append(to)
        sizes.append(count)
    return component, sizes


class PedestrianNetworkSplitError(NoRouteError):
    """The two points sit on separate walking networks.

    A subclass of :class:`NoRouteError` so every existing handler -- including
    the HTTP layer's 404 branch -- keeps working unchanged, but carrying the
    REASON, because "no route" and "no route because these are two different
    pedestrian networks, and here is how far each endpoint was from one" send a
    reader to very different places. The first reads as a router bug; the
    second is a statement about the map, and in Qatar it is usually a true one.
    """

    def __init__(self, origin_m: float, destination_m: float, radius_m: float):
        self.origin_snap_m = origin_m
        self.destination_snap_m = destination_m
        self.radius_m = radius_m
        # RouteError's initialiser, not NoRouteError's: the latter composes a
        # "(source=..., target=...)" node-key detail that is meaningless here,
        # since the whole point is that no pair of nodes was acceptable.
        RouteError.__init__(
            self,
            "no walking route: these points are on separate pedestrian "
            f"networks (no component within {radius_m:.0f} m contains both; "
            f"nearest walkable node is {origin_m:.0f} m from the origin and "
            f"{destination_m:.0f} m from the destination)",
        )


def is_foot_feature(feature: Dict[str, Any]) -> bool:
    """Is this converted GeoJSON feature an edge in the pedestrian graph?

    Reads the ``foot`` flag that ``osm_to_geojson.py`` writes from
    ``is_pedestrian_routable``. Absence means NO.

    That is the opposite of the car filter in ``bootstrap.sh``, which treats a
    missing ``car`` flag as drivable, and the asymmetry is deliberate. There,
    the default exists so a graph baked by an older ingestion pass degrades to
    the previous behaviour instead of coming up empty and 503-ing every route.
    Here there is no previous behaviour to degrade to, and "unknown means
    walkable" would put motorways in the walking graph the moment the flag went
    missing — an empty foot graph is a loud, diagnosable failure, while a foot
    graph full of motorways is a silent one that routes a person down the
    Salwa Road.
    """
    props = feature.get("properties") or {}
    return props.get("kind") == "road" and bool(props.get("foot"))


def foot_features(features: Iterable[Dict[str, Any]]) -> Iterator[Dict[str, Any]]:
    """Yield the pedestrian-routable features, with vehicular props stripped."""
    for feat in features:
        if not isinstance(feat, dict) or not is_foot_feature(feat):
            continue
        props = dict(feat.get("properties") or {})
        for key in VEHICULAR_PROPS:
            props.pop(key, None)
        yield {"type": "Feature", "geometry": feat.get("geometry"), "properties": props}


def build_foot_graph(features: Any) -> RoutingGraph:
    """Build the pedestrian ``RoutingGraph`` from converted GeoJSON features.

    Accepts a FeatureCollection or a bare feature list, matching
    ``build_graph_from_features`` — which does the actual construction, so the
    foot graph and the car graph are built by one piece of code.
    """
    if isinstance(features, dict) and "features" in features:
        features = features["features"]
    if not isinstance(features, list):
        return RoutingGraph()
    return build_graph_from_features(list(foot_features(features)))


class FootRouter(Router):
    """A :class:`Router` that walks.

    Snapping, the A* call, the ``Route`` shape and the error contract are all
    inherited unchanged. Only the mode hooks differ, and they return the SAME
    number — for a pedestrian the routing cost and the reported duration are the
    same quantity, so the two cannot drift apart the way the driving pair once
    did.
    """

    #: Pedestrian limits, replacing the inherited vehicular ones. See the
    #: constants' own docstrings for the measurements behind the numbers.
    MAX_SNAP_M = SNAP_MAX_M
    SNAP_PREFERRED_M = SNAP_PREFERRED_M

    def __init__(self, graph: RoutingGraph, crossings: Optional[CrossingCatalog] = None,
                 road_graph: Optional[RoutingGraph] = None,
                 cost_model: Optional[PedestrianCostModel] = None,
                 signals: Optional[SignalIndex] = None) -> None:
        # No restrictions, ever: see the module docstring. Passing None is also
        # what keeps `astar` on its plain node-keyed state space.
        super().__init__(graph, restrictions=None)
        # V7.4 4B.3: the deterministic edge-cost model. ``cost_model=None``
        # means the documented default (general profile): a shortest-first
        # walking cost with the physical factors (stairs, incline, crossing
        # wait) — see pedestrian_cost.py for every value and its flag.
        # ``PedestrianCostModel.legacy()`` reproduces the pre-4B.3 router
        # exactly (used by regression evidence/tests); nothing else at this
        # stage is wired.
        self._cost_model = cost_model if cost_model is not None else PedestrianCostModel.general()
        # Component labels, computed ONCE per graph rather than per request.
        # ~1.7 s over the full Qatar graph at load; a flood fill per /foot call
        # would be ~1.7 s per walk.
        self._component, self._component_sizes = label_components(graph)
        # V7.4 4B.1: the sources behind crossing facts. ``crossings`` is the
        # preserved ``<region>_crossings.geojson`` artifact (crossing TYPE, kerb,
        # tactile facts); ``road_graph`` is the DRIVABLE network (the service's
        # car graph) used only to name the road a crossing crosses. Neither
        # affects routing, snapping or durations — they enrich facts: a walk
        # without them is the identical walk, with cross facts that simply carry
        # no type/road beyond what the walked edges themselves promoted.
        self._crossings = crossings
        self._road_graph = road_graph
        # V7 traffic lights: the surveyed signals keyed by the FOOT graph's own
        # node keys, so a crossing fact can cite the signals standing on it by
        # identity. Built from the catalog against THIS graph (not the car
        # graph): a signal that is not a vertex of the walking network is not
        # something a walker's crossing can be said to have. ``None`` is the
        # pre-V7 state and is not an error — cross facts then carry no signals.
        self._signals = signals
        # V7 traffic lights: what the association actually achieved, computed
        # ONCE here rather than per request. Two numbers that cannot be read off
        # each other: a signal can be on the graph and on no crossing (the
        # ordinary junction signal a walker never steps over), and a crossing
        # can carry no signal at all (the overwhelming majority). Both matter to
        # a consumer and neither is derivable from the other.
        self._signal_census = self._signal_association_census()

    def _signal_association_census(self) -> Dict[str, Any]:
        """How many signals stand on a crossing of THIS network, and how many do not.

        Counts SIGNALS (by graph-node identity), not crossing edges, because the
        question a reader has is "of the signals I baked, how many can a walker
        be told about?" — and the answer includes the ones that are on the graph
        but at a junction rather than over a crossing.
        """
        if not self._signals or len(self._signals) == 0:
            return {"associated": 0, "on_crossing": 0, "at_junction": 0}
        on_crossing = 0
        for node in self._signals:
            if not self._graph.has_node(node):
                continue
            if any(is_crossing_edge(p) for _to, _w, p in self._graph.neighbors(node)):
                on_crossing += 1
        total = len(self._signals)
        return {
            "associated": total,
            "on_crossing": on_crossing,
            "at_junction": total - on_crossing,
        }

    # -- component-aware snapping (V7.4) ------------------------------------

    def signal_census(self) -> Dict[str, Any]:
        """What the signal association achieved, for ``/footz``.

        Not part of the route contract: this is the answer to "of the signals
        baked into this deployment, how many can a walker be told about, and how
        many of those actually stand on a crossing?" — the only way a reader can
        tell a working association from a catalog that silently matched nothing.
        """
        return dict(self._signal_census)

    def set_signals(self, signals: Optional[SignalIndex]) -> None:
        """Re-bind the signal index (service load order, V7 traffic lights).

        The routing service loads the car graph, then the foot graph, then the
        signal catalog — the order the deployment has always used, because the
        catalog path is derived from the CAR graph path. Without this, the foot
        router built two steps earlier could never see a catalog at all. Cheap
        and total: recomputes the census, a scan of the signal nodes' own
        adjacency.
        """
        self._signals = signals
        self._signal_census = self._signal_association_census()

    def component_census(self) -> Dict[str, Any]:
        """How fragmented this pedestrian network is, for ``/footz``.

        An operator cannot otherwise tell a healthy walking graph from Qatar's,
        where the largest component holds under a third of the nodes: both
        report a large, plausible edge count and both answer short walks fine.
        The difference only shows up as unexplained 404s in the field.
        """
        sizes = sorted(self._component_sizes, reverse=True)
        total = sum(sizes)
        return {
            "components": len(sizes),
            "largest_component_nodes": sizes[0] if sizes else 0,
            "largest_component_share": round(sizes[0] / total, 4) if total else 0.0,
            "top_component_nodes": sizes[:5],
            "snap_preferred_m": self.SNAP_PREFERRED_M,
            "snap_max_m": self.MAX_SNAP_M,
        }

    def _candidates(self, lon: float, lat: float, radius_m: float) -> List[tuple]:
        """Every DEPARTABLE node within ``radius_m``, nearest first.

        ``RoutingGraph._nearest`` returns only the single closest match, which
        is exactly what cannot be used here: the closest node is frequently in
        the wrong component, and the second-closest -- twenty metres further and
        on the network that actually reaches the destination -- is the answer.
        So this collects the whole neighbourhood and lets the caller choose.
        """
        graph = self._graph
        if graph._grid is None:
            graph._build_grid()
        cell = graph._grid_cell
        # Degrees-to-metres at the equator is the conservative direction: a
        # cell is NARROWER in longitude away from it, so this over-covers
        # rather than missing candidates at Qatar's latitude.
        span = int(radius_m / 111_000.0 / cell) + 1
        cx, cy = int(lon / cell), int(lat / cell)
        out: List[tuple] = []
        for gx in range(cx - span, cx + span + 1):
            for gy in range(cy - span, cy + span + 1):
                for key in graph._grid.get((gx, gy), ()):
                    if not graph._adjacency.get(key):
                        continue          # a node you cannot depart is not a snap
                    dist = haversine_meters((lon, lat), graph._nodes[key])
                    if dist <= radius_m:
                        out.append((dist, key))
        out.sort()
        return out

    def _nearest_per_component(self, candidates: List[tuple]) -> Dict[int, tuple]:
        """The closest candidate in each component (input must be sorted)."""
        best: Dict[int, tuple] = {}
        for dist, key in candidates:
            cid = self._component.get(key)
            if cid is not None and cid not in best:
                best[cid] = (dist, key)
        return best

    def _snap_pair(self, origin: tuple, destination: tuple) -> tuple:
        """Snap BOTH endpoints into one component, or explain why that failed.

        The difference from ``Router._snap_routable`` is the whole of V7.4's
        correctness work. Independent snapping asks "what is nearest to each of
        these?"; a walker is asking "how do I get from here to there on foot",
        and on a graph whose largest component holds 32% of the nodes those are
        different questions with different answers.

        Ties are broken by TOTAL snap distance, so the pair chosen is the one
        that moves the person least overall -- not the one with the closest
        single endpoint, which is how one endpoint ends up 600 m away to keep
        the other one at 5 m.

        @return ``(origin_key, destination_key, component_id)``
        """
        near_o = self._candidates(origin[0], origin[1], self.MAX_SNAP_M)
        near_d = self._candidates(destination[0], destination[1], self.MAX_SNAP_M)

        # Nothing walkable at all within the hard limit: that is the "outside
        # the routable area" case, and it is reported as the car router reports
        # it -- with the point, the distance and the limit.
        for point, found in ((origin, near_o), (destination, near_d)):
            if not found:
                fallback = self._graph.nearest_node(point[0], point[1])
                distance = haversine_meters(point, self._graph.node_coord(fallback))
                raise EndpointTooFarError(
                    point[0], point[1], distance, self.MAX_SNAP_M)

        best_o = self._nearest_per_component(near_o)
        best_d = self._nearest_per_component(near_d)
        shared = set(best_o) & set(best_d)
        if not shared:
            # Both endpoints are ON the walking network; there is simply no
            # walking network joining them. Saying "no route found" here would
            # be true and useless, and saying nothing at all is how a 404 gets
            # blamed on the router.
            raise PedestrianNetworkSplitError(
                near_o[0][0], near_d[0][0], self.MAX_SNAP_M)

        cid = min(shared, key=lambda c: best_o[c][0] + best_d[c][0])
        return best_o[cid][1], best_d[cid][1], cid

    def _edge_weight(self, u: str, to: str, w: float, props: Dict[str, Any]) -> float:
        """Search cost (seconds) for one edge, per the V7.4 4B.3 cost model.

        The default model is a shortest-first walking cost (pace plus the
        documented physical factors: stairs speed, Tobler incline, crossing
        expected delay). `PedestrianCostModel.legacy()` reproduces the
        pre-4B.3 pure-time behavior exactly. NOTE: this is deliberately NOT
        the reported duration — ``_edge_duration_s`` stays pure pace time so
        the walk ETA is unchanged (the mirror of the driving model's
        TURN_DELAY_S split; see pedestrian_cost module docstring).
        """
        return self._cost_model.edge_weight(u, to, w, props)

    def _edge_duration_s(self, w: float, props: Dict[str, Any]) -> float:
        """The walk's pace time (seconds): distance / walking speed.

        Unchanged by 4B.3: reported ETA stays pure pace time while the
        weighted cost (which route choice optimised) carries the documented
        penalties, and ``cost.penalty_s`` on the wire exposes the gap.
        """
        return w / walk_speed_ms(props)

    def walk(self, origin: Endpoint, destination: Endpoint) -> Dict[str, Any]:
        """A pedestrian route, plus the snapping and the stairs that produced it.

        ``Router.route`` returns a bare ``Route``, which is all ``/route`` has
        ever needed. A walk needs two more things visible:

        * **where the endpoints landed.** A driver's tap snaps onto a road a few
          metres away; a walk starts at a parking space or a mall door, and a
          snap of 300 m is the difference between "your walk starts at the car"
          and "your walk starts somewhere else entirely". ``/navigate`` already
          reports this for the same reason.
        * **how much of it is stairs.** The claim that steps are penalised and
          not banned is only checkable if the answer says when it used them.
        * **what is actually happening, as facts.** V7.4 4B.1: the structured
          maneuver facts of the walk — depart / cross / stairs / turn /
          transition / arrive — sourced to the graph geometry or promoted OSM
          data that established each, positioned on the geometry, and carrying
          no instruction prose. 4B.2 converts them into maneuvers; this stage
          only makes the truth visible.
        * **what the walk costs, and what it exposes.** V7.4 4B.3: ``cost`` is
          the weighted cost the search optimised (unit: seconds) with the
          pace/penalty split and per-factor seconds plus attribute-exposure
          totals (stairs/step_count, incline, crossing wait, poor surface,
          unlit, narrow width, no sidewalk); ``segment_cost_s`` is one
          weighted cost per geometry pair. The choice model's full
          configuration is on ``/footz``.
        """
        o = _as_coord(origin)
        d = _as_coord(destination)
        src, tgt, cid = self._snap_pair(o, d)
        route = self.route_by_node(src, tgt)
        # V7.4 4B: the structured maneuver facts of this walk, computed once and
        # shared by the fact stream (4B.1) and its sparse interpretation (4B.2).
        facts = build_pedestrian_maneuvers(
            route.node_keys, self._graph,
            crossings=self._crossings, road_graph=self._road_graph,
            signals=self._signals)
        snap = [self._snap_info(o, src), self._snap_info(d, tgt)]
        snap_max = max(s["distance_m"] for s in snap)
        # V7.4 4A: the three distances a walk's shape is stated against, made
        # explicit so none of them can be mistaken for another. The route
        # connects the SNAPPED points, so detour_ratio is measured against the
        # snapped straight line: a path between two points is never shorter
        # than the straight line between them, so the ratio can no longer read
        # below 1.0 (Festival City reported 0.68 when the ratio divided the
        # route by the REQUESTED straight line, which snapping had pulled
        # closer together).
        requested_straight_m = haversine_meters(o, d)
        snap_straight_m = haversine_meters(
            self._graph.node_coord(src), self._graph.node_coord(tgt))
        route_m = route.distance_m
        return {
            "route": route,
            "snap": snap,
            "snap_max_m": snap_max,
            # True when both endpoints reached the network within the pace a
            # pedestrian graph should manage. False does NOT mean wrong -- an
            # airport apron or a walled compound legitimately snaps far -- it
            # means the walk starts somewhere the user may not recognise, and a
            # client showing "6 min walk" should be able to say so.
            "snap_within_preferred": snap_max <= self.SNAP_PREFERRED_M,
            # Which walking network this route lives on, and how big it is.
            # A route inside a 12-node component is a route around one car park.
            "component": cid,
            "component_nodes": self._component_sizes[cid],
            "steps_m": self.steps_m(route.node_keys),
            # V7.4 4A.4: how much of the walk is actually ON a crossing (in
            # metres). The recon's rule: "cross the road" may only exist when
            # the map can establish a crossing; this is that establishment
            # made measurable. Mirrors steps_m -- 4B turns it into language.
            "crossing_m": self.crossings_m(route.node_keys),
            "segment_tags": self.segment_tags(route.node_keys),
            # V7.4 4B.1: the structured maneuver FACTS of the walk — depart /
            # cross / stairs / turn / transition / arrive, each sourced to the
            # graph geometry or promoted OSM data that established it, each
            # carrying its position on the geometry. No instruction prose: this
            # is the factual layer 4B.2 turns into maneuvers and 4C into UX.
            # Cross facts are enriched by the crossings artifact (type, kerb)
            # and the road graph (the crossed road) when they are wired in.
            "maneuvers": facts,
            # V7.4 4B.2: the sparse, ordered interpretation of the facts —
            # depart / cross / stairs / turn_* / continue / arrive, with dense
            # bearing noise merged away and crossing/stair events guaranteed to
            # survive. A pure function of ``facts`` (the facts are the source
            # of truth): no geometry is re-derived, no OSM semantics invented,
            # no prose or voice. 4B.3 consumes this via the cost model's
            # per-edge consumption of the SAME promoted attributes; this plan
            # itself is untouched.
            "maneuver_plan": build_pedestrian_plan(facts),
            # V7.4 4B.3: the cost model's route-level summary and per-segment
            # costs — the ``why did the router choose this and what does it
            # expose`` answer. ``cost.cost_s`` is the weighted cost the search
            # optimised (unit: seconds); ``cost.pace_s`` is the pure walking
            # time and equals the reported duration; ``cost.penalty_s`` and
            # ``cost.factor_s`` decompose the difference per documented factor;
            # the attribute-exposure totals (stairs/step_count, incline,
            # crossing, poor surface, unlit, narrow width, no-sidewalk) are
            # measured regardless of which factors the profile enables, so a
            # client can see what the route exposes without a per-edge dump.
            # ``segment_cost_s`` is one number per geometry pair, aligned with
            # ``classes``/``footway``/``crossing``/``lit``.
            "cost": self.cost_summary(route.node_keys),
            "segment_cost_s": self.segment_cost_s(route.node_keys),
            # How far the walk is compared with the straight line. Phase 2 noted
            # that a long walk and a missing crossing are indistinguishable from
            # the distance alone; this is the number that makes them
            # distinguishable, and it is reported rather than acted on because
            # a high ratio is often correct (walls, compounds, car parks).
            #
            # ``straight_m`` is the ORIGINAL key and keeps its original
            # meaning (the requested-point straight line); it is now an alias
            # for ``requested_straight_m`` and is retained for wire
            # continuity. New readers should use the explicit names.
            "straight_m": round(requested_straight_m, 1),
            "requested_straight_m": round(requested_straight_m, 1),
            "snap_straight_m": round(snap_straight_m, 1),
            "route_m": round(route_m, 1),
            "detour_ratio": (round(route_m / snap_straight_m, 2)
                             if snap_straight_m > 1.0 else None),
        }

    def edges_along(
        self, keys: List[str], graph: Optional[RoutingGraph] = None
    ) -> Iterator[tuple]:
        """Yield ``(weight, props)`` for each edge of a path of node keys.

        One lookup per segment, shared by everything that needs to know what a
        walk is actually made of. ``Route.path`` is built as one coordinate per
        node key, so the Nth pair yielded here is the Nth segment of the
        geometry on the wire — which is what lets the client line a per-segment
        array up against the LineString without a second resolution step.
        """
        g = graph if graph is not None else self._graph
        for a, b in zip(keys, keys[1:]):
            for to, w, p in g.neighbors(a):
                if to == b:
                    yield w, p
                    break

    def _edges_with_keys(
        self, keys: List[str], graph: Optional[RoutingGraph] = None
    ) -> Iterator[tuple]:
        """Yield ``(u, to, w, props)`` for each edge of a node-key path.

        The cost model's signature needs the directed endpoints, so this is
        ``edges_along`` with the node keys attached — one lookup per segment,
        the same alignment as everything else on the wire.
        """
        g = graph if graph is not None else self._graph
        for a, b in zip(keys, keys[1:]):
            for to, w, p in g.neighbors(a):
                if to == b:
                    yield a, b, w, p
                    break

    def cost_summary(self, keys: List[str],
                     graph: Optional[RoutingGraph] = None) -> Dict[str, Any]:
        """V7.4 4B.3: the route-level cost/attribute-exposure summary.

        Every quantity is additive over the route's edges and derived from the
        SAME model that chose the route, so the summary is a direct answer to
        ``weighted cost``, ``stairs/step exposure``, ``incline exposure``,
        ``poor-surface exposure``, ``unlit exposure`` and ``crossing
        exposure`` (the stage's measurability list). The quantities are:

        * ``base_time_s`` — walking time on level ground at the flat 1.35 m/s
          pace: the route's length expressed in seconds;
        * ``pace_s`` — the reported pace time (stairs at 0.5 m/s), by
          construction exactly ``route.duration_s``;
        * ``cost_s`` — the weighted cost the search optimised;
        * ``penalty_s`` — ``cost_s - pace_s``: the non-pace penalties;
        * ``factor_s`` — each factor's chained uplift in seconds; the sum is
          EXACTLY ``cost_s - base_time_s`` (the stairs uplift is the gap
          ``pace_s - base_time_s``, incline/preferences/wait the rest — see
          pedestrian_cost._breakdown for the chaining that makes this exact).

        The attribute-exposure totals (stairs/step_count, incline, crossing,
        poor surface, unlit, narrow width, no-sidewalk) are measured
        regardless of which factors the profile enables.
        """
        model = self._cost_model
        base_time_s = 0.0
        pace_s = 0.0
        cost_s = 0.0
        factor_s = {f: 0.0 for f in FACTORS}
        stairs = {"edges": 0, "distance_m": 0.0, "step_count": 0, "handrail_edges": 0}
        incline = {"edges": 0, "distance_m": 0.0, "uplift_s": 0.0, "max_gradient": 0.0}
        crossing = {"edges": 0, "distance_m": 0.0, "wait_s": 0.0,
                    "typed_edges": 0, "untyped_edges": 0}
        surface = {"poor_edges": 0, "poor_distance_m": 0.0}
        lit = {"unlit_edges": 0, "unlit_distance_m": 0.0}
        width = {"narrow_edges": 0, "narrow_distance_m": 0.0}
        sidewalk = {"no_sidewalk_edges": 0, "no_sidewalk_distance_m": 0.0}
        for a, b, w, p in self._edges_with_keys(keys, graph):
            bd = model._breakdown(w, p)
            cost_s += bd["total"]
            pace_s += bd["pace"]
            base_time_s += bd["flat"]
            for f in (STAIRS, *MULTIPLICATIVE_ORDER):
                factor_s[f] += bd["uplifts"][f]
            factor_s["crossing_wait"] += bd["wait"]
            hw = (p.get("highway") or "").strip().lower()
            # --- attribute exposure, measured regardless of profile flags ---
            if hw == "steps":
                stairs["edges"] += 1
                stairs["distance_m"] += w
                sc = p.get("step_count")
                try:
                    stairs["step_count"] += int(sc)
                except (TypeError, ValueError):
                    pass
                if (p.get("handrail") or "").strip().lower() in ("yes", "1", "true"):
                    stairs["handrail_edges"] += 1
            inc = (p.get("incline") or "").strip().lower()
            if inc:
                incline["edges"] += 1
                incline["distance_m"] += w
                incline["uplift_s"] += bd["uplifts"]["incline"]
                g = _parse_gradient(p.get("incline")) or 0.0
                incline["max_gradient"] = max(incline["max_gradient"], g)
            if is_crossing_edge(p):
                crossing["edges"] += 1
                crossing["distance_m"] += w
                crossing["wait_s"] += bd["wait"]
                if (p.get("crossing") or "").strip():
                    crossing["typed_edges"] += 1
                else:
                    crossing["untyped_edges"] += 1
            surf = (p.get("surface") or "").strip().lower()
            if surf and surf not in SMOOTH_SURFACES:
                surface["poor_edges"] += 1
                surface["poor_distance_m"] += w
            if (p.get("lit") or "").strip().lower() in ("no", "false", "0"):
                lit["unlit_edges"] += 1
                lit["unlit_distance_m"] += w
            if is_narrow_width(p.get("width")):
                width["narrow_edges"] += 1
                width["narrow_distance_m"] += w
            if (p.get("sidewalk") or "").strip().lower() == "no":
                sidewalk["no_sidewalk_edges"] += 1
                sidewalk["no_sidewalk_distance_m"] += w
        return {
            "profile": model.profile,
            "base_time_s": round(base_time_s, 2),
            "pace_s": round(pace_s, 2),
            "cost_s": round(cost_s, 2),
            "penalty_s": round(cost_s - pace_s, 2),
            # What affected route selection under this profile: the live
            # factor set, and the non-zero factor seconds. Exposure blocks
            # below are OBSERVED regardless of flags — for the general
            # profile, surface/lit/width/sidewalk costs are 0 and therefore
            # absent from factor_s even though the route may report their
            # exposure metres. This is the contract's explicit affected-vs-
            # observed split (4B.4).
            "selection_factors": model.selection_factors(),
            "factor_s": {k: round(v, 2) for k, v in factor_s.items() if round(v, 2) != 0.0},
            "stairs": {k: (round(v, 1) if isinstance(v, float) else v)
                       for k, v in stairs.items()},
            "incline": {k: (round(v, 2) if isinstance(v, float) else v)
                        for k, v in incline.items()},
            "crossing": {k: (round(v, 1) if isinstance(v, float) else v)
                         for k, v in crossing.items()},
            "surface": {k: (round(v, 1) if isinstance(v, float) else v)
                        for k, v in surface.items()},
            "lit": {k: (round(v, 1) if isinstance(v, float) else v)
                    for k, v in lit.items()},
            "width": {k: (round(v, 1) if isinstance(v, float) else v)
                      for k, v in width.items()},
            "sidewalk": {k: (round(v, 1) if isinstance(v, float) else v)
                         for k, v in sidewalk.items()},
        }

    def segment_cost_s(self, keys: List[str],
                       graph: Optional[RoutingGraph] = None) -> List[float]:
        """V7.4 4B.3: per-segment weighted cost, aligned with the geometry.

        One number per pair of coordinates — the same alignment as ``classes``
        and the ``footway``/``crossing``/``lit`` arrays — so a client can see
        WHICH segments cost more (a crossing wait, a slope, a staircase)
        without a per-edge payload dump.
        """
        model = self._cost_model
        out = []
        g = graph if graph is not None else self._graph
        for a, b in zip(keys, keys[1:]):
            for to, w, p in g.neighbors(a):
                if to == b:
                    out.append(round(model.edge_weight(a, b, w, p), 3))
                    break
        return out

    def steps_m(self, keys: List[str], graph: Optional[RoutingGraph] = None) -> float:
        """Metres of ``highway=steps`` along a path of node keys."""
        return sum(
            w for w, p in self.edges_along(keys, graph)
            if (p.get("highway") or "").strip().lower() == "steps"
        )

    def crossings_m(self, keys: List[str], graph: Optional[RoutingGraph] = None) -> float:
        """Metres walked ON crossings along a path of node keys (V7.4 4A.4).

        The recon's sentence is the rule: 4B may only say "cross the road"
        when the underlying data can establish a crossing. This is the
        measurement of that data — the metres of the walk whose edge carries
        ``footway=crossing`` (the crossing segment OSM drew) or an explicit
        ``crossing=*`` type. Both signals come from preserved source tags;
        nothing here invents a crossing where OSM drew none.

        Mirrors ``steps_m``: a number, not an instruction — 4B turns it into
        language, this stage only makes the fact count-able and visible.
        """
        return sum(
            w for w, p in self.edges_along(keys, graph) if is_crossing_edge(p)
        )

    def segment_tags(
        self, keys: List[str], graph: Optional[RoutingGraph] = None
    ) -> List[Dict[str, Any]]:
        """What each segment of the walk IS, for a client that models shade.

        The on-device shade model assumes a facade whose height depends on the
        road class, so it needs the class of every segment — and a walk is one
        LineString with no per-vertex properties, so without this the client can
        only guess a single class for the whole route. Guessing is not harmless
        here: `primary`/`trunk` are modelled as unshadeable, and a client that
        assumed `footway` throughout would promise shade along the Corniche.

        ``area`` is the honesty flag rather than a rendering one. An open plaza
        or a surface car park has no facade beside it, and the client is
        expected to decline to model those rather than credit them with shade.

        Both ``area`` and the ``covered``/``indoor`` half of ``enclosed`` depend
        on tags this pipeline only began promoting alongside this endpoint, so
        they read ``False`` against a bake made by an older ingestion pass. That
        degrades to "no facade claim", which is the safe direction.
        """
        out: List[Dict[str, Any]] = []
        for _w, p in self.edges_along(keys, graph):
            enclosed = any(
                (p.get(k) or "").strip().lower() in ("yes", "1", "true", "building_passage")
                for k in ("tunnel", "covered", "indoor")
            )
            out.append({
                "highway": (p.get("highway") or "").strip().lower(),
                # V7.4 4A.4: the RAW pedestrian facts of the segment, exposed
                # per-segment exactly as preserved, so a client or 4B can tell
                # a crossing from a pavement (footway=crossing), a marked from
                # an unmarked crossing (crossing=*), and a lit street from a
                # dark one — without this stage interpreting any of them.
                "footway": (p.get("footway") or "").strip().lower() or None,
                "crossing": (p.get("crossing") or "").strip().lower() or None,
                "lit": (p.get("lit") or "").strip().lower() or None,
                "enclosed": enclosed,
                "area": (p.get("area") or "").strip().lower() in ("yes", "1", "true"),
            })
        return out
