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
    """
    if weight is None:
        weight = lambda _u, _to, w, _p: w
    if source not in graph.nodes() or target not in graph.nodes():
        raise NoRouteError(source, target)
    dist: Dict[str, float] = {source: 0.0}
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
        for to, w, p in graph.neighbors(u):
            nd = d + weight(u, to, w, p)
            if nd < dist.get(to, float("inf")):
                dist[to] = nd
                prev[to] = u
                heapq.heappush(pq, (nd, to))
    if target not in dist:
        raise NoRouteError(source, target)
    return dist[target], _reconstruct(prev, source, target)


def astar(
    graph: RoutingGraph,
    source: str,
    target: str,
    heuristic: Callable[[Coord, Coord], float] = haversine_meters,
    weight: Optional[Callable[[str, str, float, Dict[str, Any]], float]] = None,
) -> Tuple[float, List[str]]:
    """Return ``(total_distance_m, path_node_keys)`` via A*.

    Uses an admissible haversine heuristic between node coordinates, so the
    returned distance equals the Dijkstra optimum on a connected graph.

    ``weight(u, to, w, props)`` overrides the per-edge relaxation cost (the
    default is the raw stored distance ``w``). When routing by travel time,
    pass a weight that returns ``w / speed_ms``; the returned
    ``total_distance_m`` is still the summed RAW distance so ETA/distance
    reporting stays correct.

    Raises ``NoRouteError`` if the target is unreachable.
    """
    if weight is None:
        weight = lambda _u, _to, w, _p: w
    if source not in graph.nodes() or target not in graph.nodes():
        raise NoRouteError(source, target)
    nodes = graph.nodes()
    target_coord = nodes[target]

    if weight is not None:
        # When routing by a non-distance cost (e.g. travel time), the g-score is
        # in those cost units while ``heuristic`` returns raw metres. Mixing
        # units in the f-score silently turns A* back into a distance search.
        # Scale the heuristic into cost units using a high reference speed
        # (140 km/h) so it stays ADMISSIBLE (true remaining cost >= distance /
        # max_speed) while remaining consistent with the g-score units.
        _ref_speed_ms = 140.0 / 3.6

        def h(node: str) -> float:
            return heuristic(nodes[node], target_coord) / _ref_speed_ms
    else:
        def h(node: str) -> float:
            return heuristic(nodes[node], target_coord)

    g_score: Dict[str, float] = {source: 0.0}
    prev: Dict[str, str] = {}
    pq: List[Tuple[float, float, str]] = [(h(source), 0.0, source)]
    visited = set()
    while pq:
        _f, d, u = heapq.heappop(pq)
        if u == target:
            break
        if u in visited:
            continue
        visited.add(u)
        for to, w, p in graph.neighbors(u):
            nd = d + weight(u, to, w, p)
            if nd < g_score.get(to, float("inf")):
                g_score[to] = nd
                prev[to] = u
                heapq.heappush(pq, (nd + h(to), nd, to))
    if target not in g_score:
        raise NoRouteError(source, target)
    return g_score[target], _reconstruct(prev, source, target)


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
