"""Routing graph shared across Vector Python engines.

A `RoutingGraph` stores nodes keyed by a rounded (lon, lat) tuple and directed
edge adjacency with haversine-derived weights. Undirected ways add both
directions; one-way ways add a single directed edge. Parallel duplicate edges
are merged keeping the shorter weight.
"""

from typing import Any, Dict, List, Optional, Tuple

from .haversine import haversine_meters

Coord = Tuple[float, float]
Edge = Tuple[str, float, Dict[str, Any]]


class RoutingGraph:
    """In-memory routing graph keyed by normalized (lon, lat) coordinates."""

    def __init__(self) -> None:
        self._nodes: Dict[str, Coord] = {}
        self._adjacency: Dict[str, List[Edge]] = {}

    @staticmethod
    def node_key(lon: float, lat: float) -> str:
        """Return a stable node key for a (lon, lat) coordinate (7-decimal rounding)."""
        return f"{round(float(lon), 7):.7f},{round(float(lat), 7):.7f}"

    def add_node(self, lon: float, lat: float) -> str:
        """Create a node if absent and return its key."""
        key = self.node_key(lon, lat)
        if key not in self._nodes:
            self._nodes[key] = (round(float(lon), 7), round(float(lat), 7))
        return key

    def _add_edge(self, src: str, dst: str, weight: float, props: Dict[str, Any]) -> None:
        edge_props: Dict[str, Any] = dict(props)
        edge_props["length_m"] = weight
        adj = self._adjacency.setdefault(src, [])
        for i, (to, w, _p) in enumerate(adj):
            if to == dst:
                if weight < w:
                    adj[i] = (dst, weight, edge_props)
                return
        adj.append((dst, weight, edge_props))

    def add_way(self, coordinates: List[Coord], props: Optional[Dict[str, Any]] = None) -> None:
        """Add a poly-line way as a sequence of edges between consecutive coordinates."""
        props = dict(props or {})
        if not coordinates or len(coordinates) < 2:
            return
        oneway = props.get("oneway") in (True, "yes", "1")
        prev_key: Optional[str] = None
        for lon, lat in coordinates:
            key = self.add_node(lon, lat)
            if prev_key is not None:
                weight = haversine_meters(self._nodes[prev_key], self._nodes[key])
                self._add_edge(prev_key, key, weight, props)
                if not oneway:
                    self._add_edge(key, prev_key, weight, props)
            prev_key = key

    def neighbors(self, node_key: str) -> List[Edge]:
        """Return outgoing edges ``(to_key, weight_m, props)`` for a node."""
        return list(self._adjacency.get(node_key, []))

    def nodes(self) -> Dict[str, Coord]:
        """Return the node key -> (lon, lat) mapping."""
        return dict(self._nodes)

    def edges(self) -> List[Tuple[str, str, float, Dict[str, Any]]]:
        """Return all directed edges as ``(src, dst, weight_m, props)`` tuples."""
        out: List[Tuple[str, str, float, Dict[str, Any]]] = []
        for src, adj in self._adjacency.items():
            for to, w, p in adj:
                out.append((src, to, w, p))
        return out

    def edge_count(self) -> int:
        """Return the total number of directed edges."""
        return sum(len(adj) for adj in self._adjacency.values())

    def nearest_node(self, lon: float, lat: float) -> str:
        """Return the key of the closest existing node (linear scan)."""
        if not self._nodes:
            raise ValueError("graph has no nodes")
        best = None
        best_d = None
        target = (float(lon), float(lat))
        for key, coord in self._nodes.items():
            d = haversine_meters(target, coord)
            if best_d is None or d < best_d:
                best_d = d
                best = key
        assert best is not None
        return best

    def to_dict(self) -> Dict[str, Any]:
        """Serialize the graph to a plain dict (nodes + directed adjacency)."""
        return {
            "nodes": {k: list(v) for k, v in self._nodes.items()},
            "adjacency": {
                k: [[to, w, dict(p)] for (to, w, p) in adj]
                for k, adj in self._adjacency.items()
            },
        }

    @classmethod
    def from_dict(cls, d: Dict[str, Any]) -> "RoutingGraph":
        """Reconstruct a graph from ``to_dict`` output."""
        g = cls()
        for k, coord in d.get("nodes", {}).items():
            g._nodes[k] = (float(coord[0]), float(coord[1]))
        for k, adj in d.get("adjacency", {}).items():
            g._adjacency[k] = [(to, float(w), dict(p)) for (to, w, p) in adj]
        return g
