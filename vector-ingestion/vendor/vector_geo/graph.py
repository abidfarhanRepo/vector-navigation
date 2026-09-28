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

# OSM writes `turn:lanes` in the way's digitisation order. A directed edge knows
# nothing about that once it is in the graph, so `add_way` — the only place that
# still sees the OSM node order — marks which way each directed edge runs
# relative to the way. The router reads it back to resolve the lane string for
# the direction actually driven (see `router._resolve_turn_lanes`).
_LANE_DIR_TAGS = ("turn:lanes", "turn:lanes:forward", "turn:lanes:backward")


class RoutingGraph:
    """In-memory routing graph keyed by normalized (lon, lat) coordinates."""

    def __init__(self) -> None:
        self._nodes: Dict[str, Coord] = {}
        self._adjacency: Dict[str, List[Edge]] = {}
        # Lazy spatial grid for fast nearest_node: cell (ix,iy) -> [keys].
        # Built on first query, invalidated whenever a node is added.
        self._grid: Optional[Dict[Tuple[int, int], List[str]]] = None
        self._grid_cell = 0.01  # ~1.1 km at the equator; fine for road snapping.
        self._grid_bbox: Optional[Tuple[int, int, int, int]] = None

    @staticmethod
    def node_key(lon: float, lat: float) -> str:
        """Return a stable node key for a (lon, lat) coordinate (7-decimal rounding)."""
        return f"{round(float(lon), 7):.7f},{round(float(lat), 7):.7f}"

    def add_node(self, lon: float, lat: float) -> str:
        """Create a node if absent and return its key."""
        key = self.node_key(lon, lat)
        if key not in self._nodes:
            self._nodes[key] = (round(float(lon), 7), round(float(lat), 7))
            self._grid = None  # invalidate the spatial index
            self._grid_bbox = None
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
        # OSM one-way values. `-1` means the way is one-way AGAINST its
        # digitisation order, so the single directed edge must be REVERSED. It
        # was previously not recognised at all, which silently made those ways
        # bidirectional -- the router would happily send a car the wrong way up
        # them. `reversible` and `alternating` are time-dependent; without a
        # schedule the safe reading is bidirectional.
        ow = props.get("oneway")
        ow = ow.strip().lower() if isinstance(ow, str) else ow
        oneway_forward = ow in (True, "yes", "1", "true")
        oneway_reverse = ow in ("-1", "reverse")

        # Direction provenance for lane guidance, and ONLY for lane guidance:
        # the marker is attached only to ways that carry a lane-turn tag, so no
        # other consumer pays for it. `props_fwd`/`props_bwd` are applied to the
        # edge that runs with/against the way's digitisation respectively.
        if any(props.get(t) for t in _LANE_DIR_TAGS):
            props_fwd = dict(props)
            props_fwd["_way_dir"] = "forward"
            props_bwd = dict(props)
            props_bwd["_way_dir"] = "backward"
        else:
            props_fwd = props_bwd = props

        prev_key: Optional[str] = None
        for lon, lat in coordinates:
            key = self.add_node(lon, lat)
            if prev_key is not None:
                weight = haversine_meters(self._nodes[prev_key], self._nodes[key])
                if oneway_reverse:
                    # `-1` travels against digitisation, so the single edge runs
                    # backward and must carry the backward lane direction.
                    self._add_edge(key, prev_key, weight, props_bwd)
                else:
                    self._add_edge(prev_key, key, weight, props_fwd)
                    if not oneway_forward:
                        self._add_edge(key, prev_key, weight, props_bwd)
            prev_key = key

    def neighbors(self, node_key: str) -> List[Edge]:
        """Return outgoing edges ``(to_key, weight_m, props)`` for a node."""
        return list(self._adjacency.get(node_key, []))

    def neighbors_iter(self, node_key: str):
        """Iterate outgoing edges without copying (hot path for pathfinding)."""
        return iter(self._adjacency.get(node_key, ()))

    def has_node(self, node_key: str) -> bool:
        """Return True if the node key exists (no dict copy)."""
        return node_key in self._nodes

    def node_coord(self, node_key: str) -> Coord:
        """Return a node's (lon, lat) without copying the whole node map."""
        return self._nodes[node_key]

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

    def _build_grid(self) -> None:
        cell = self._grid_cell
        grid: Dict[Tuple[int, int], List[str]] = {}
        for key, (lon, lat) in self._nodes.items():
            grid.setdefault((int(lon / cell), int(lat / cell)), []).append(key)
        self._grid = grid

    def nearest_node(self, lon: float, lat: float) -> str:
        """Return the key of the closest existing node.

        Uses a lazily-built spatial grid (cells ~1 km) and searches outward in
        expanding rings, so a query touches only nodes near the target instead
        of scanning the whole graph. Falls back correctly at graph edges by
        widening the ring until a candidate is found. Exact haversine is applied
        only to the small candidate set.
        """
        return self._nearest(lon, lat, predicate=None)

    def nearest_routable_node(self, lon: float, lat: float, max_m: float = 800.0) -> Optional[str]:
        """Return the nearest node with >=1 outgoing edge within ``max_m``.

        Grid-accelerated equivalent of a radius scan for a *departable* node
        (avoids snapping onto a dead-end just off the road). Returns None when
        no routable node lies within ``max_m`` so the caller can fall back to a
        plain nearest-node snap.
        """
        best = self._nearest(
            lon, lat,
            predicate=lambda k: bool(self._adjacency.get(k)),
            max_m=max_m,
        )
        return best



    # How far the ring search may expand before conceding and scanning linearly.
    # 32 cells is ~35 km: comfortably beyond any legitimate snap distance, and
    # cheap (~8k probes) compared with the linear fallback it guards.
    _RING_BUDGET = 32

    def _linear_nearest(self, target, predicate=None, max_m=None) -> Optional[str]:
        """Exact nearest node by full scan. The fallback for far-away queries."""
        best: Optional[str] = None
        best_d: Optional[float] = None
        for key, coord in self._nodes.items():
            if predicate is not None and not predicate(key):
                continue
            d = haversine_meters(target, coord)
            if max_m is not None and d > max_m:
                continue
            if best_d is None or d < best_d:
                best_d, best = d, key
        return best

    def _max_useful_radius(self, cx: int, cy: int) -> int:
        """Largest ring around (cx, cy) that can still contain a node.

        Exactly the Chebyshev distance from the query cell to the farthest
        occupied cell, so the ring search can stop the moment it has covered the
        whole graph instead of grinding out to max_radius.

        A span-only bound would be WRONG: when the query lies far outside the
        graph, the ring legitimately has to grow until it reaches the data, and
        capping at the graph's own width would return None while a nearest node
        plainly exists.
        """
        if not self._grid:
            return 0
        if self._grid_bbox is None:
            xs = [gx for (gx, _) in self._grid]
            ys = [gy for (_, gy) in self._grid]
            self._grid_bbox = (min(xs), min(ys), max(xs), max(ys))
        x0, y0, x1, y1 = self._grid_bbox
        return max(abs(cx - x0), abs(cx - x1), abs(cy - y0), abs(cy - y1))

    def _nearest(self, lon, lat, predicate=None, max_m=None) -> Optional[str]:
        if not self._nodes:
            raise ValueError("graph has no nodes")
        if self._grid is None:
            self._build_grid()
        assert self._grid is not None
        cell = self._grid_cell
        target = (float(lon), float(lat))
        cx, cy = int(lon / cell), int(lat / cell)

        best: Optional[str] = None
        best_d: Optional[float] = None
        radius = 0
        max_radius = 2048  # safety bound (~2000 km); practically never reached.
        # When a distance cap is given, stop expanding once rings can only hold
        # nodes farther than the cap (radius-1 cells are already fully covered).
        settled_at: Optional[int] = None
        max_useful = self._max_useful_radius(cx, cy)
        while radius <= max_radius:
            for gx in range(cx - radius, cx + radius + 1):
                for gy in range(cy - radius, cy + radius + 1):
                    if radius > 0 and abs(gx - cx) != radius and abs(gy - cy) != radius:
                        continue
                    for key in self._grid.get((gx, gy), ()):
                        if predicate is not None and not predicate(key):
                            continue
                        d = haversine_meters(target, self._nodes[key])
                        if max_m is not None and d > max_m:
                            continue
                        if best_d is None or d < best_d:
                            best_d, best = d, key
            if max_m is not None and (radius * cell * 111_000.0) > max_m and best is None:
                # Rings already exceed the cap and nothing matched -> give up.
                return None
            # Ring expansion is only a win when the answer is NEARBY. Once the
            # query is far from the graph, each ring costs ~8r cell probes and
            # the walk out to max_radius=2048 is ~17 million of them — the
            # router stops answering. That is reachable from a single request
            # with an out-of-region coordinate, and was hit in testing by
            # passing (lat, lon) where (lon, lat) was expected: the swapped
            # point sat ~2,600 cells away, further than max_radius itself, so
            # the search ground through every ring and still found nothing.
            #
            # So: give the rings a small budget, then fall back to an exact
            # linear scan. O(N) over the node table is ~141k haversines for
            # Qatar — tens of milliseconds, against a hang.
            if best is None and radius > min(max_useful, self._RING_BUDGET):
                return self._linear_nearest(target, predicate, max_m)
            if best is not None and settled_at is None:
                settled_at = radius
            if settled_at is not None and radius >= settled_at + 1:
                break
            radius += 1
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
