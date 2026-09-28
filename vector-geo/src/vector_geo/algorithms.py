"""Shortest-path algorithms and graph building shared across Vector engines."""

import heapq
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional, Tuple

from .errors import NoRouteError
from .graph import RoutingGraph
from .haversine import haversine_meters

Coord = Tuple[float, float]


@dataclass
class Route:
    """A computed route through the routing graph."""

    distance_m: float
    duration_s: Optional[float]
    path: List[Coord]
    node_keys: List[str]
    waypoint_count: int


def _reconstruct(prev: Dict[str, str], source: str, target: str) -> List[str]:
    path = [target]
    while path[-1] != source:
        path.append(prev[path[-1]])
    path.reverse()
    return path


def dijkstra(
    graph: RoutingGraph, source: str, target: str,
    weight: Optional[Callable[[str, str, float, Dict[str, Any]], float]] = None,
) -> Tuple[float, List[str]]:
    """Return ``(total_distance_m, path_node_keys)`` via Dijkstra.

    The edge cost used for relaxation is ``weight(u, to, w, props)`` when
    supplied, otherwise the raw stored distance ``w`` (metres). The returned
    ``total_distance_m`` is ALWAYS the summed raw distance (so callers report
    true kilometres even when routing by travel time).

    Raises ``NoRouteError`` if the target is unreachable from the source.

    **Ignores turn restrictions.** There is no ``banned_turn`` here because
    honouring one needs the (node, approach) state ``astar`` carries. Use
    ``astar`` for anything a driver follows.
    """
    if weight is None:
        weight = lambda _u, _to, w, _p: w
    if not graph.has_node(source) or not graph.has_node(target):
        raise NoRouteError(source, target)
    dist: Dict[str, float] = {source: 0.0}
    # Parallel raw-distance accumulator so the returned total is summed raw
    # metres even when ``weight`` optimizes a non-distance cost (travel time).
    raw: Dict[str, float] = {source: 0.0}
    prev: Dict[str, str] = {}
    pq: List[Tuple[float, str]] = [(0.0, source)]
    visited = set()
    while pq:
        d, u = heapq.heappop(pq)
        if u == target:
            break
        if u in visited:
            continue
        visited.add(u)
        for to, w, p in graph.neighbors_iter(u):
            nd = d + weight(u, to, w, p)
            if nd < dist.get(to, float("inf")):
                dist[to] = nd
                raw[to] = raw[u] + w
                prev[to] = u
                heapq.heappush(pq, (nd, to))
    if target not in dist:
        raise NoRouteError(source, target)
    return raw[target], _reconstruct(prev, source, target)


def astar(
    graph: RoutingGraph,
    source: str,
    target: str,
    heuristic: Callable[[Coord, Coord], float] = haversine_meters,
    weight: Optional[Callable[[str, str, float, Dict[str, Any]], float]] = None,
    banned_turn: Optional[Callable[[Optional[str], str, str], bool]] = None,
    turn_nodes: Optional[Any] = None,
) -> Tuple[float, List[str]]:
    """Return ``(total_distance_m, path_node_keys)`` via A*.

    Uses an admissible haversine heuristic between node coordinates, so the
    returned distance equals the Dijkstra optimum on a connected graph.

    ``weight(u, to, w, props)`` overrides the per-edge relaxation cost (the
    default is the raw stored distance ``w``). When routing by travel time,
    pass a weight that returns ``w / speed_ms``; the returned
    ``total_distance_m`` is still the summed RAW distance so ETA/distance
    reporting stays correct.

    ## Turn restrictions

    ``banned_turn(approach, at, next)`` forbids a movement through a junction.
    Honouring it exactly requires the search state to be **(node, approach)**
    rather than ``node``, because "is this move legal?" now depends on how the
    node was entered. Collapsing that to one state per node — which this
    function used to do, reading the predecessor of whichever path settled the
    node first — silently prunes the alternative approach, and the search then
    reports NO ROUTE for a journey that is perfectly legal. Measured on the
    Qatar graph, that happened on **11 of 20** probes across banned junctions,
    each burning ~5 s exhausting the frontier before conceding.

    ``turn_nodes`` is the optimisation that makes exactness affordable: only
    nodes in that set carry an approach in their state, because at every other
    node the legal exits do not depend on the entry. Qatar has 2,179 such nodes
    out of 1.3 M, so the state space grows by a fraction of a percent instead of
    by the average out-degree. Omit it and EVERY node becomes stateful — still
    correct, just slower; correctness is the default and the cheap path is the
    thing you opt into.

    Raises ``NoRouteError`` if the target is unreachable.
    """
    if weight is None:
        weight = lambda _u, _to, w, _p: w
    if not graph.has_node(source) or not graph.has_node(target):
        raise NoRouteError(source, target)
    target_coord = graph.node_coord(target)

    if weight is not None:
        # When routing by a non-distance cost (e.g. travel time), the g-score is
        # in those cost units while ``heuristic`` returns raw metres. Mixing
        # units in the f-score silently turns A* back into a distance search.
        # Scale the heuristic into cost units using a high reference speed
        # (140 km/h) so it stays ADMISSIBLE (true remaining cost >= distance /
        # max_speed) while remaining consistent with the g-score units.
        _ref_speed_ms = 140.0 / 3.6

        def h(node: str) -> float:
            return heuristic(graph.node_coord(node), target_coord) / _ref_speed_ms
    else:
        def h(node: str) -> float:
            return heuristic(graph.node_coord(node), target_coord)

    # A state is (node, approach). ``approach`` is None wherever the entry
    # direction cannot change the legal exits, which is every node when no
    # restrictions are in play — so the state space is identical to the old
    # node-keyed search in that case.
    restricted = banned_turn is not None
    stateful_everywhere = restricted and turn_nodes is None

    def _state(node: str, approach: Optional[str]):
        if not restricted:
            return (node, None)
        if stateful_everywhere or node in turn_nodes:
            return (node, approach)
        return (node, None)

    start = _state(source, None)
    g_score: Dict[Any, float] = {start: 0.0}
    # Raw distance (metres) accumulated along the SAME relaxation path as the
    # optimized cost, so the returned total honours the documented contract
    # (summed raw distance) even when ``weight`` optimizes travel time.
    raw_score: Dict[Any, float] = {start: 0.0}
    prev: Dict[Any, Any] = {}
    # A monotonic tiebreaker keeps the heap from ever comparing two states,
    # which would raise on ``(str, None) < (str, str)``.
    counter = 0
    pq: List[Tuple[float, float, int, Any]] = [(h(source), 0.0, counter, start)]
    visited = set()
    goal: Optional[Any] = None
    while pq:
        _f, d, _c, st = heapq.heappop(pq)
        u, approach = st
        if u == target:
            goal = st
            break
        if st in visited:
            continue
        visited.add(st)
        for to, w, p in graph.neighbors_iter(u):
            # ``approach`` is exact wherever it matters: a node that any
            # restriction can ban at is in ``turn_nodes`` and therefore carries
            # it. That is a CONTRACT on the caller — pass a ``turn_nodes`` that
            # omits a node the predicate bans at and the ban is silently not
            # enforced there. Callers should derive both from the same object
            # (``TurnRestrictions.as_predicate()`` / ``.via_nodes``) so the two
            # cannot drift apart.
            if restricted and banned_turn(approach, u, to):
                continue
            nd = d + weight(u, to, w, p)
            to_st = _state(to, u)
            if nd < g_score.get(to_st, float("inf")):
                g_score[to_st] = nd
                raw_score[to_st] = raw_score[st] + w
                prev[to_st] = st
                counter += 1
                heapq.heappush(pq, (nd + h(to), nd, counter, to_st))
    if goal is None:
        raise NoRouteError(source, target)
    path = [goal]
    while path[-1] != start:
        path.append(prev[path[-1]])
    path.reverse()
    return raw_score[goal], [node for node, _a in path]


def _as_coord_list(feature: Dict[str, Any]) -> Optional[List[Coord]]:
    geom = feature.get("geometry")
    if isinstance(geom, dict) and geom.get("type") == "LineString":
        coords = geom.get("coordinates")
        if isinstance(coords, list) and coords:
            return [(float(c[0]), float(c[1])) for c in coords]
    if "coordinates" in feature and isinstance(feature["coordinates"], list):
        return [(float(c[0]), float(c[1])) for c in feature["coordinates"]]
    return None


def build_graph_from_features(features: Any) -> RoutingGraph:
    """Build a ``RoutingGraph`` from GeoJSON-style LineString features.

    Accepts either a FeatureCollection (``{"features": [...]}``), a bare list of
    features, or already-normalized dicts. Non-LineString / empty geometries are
    ignored gracefully.
    """
    g = RoutingGraph()
    if isinstance(features, dict) and "features" in features:
        features = features["features"]
    if not isinstance(features, list):
        return g
    for feat in features:
        if not isinstance(feat, dict):
            continue
        coords = _as_coord_list(feat)
        if not coords:
            continue
        props = feat.get("properties") or {}
        g.add_way(coords, dict(props))
    return g
