"""Learned speed-profile overlay for vector-routing (issue 07, stdlib only).

The point at which routing stops being a function of OSM alone and starts being
a function of how people actually drive. ``vector-learning`` (S3) aggregates
 k-anonymous ``speed_profile`` facts per ``(segment, time_band)``; once such a
 fact clears its confidence threshold (issue 06) it is promoted here and the
 router uses the *observed* speed instead of the OSM class default.

Design mirrors :mod:`vector_routing.traffic_overlay` deliberately:

* **Matching is by edge endpoint coordinates**, not a shared ``segment_id``, so
  routing and learning stay decoupled (ADR-0003) and need not agree on IDs.
* **It is a weight overlay, never a graph mutation.** The base graph is
  untouched, so the whole learned layer can be switched off with one flag and
  the router falls back to exactly its previous behaviour.

Composition with live traffic (issue 07 requires this be explicit):

    learned speed  = what this road is *typically* like at this hour
    live traffic   = what this road is like *right now*

They are different inputs and they **compose multiplicatively** rather than
overwriting each other. The learned profile replaces the *baseline* assumption
(the OSM maxspeed fallback); live congestion then scales that baseline. Neither
"wins" — a road that is typically slow AND currently jammed is penalized twice,
which is correct. Where the two disagree about the baseline, live traffic is
authoritative for the current request, because it is measuring now.

Facts are consumed as plain dicts (the shape ``FactStore.query`` returns), so
this module has no dependency on ``vector-learning`` — the integration is over
files/HTTP, never in-process (ADR-0003).
"""

from typing import Any, Dict, Iterable, List, Optional, Tuple

Coord = Tuple[float, float]

# Same node-key precision RoutingGraph and TrafficOverlay use.
_PRECISION = 7

# A learned profile may not make an edge arbitrarily fast or slow; clamp the
# derived factor so one bad aggregate cannot dominate the search.
_MIN_FACTOR = 0.25
_MAX_FACTOR = 10.0

# The OSM fallback speed router._edge_time_weight assumes when maxspeed is
# absent. Kept in sync deliberately: the learned factor is expressed *relative*
# to whatever baseline the router would otherwise have used.
DEFAULT_BASELINE_KMH = 30.0

HOURS_PER_WEEK = 168  # kept for backward-compat; new code uses BANDS_PER_WEEK
BANDS_PER_WEEK = 8   # adr-0067: coarsened time-of-week bands, agreed with vector-learning


def _round(c: Coord) -> Coord:
    return (round(float(c[0]), _PRECISION), round(float(c[1]), _PRECISION))


def _pair_key(a: Coord, b: Coord) -> Tuple[Coord, Coord]:
    """Order-independent key for an undirected edge (speed profiles are undirected)."""
    ra, rb = _round(a), _round(b)
    return (ra, rb) if ra <= rb else (rb, ra)


class LearnedSpeedOverlay:
    """Maps ``(undirected edge, time_band)`` to an observed speed in km/h."""

    def __init__(self) -> None:
        self._by_pair_hour: Dict[Tuple[Tuple[Coord, Coord], int], float] = {}
        self._segment_ids: Dict[Tuple[Coord, Coord], str] = {}

    # ---- construction --------------------------------------------------

    @classmethod
    def from_facts(cls, facts: Iterable[Dict[str, Any]]) -> "LearnedSpeedOverlay":
        """Build from promoted ``speed_profile`` facts.

        ``speed_for``, ``factor_for``, and :meth:`has_profile` accept an explicit
        ``hour`` argument that is the band index (0..``BANDS_PER_WEEK-1``).
        """
        overlay = cls()
        for fact in facts:
            payload = fact.get("payload") or {}
            if fact.get("fact_type", "speed_profile") != "speed_profile":
                continue
            speed = payload.get("median_speed_kmh")
            hour = payload.get("band")
            if not isinstance(speed, (int, float)) or float(speed) <= 0:
                continue
            if not isinstance(hour, int) or not (0 <= hour < BANDS_PER_WEEK):
                continue
            coords = _coords_from(payload.get("geometry"))
            if len(coords) < 2:
                continue
            seg_id = payload.get("segment_id")
            for a, b in zip(coords, coords[1:]):
                key = _pair_key(a, b)
                overlay._by_pair_hour[(key, int(hour))] = float(speed)
                if isinstance(seg_id, str):
                    overlay._segment_ids[key] = seg_id
        return overlay

    def __len__(self) -> int:
        return len(self._by_pair_hour)

    # ---- lookup --------------------------------------------------------

    def speed_for(self, src: Coord, dst: Coord, band: int) -> Optional[float]:
        """Observed speed for this edge at this band, or ``None`` if unlearned."""
        return self._by_pair_hour.get((_pair_key(src, dst), int(band)))

    def factor_for(
        self,
        src: Coord,
        dst: Coord,
        band: int,
        baseline_kmh: float = DEFAULT_BASELINE_KMH,
    ) -> float:
        """Weight multiplier for this edge (1.0 when unlearned).

        Expressed relative to the baseline the router would otherwise use, so
        the factor slots into the same multiplicative pipeline as the live
        traffic penalty. A learned speed *faster* than the baseline yields a
        factor below 1.0 (a bonus); slower yields a penalty.

        **Graceful fallback is the whole point:** an edge with no confident
        profile returns exactly 1.0, so a router at 0% learned coverage behaves
        identically to one with no overlay at all.
        """
        speed = self.speed_for(src, dst, band)
        if speed is None or speed <= 0:
            return 1.0
        if baseline_kmh <= 0:
            baseline_kmh = DEFAULT_BASELINE_KMH
        factor = float(baseline_kmh) / float(speed)
        return max(_MIN_FACTOR, min(factor, _MAX_FACTOR))

    def has_profile(self, src: Coord, dst: Coord, band: int) -> bool:
        """Whether this edge is covered at this band — the input to issue 10's
        coverage metric and to the with-profile/without-profile ETA split."""
        return (_pair_key(src, dst), int(band)) in self._by_pair_hour

    def coverage(self) -> Dict[str, int]:
        """Distinct edges and (edge, band) buckets covered."""
        edges = {key for key, _ in self._by_pair_hour}
        return {"edges": len(edges), "edge_bands": len(self._by_pair_hour)}


def _coords_from(geometry: Any) -> List[Coord]:
    """Normalize a GeoJSON-style ``[[lng, lat], ...]`` list to ``Coord``s."""
    out: List[Coord] = []
    if not isinstance(geometry, (list, tuple)):
        return out
    for pair in geometry:
        if isinstance(pair, (list, tuple)) and len(pair) >= 2:
            try:
                out.append((float(pair[0]), float(pair[1])))
            except (TypeError, ValueError):
                continue
    return out


class LearnedSpeedView:
    """Read-only graph view applying learned speed factors during search.

    Same surface ``astar`` relies on, matching :class:`OverlayView`. Composes
    with the live-traffic view by wrapping it: build
    ``LearnedSpeedView(OverlayView(graph, traffic), learned, hour)`` and both
    factors multiply, which is the documented composition rule.

    Set ``enabled=False`` (or pass ``overlay=None``) to disable the entire
    learned layer at runtime — the rollback path issue 07 requires.
    """

    def __init__(
        self,
        graph: Any,
        overlay: Optional[LearnedSpeedOverlay],
        band: int,
        *,
        enabled: bool = True,
        baseline_kmh: float = DEFAULT_BASELINE_KMH,
    ) -> None:
        self._graph = graph
        self._overlay = overlay if enabled else None
        self._band = int(band) % BANDS_PER_WEEK
        self._baseline = baseline_kmh
        # Counters so a caller can report how much of a route was learned —
        # feeds the with-profile vs without-profile ETA split (issue 10).
        self.edges_learned = 0
        self.edges_total = 0

    def reset_counters(self) -> None:
        """Zero the learned/total counters before a fresh search."""
        self.edges_learned = 0
        self.edges_total = 0

    def learned_fraction(self) -> float:
        """Fraction of relaxed edges that had a profile (0.0 when none did)."""
        if self.edges_total <= 0:
            return 0.0
        return self.edges_learned / self.edges_total

    def nodes(self) -> Dict[str, Coord]:
        return self._graph.nodes()

    def has_node(self, node_key: str) -> bool:
        return self._graph.has_node(node_key)

    def node_coord(self, node_key: str) -> Coord:
        return self._graph.node_coord(node_key)

    def nearest_node(self, lon: float, lat: float) -> str:
        return self._graph.nearest_node(lon, lat)

    def nearest_routable_node(self, lon: float, lat: float, max_m: float = 800.0):
        return self._graph.nearest_routable_node(lon, lat, max_m=max_m)

    def neighbors(self, u: str):
        return list(self.neighbors_iter(u))

    def neighbors_iter(self, u: str):
        u_coord = self._graph.node_coord(u) if self._graph.has_node(u) else None
        for to, w, p in self._graph.neighbors_iter(u):
            self.edges_total += 1
            factor = 1.0
            if self._overlay is not None and u_coord is not None and self._graph.has_node(to):
                to_coord = self._graph.node_coord(to)
                factor = self._overlay.factor_for(
                    u_coord, to_coord, self._band, baseline_kmh=self._edge_baseline(p)
                )
                if self._overlay.has_profile(u_coord, to_coord, self._band):
                    self.edges_learned += 1
            yield (to, w * factor, p)

    def _edge_baseline_public(self, props: Any) -> float:
        """Exposed for :class:`CompiledLearnedOverlay`, which folds it in early."""
        return self._edge_baseline(props)

    def _edge_baseline(self, props: Any) -> float:
        """The speed the router *would* have assumed for this edge.

        This is what makes the factor arithmetically exact rather than merely
        directional. The router derives both its search cost and its ETA as
        ``length / baseline``; scaling the length by ``baseline / learned``
        turns both into ``length / learned`` with no further changes anywhere.

        Using a fixed baseline instead would make the ETA on a learned 100 km/h
        motorway wrong by the ratio of its real limit to that constant — the
        route would be picked correctly and then mis-timed, which is the harder
        bug to notice.
        """
        if not isinstance(props, dict):
            return self._baseline
        try:
            from .router import parse_kmh
        except ImportError:  # pragma: no cover - package always ships router
            return self._baseline
        speed = parse_kmh(props)
        if speed is None or float(speed) <= 0:
            return self._baseline
        return float(speed)


# ---------------------------------------------------------------------------
# Compiled form: the same arithmetic, resolved once instead of per relaxation.
# ---------------------------------------------------------------------------
#
# :class:`LearnedSpeedView` is correct but does real work on **every edge the A*
# frontier touches**: a node_coord call, two coordinate roundings, a tuple
# compare, a dict lookup and a maxspeed parse. Measured on the full Qatar graph
# (1.32 M nodes / 2.50 M directed edges) that costs +242% on ``/route`` — 2.34 s
# to 7.98 s. That is the same shape as the Wave 26c traffic-overlay blow-up
# documented in ``RoutingService.navigate``, and issue 07 explicitly forbids it:
# "loading a profile set must not regress route latency".
#
# The fix is not to make the per-edge work faster but to stop doing it per edge.
# An overlay is small (~20 k buckets) and static between promotions, while the
# frontier is millions of edges. So resolve the whole overlay to graph node-key
# pairs ONCE when it is loaded, folding the per-edge baseline in at that point,
# and leave the hot path with a single dict lookup on keys it already holds.


class CompiledLearnedOverlay:
    """A learned overlay resolved against one specific graph.

    Holds ``hour -> u_key -> {v_key: factor}``, with the edge's own maxspeed
    already folded into each factor. Build it once per overlay load; it is
    immutable and safe to share across concurrent requests.

    The nesting is a performance decision, not a style one. Nearly every node
    the frontier expands has *nothing* learned on it, and an outer lookup keyed
    by the source node alone lets those nodes skip the inner work entirely —
    no tuple to allocate, no second lookup. Flattening this to
    ``{(u, v): factor}`` costs a tuple allocation per relaxed edge, which over
    2.5 M edges is most of the remaining overhead.
    """

    def __init__(self) -> None:
        self._by_hour: Dict[int, Dict[str, Dict[str, float]]] = {}
        self.resolved_edges = 0
        self.unresolved_edges = 0

    @classmethod
    def compile(cls, graph: Any, overlay: Optional[LearnedSpeedOverlay]) -> "CompiledLearnedOverlay":
        compiled = cls()
        if overlay is None or len(overlay) == 0:
            return compiled

        resolver = _KeyResolver(graph)
        # A throwaway view, used only for its baseline-from-props logic so the
        # two code paths cannot disagree about what the baseline is.
        baseline_helper = LearnedSpeedView(graph, None, 0)

        for (pair, band), speed in overlay._by_pair_hour.items():
            coord_a, coord_b = pair
            key_a = resolver.resolve(coord_a)
            key_b = resolver.resolve(coord_b)
            if key_a is None or key_b is None:
                compiled.unresolved_edges += 1
                continue
            bucket = compiled._by_hour.setdefault(int(band), {})
            placed = False
            for u, v in ((key_a, key_b), (key_b, key_a)):
                props = _props_between(graph, u, v)
                if props is None:
                    continue
                baseline = baseline_helper._edge_baseline_public(props)
                factor = baseline / float(speed) if speed > 0 else 1.0
                bucket.setdefault(u, {})[v] = max(_MIN_FACTOR, min(factor, _MAX_FACTOR))
                placed = True
            if placed:
                compiled.resolved_edges += 1
            else:
                compiled.unresolved_edges += 1
        return compiled

    def __len__(self) -> int:
        return sum(len(inner) for by_u in self._by_hour.values() for inner in by_u.values())

    def factors_for_band(self, band: int) -> Dict[str, Dict[str, float]]:
        """``u_key -> {v_key: factor}`` for one band (empty dict if none)."""
        return self._by_hour.get(int(band) % BANDS_PER_WEEK, {})

    def stats(self) -> Dict[str, int]:
        return {
            "bands": len(self._by_hour),
            "directed_edges": len(self),
            "resolved": self.resolved_edges,
            "unresolved": self.unresolved_edges,
        }


class _KeyResolver:
    """Maps a coordinate to a graph node key.

    Fast path: ``vector_geo``'s key format is a pure function of the rounded
    coordinate, so the key can be computed and confirmed with ``has_node`` — no
    index, no allocation. Fallback: for graphs whose keys are not
    coordinate-derived (test doubles, custom graphs) build a reverse index from
    ``nodes()`` on first miss. The fallback is only ever paid by small graphs,
    because a real one always hits the fast path.
    """

    def __init__(self, graph: Any) -> None:
        self._graph = graph
        self._index: Optional[Dict[Coord, str]] = None

    def resolve(self, coord: Coord) -> Optional[str]:
        key = f"{round(float(coord[0]), 7):.7f},{round(float(coord[1]), 7):.7f}"
        try:
            if self._graph.has_node(key):
                return key
        except Exception:  # noqa: BLE001 - an exotic graph must not break loading
            pass
        if self._index is None:
            self._index = {}
            try:
                for node_key, node_coord in self._graph.nodes().items():
                    self._index[_round(node_coord)] = node_key
            except Exception:  # noqa: BLE001
                self._index = {}
        return self._index.get(_round(coord))


def _props_between(graph: Any, u: str, v: str) -> Optional[Dict[str, Any]]:
    """Edge props for the directed edge ``u -> v``, or ``None`` if absent."""
    try:
        for to, _w, props in graph.neighbors_iter(u):
            if to == v:
                return props if isinstance(props, dict) else {}
    except Exception:  # noqa: BLE001
        return None
    return None


class CompiledLearnedView:
    """The hot-path view: one dict lookup per relaxed edge.

    Same surface as :class:`LearnedSpeedView` and the same resulting weights —
    the difference is only *when* the work happens. Keep both: the uncompiled
    view stays the readable reference implementation and works against any
    graph-like object, while this one is what a 2.5 M-edge search uses.
    """

    def __init__(
        self,
        graph: Any,
        compiled: Optional[CompiledLearnedOverlay],
        band: int,
        *,
        enabled: bool = True,
    ) -> None:
        self._graph = graph
        self._band = int(band) % BANDS_PER_WEEK
        self._factors: Dict[str, Dict[str, float]] = (
            compiled.factors_for_band(self._band) if (compiled is not None and enabled) else {}
        )
        self.edges_learned = 0
        self.edges_total = 0

    def nodes(self) -> Dict[str, Coord]:
        return self._graph.nodes()

    def has_node(self, node_key: str) -> bool:
        return self._graph.has_node(node_key)

    def node_coord(self, node_key: str) -> Coord:
        return self._graph.node_coord(node_key)

    def nearest_node(self, lon: float, lat: float) -> str:
        return self._graph.nearest_node(lon, lat)

    def nearest_routable_node(self, lon: float, lat: float, max_m: float = 800.0):
        return self._graph.nearest_routable_node(lon, lat, max_m=max_m)

    def neighbors(self, u: str):
        return list(self.neighbors_iter(u))

    def neighbors_iter(self, u: str):
        # Two early exits, and between them they cover almost every expansion:
        # nothing learned for this hour at all, and nothing learned leaving THIS
        # node. Both hand the underlying iterator straight through, so an
        # unlearned neighbourhood costs one dict lookup for the whole node
        # rather than one per edge.
        from_u = self._factors.get(u) if self._factors else None
        if from_u is None:
            yield from self._graph.neighbors_iter(u)
            return
        get = from_u.get
        for to, w, p in self._graph.neighbors_iter(u):
            factor = get(to)
            if factor is None:
                yield (to, w, p)
            else:
                self.edges_learned += 1
                yield (to, w * factor, p)

    def learned_fraction(self) -> float:
        if self.edges_total <= 0:
            return 0.0
        return self.edges_learned / self.edges_total
