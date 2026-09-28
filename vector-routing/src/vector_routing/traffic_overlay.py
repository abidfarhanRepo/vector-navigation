"""Live traffic overlay for vector-routing (stdlib only).

The routing engine consumes ``TRAFFIC_CONGESTION`` envelopes (published by
vector-traffic, Wave 26c) and uses them to penalize congested edges during
path search. Matching is by **edge endpoint coordinates** (rounded to graph
precision) rather than a shared ``segment_id``, so the two engines stay
decoupled (ADR-0003) — traffic and routing need not agree on IDs.

A ``TrafficOverlay`` maps an undirected edge (its two endpoint coordinates) to a
penalty multiplier (>= 1.0). Heavier congestion -> larger multiplier -> the
search avoids that edge when an alternative exists.
"""

from typing import Any, Dict, List, Optional, Tuple

Coord = Tuple[float, float]

# Round to the same precision RoutingGraph uses for node keys (7 decimals).
_PRECISION = 7


def _round(c: Coord) -> Coord:
    return (round(float(c[0]), _PRECISION), round(float(c[1]), _PRECISION))


def _pair_key(a: Coord, b: Coord) -> Tuple[Coord, Coord]:
    """Order-independent key for an undirected edge between coords ``a`` and ``b``."""
    ra, rb = _round(a), _round(b)
    return (ra, rb) if ra <= rb else (rb, ra)


def _speed_factor(seg: Dict[str, Any]) -> float:
    """Penalty multiplier for a congestion segment (>= 1.0).

    A segment travelling at ``mean_speed_kmh`` against a ``free_flow_kmh``
    baseline is slower by ``free_flow / mean``. Unknown speed -> 1.0 (no
    penalty). A near-zero speed -> a large but finite penalty.
    """
    free = seg.get("free_flow_kmh")
    mean = seg.get("mean_speed_kmh")
    if not isinstance(free, (int, float)) or not isinstance(mean, (int, float)):
        return 1.0
    if mean <= 0:
        return 10.0
    factor = float(free) / float(mean)
    if factor < 1.0:
        return 1.0
    return min(factor, 10.0)


class TrafficOverlay:
    """Maps undirected edges (by endpoint coords) to congestion penalty factors."""

    def __init__(self) -> None:
        # (rounded_coord, rounded_coord) -> penalty multiplier
        self._by_pair: Dict[Tuple[Coord, Coord], float] = {}

    @classmethod
    def from_segments(cls, segments: List[Dict[str, Any]]) -> "TrafficOverlay":
        """Build an overlay from congestion envelope ``segments``.

        Each segment has ``geometry`` as a list of ``[lat, lon]`` pairs (bus
        envelope convention) and speed fields. Consecutive coordinate pairs are
        indexed in both directions (traffic is undirected).
        """
        overlay = cls()
        for seg in segments:
            geom = seg.get("geometry") or []
            # Bus envelope uses [lat, lon]; normalize to (lon, lat) Coord.
            coords: List[Coord] = []
            for pair in geom:
                if isinstance(pair, (list, tuple)) and len(pair) >= 2:
                    coords.append((float(pair[1]), float(pair[0])))
            factor = _speed_factor(seg)
            for a, b in zip(coords, coords[1:]):
                key = _pair_key(a, b)
                overlay._by_pair[key] = max(overlay._by_pair.get(key, 1.0), factor)
        return overlay

    def penalty_for(self, src: Coord, dst: Coord) -> float:
        """Penalty multiplier for the edge ``src -> dst`` (1.0 if unknown)."""
        return self._by_pair.get(_pair_key(src, dst), 1.0)

    def merge(self, other: "TrafficOverlay") -> None:
        """Absorb another overlay (latest congestion wins per edge)."""
        for key, factor in other._by_pair.items():
            self._by_pair[key] = max(self._by_pair.get(key, 1.0), factor)


class OverlayView:
    """A read-only view over a RoutingGraph that applies overlay penalties.

    Implements the same ``nodes()`` / ``neighbors()`` surface ``astar`` relies
    on, scaling each edge weight by the overlay penalty for its endpoints. This
    lets routing avoid congestion without mutating the underlying graph or the
    shared vector_geo algorithms.
    """

    def __init__(self, graph: Any, overlay: Optional[TrafficOverlay]) -> None:
        self._graph = graph
        self._overlay = overlay

    def nodes(self) -> Dict[str, Coord]:
        return self._graph.nodes()

    def has_node(self, node_key: str) -> bool:
        return self._graph.has_node(node_key)

    def node_coord(self, node_key: str) -> Coord:
        return self._graph.node_coord(node_key)

    def nearest_node(self, lon: float, lat: float) -> str:
        # Snapping uses the underlying graph's node coordinates (identical to
        # the view's), so delegate to avoid duplicating the scan.
        return self._graph.nearest_node(lon, lat)

    def nearest_routable_node(self, lon: float, lat: float, max_m: float = 800.0):
        return self._graph.nearest_routable_node(lon, lat, max_m=max_m)

    def neighbors(self, u: str):
        return list(self.neighbors_iter(u))

    def neighbors_iter(self, u: str):
        # Penalty is a function of endpoint coordinates; fetch them individually
        # instead of copying the whole node map on every expansion.
        u_coord = self._graph.node_coord(u) if self._graph.has_node(u) else None
        for to, w, p in self._graph.neighbors_iter(u):
            penalty = 1.0
            if self._overlay is not None and u_coord is not None and self._graph.has_node(to):
                penalty = self._overlay.penalty_for(u_coord, self._graph.node_coord(to))
            yield (to, w * penalty, p)
