"""The signal catalog and its projection onto a route (V7 Stage 5).

Bootstrap bakes ``<region>_signals.geojson`` beside the roads and foot
networks -- a FeatureCollection of ``kind=signal`` points carrying the OSM
node id (feature ``id`` = ``n<osm>``) and, where OSM recorded one,
``traffic_signals:direction``. This module loads THAT artifact and answers
one question for the router: *which signals does this route pass, where
along it, and from which approach?*

What a signal is NOT, and what nothing here claims:

* a phase, cycle, length or offset -- the extract has none and no OSM tag is
  one. The wire contract deliberately carries no timing field, so a client
  has nothing to fabricate a countdown from;
* the stop line -- the OSM node sits at the junction/crossing, a proxy with
  built-in tolerance the client's arrival window accounts for.

The projection is route-relative: each signal is projected PERPENDICULARLY
onto the route's own segments (not matched to the nearest geographic point),
rejected beyond a snap gate, ordered by along-route distance, and identical
positions deduplicated. Doubled-back routes are handled by the client's
matching model with the same windowed doctrine RouteTracker uses; here the
sorted along-route answer is what the wire carries.

The catalog is OPTIONAL and its absence is a valid state: a deployment baked
before this step, or one that chose not to bake signals, must route exactly
as it did before. Absence means the ``signals`` key is simply absent from the
``/navigate`` reply.

V7 traffic lights adds the second consumer: :class:`SignalIndex`, the same
catalog keyed by the GRAPH NODE each signal stands on, so a walked crossing
can ask "is there a surveyed signal on my own geometry?" and get an answer
established by identity rather than by a radius. See that class for why the
distinction matters.
"""

import json
import logging
import math
import os
from typing import Any, Dict, List, Optional, Tuple

from .haversine import haversine_meters

logger = logging.getLogger("vector_routing.signals")

Coord = Tuple[float, float]

#: How far off the route a signal may sit and still be claimed as on it.
#: The route's own snap tolerance: a signal at a junction the route passes is
#: ~0 m away; 40 m covers a crossing signal drawn slightly off the carriageway
#: without claiming a signal half a street away.
SNAP_MAX_M = 40.0

#: Two signals closer than this (metres) are the same junction. OSM sometimes
#: models one physical set of lights as two nodes (one per stop line).
DUPLICATE_M = 10.0


class SignalCatalog:
    """The baked signal points, loaded defensively and never mutated.

    ``__len__`` of an absent or unreadable file is 0 -- a missing catalog is
    the normal state of a pre-Stage-5 deployment, not an error.
    """

    def __init__(self, signals: List[Dict[str, Any]]) -> None:
        self._signals = signals

    def __len__(self) -> int:
        return len(self._signals)

    def __iter__(self):
        return iter(self._signals)

    @classmethod
    def from_feature_collection(cls, fc: Dict[str, Any]) -> "SignalCatalog":
        out: List[Dict[str, Any]] = []
        for f in fc.get("features", []) or []:
            props = f.get("properties") or {}
            if props.get("kind") != "signal":
                continue
            geo = f.get("geometry") or {}
            coords = geo.get("coordinates")
            if not coords or len(coords) != 2:
                continue
            lon, lat = float(coords[0]), float(coords[1])
            if not (math.isfinite(lon) and math.isfinite(lat)):
                continue
            out.append({
                # The feature id is the stable OSM identity (`n<id>`) and is
                # the id the client's callout source and telemetry reuse.
                "id": f.get("id") or f"signal-{len(out)}",
                "lon": lon,
                "lat": lat,
                # `traffic_signals:direction` is provenance, never the
                # approach definition. The approach is the route's bearing at
                # the signal's along-route position, which is what this module
                # computes below.
                "direction": props.get("traffic_signals:direction"),
            })
        return cls(out)

    @classmethod
    def from_path(cls, path: Optional[str]) -> "SignalCatalog":
        if not path or not os.path.exists(path):
            return cls([])
        try:
            with open(path, encoding="utf-8") as fh:
                doc = json.load(fh)
            if isinstance(doc, dict):
                return cls.from_feature_collection(doc)
            return cls([])
        except (OSError, ValueError):
            logger.warning("signal catalog unreadable, continuing without it: %s", path)
            return cls([])

    def describe(self) -> Dict[str, Any]:
        return {"signals": len(self._signals)}


def osm_source(signal_id: str) -> str:
    """The provenance string for a catalog entry's id.

    Catalog ids are the feature ids bootstrap writes (``n<osm>``), so the
    provenance is ``osm:node:<osm id>`` -- the same spelling
    :class:`dev.vector.geo.signal.SignalRef` uses on the client. An id that is
    not an OSM node id (a hand-built fixture, say) is labelled with what it
    actually is rather than being dressed up as one.
    """
    raw = str(signal_id)
    if raw[:1] == "n" and raw[1:].isdigit():
        return f"osm:node:{raw[1:]}"
    return f"signal:{raw}"


class SignalIndex:
    """The catalog keyed by the GRAPH NODE each signal stands on (V7 traffic lights).

    ## Why identity and not proximity

    A signal's position is a raw OSM coordinate; a graph node's position is
    that coordinate rounded to the graph's own 7-decimal key. When they agree,
    the mapper has stated that the signal IS a vertex of that way -- it is a
    fact about the source geometry, and it is exactly zero metres wide.

    A radius is not the same statement. Measured on the real Qatar bake, a
    single junction's signal nodes sit within 40 m of crossing nodes and of
    crossing WAYS the signal does not control (208 of 899 signals are within
    40 m of a node tagged ``crossing=traffic_signals``, but only 118 crossing
    ways contain a signal as a vertex). Attaching by radius therefore attaches
    signals to crossings they are merely near, which is how a walker gets told
    to expect a signal at an unmarked kerb.

    ## What an entry says and does not say

    ``id``, ``source`` and ``node`` -- a location and its provenance. There is
    no phase, no cycle and no state, because the source has none: measured over
    the whole extract, not one of the 899 signal nodes carries a timing-shaped
    tag, and the artifact bootstrap writes carries none either. Being reachable
    through this index is a statement about WHERE a signal is.
    """

    def __init__(self, by_node: Dict[str, Dict[str, Any]]) -> None:
        self._by_node = by_node

    def __len__(self) -> int:
        return len(self._by_node)

    def __bool__(self) -> bool:
        return bool(self._by_node)

    def __iter__(self):
        """The graph node keys carrying a surveyed signal."""
        return iter(self._by_node)

    @classmethod
    def from_catalog(cls, catalog: Optional[SignalCatalog],
                     graph: Any) -> "SignalIndex":
        """Index ``catalog`` against ``graph``'s own node keys.

        ``graph`` is any :class:`vector_geo.graph.RoutingGraph`-shaped object
        (``has_node`` / ``node_key``): the pedestrian graph for a walk, the
        road graph for a drive. A signal whose coordinate is not a node of the
        supplied graph is NOT indexed -- it is unassociated, which is a real
        and reportable state (41 of Qatar's 899 signals are not on the driving
        graph and 469 are not on the pedestrian one), not an error to paper
        over with a rounded position.
        """
        by_node: Dict[str, Dict[str, Any]] = {}
        if catalog is None or graph is None:
            return cls(by_node)
        for sig in catalog:
            key = graph.node_key(sig["lon"], sig["lat"])
            if not graph.has_node(key):
                continue
            by_node[key] = {
                "id": sig["id"],
                "source": osm_source(sig["id"]),
                # The graph node key IS the evidence: the signal's coordinate
                # and this vertex are the same point, so the association can be
                # re-checked against the bake by anyone reading the wire.
                "node": key,
            }
        return cls(by_node)

    def at(self, node_key: Optional[str]) -> Optional[Dict[str, Any]]:
        """The surveyed signal standing on ``node_key``, or ``None``."""
        if not node_key:
            return None
        return self._by_node.get(node_key)

    def describe(self) -> Dict[str, Any]:
        return {"associated": len(self._by_node)}


def _point_segment(sig: Coord, a: Coord, b: Coord) -> Tuple[float, float]:
    """Perpendicular projection of ``sig`` onto segment ``a->b``.

    Returns ``(t, offset_m)`` where ``t`` is the fraction along the segment
    (clamped to [0,1]) and ``offset_m`` the perpendicular distance in metres.
    Segment-based, not vertex-based: on a long straight motorway a vehicle
    dead-centre in its lane would otherwise sit 100 m from the nearest vertex
    while being 0 m from the road.
    """
    x1, y1 = a
    x2, y2 = b
    px, py = sig
    dx, dy = x2 - x1, y2 - y1
    denom = dx * dx + dy * dy
    if denom <= 1e-18:
        t = 0.0
    else:
        t = ((px - x1) * dx + (py - y1) * dy) / denom
        t = max(0.0, min(1.0, t))
    proj = (x1 + t * dx, y1 + t * dy)
    off = haversine_meters(sig, proj)
    return t, off


def _seg_bearing(a: Coord, b: Coord) -> float:
    """Bearing of the segment, reused from the router's own helper."""
    from .router import bearing_deg

    return bearing_deg(a, b)


def signals_on_path(
    catalog: SignalCatalog,
    path: List[Coord],
    snap_max_m: float = SNAP_MAX_M,
    duplicate_m: float = DUPLICATE_M,
) -> List[Dict[str, Any]]:
    """The signals this route passes, as along-route facts, in route order.

    ``path`` is the route's own coordinate list ([lon, lat] pairs). Each
    signal is projected perpendicularly onto the closest segment; those beyond
    ``snap_max_m`` are not on this route and are not claimed; the survivors
    are ordered by along-route distance and same-position duplicates merged.

    Returns entries: ``{"id", "lon", "lat", "along_m", "approach_bearing"}``.
    Empty for an empty catalog -- which is also the whole contract a
    pre-Stage-5 deployment gets.
    """
    if not catalog._signals or len(path) < 2:
        return []

    # Cumulative distance per segment, so a projection maps to along_m.
    seg_len = [haversine_meters(path[i], path[i + 1]) for i in range(len(path) - 1)]
    cum = [0.0]
    for s in seg_len:
        cum.append(cum[-1] + s)
    total = cum[-1]
    if total <= 0.0:
        return []

    matched: List[Dict[str, Any]] = []
    for sig in catalog._signals:
        point: Coord = (sig["lon"], sig["lat"])
        best_t, best_off, best_i = None, None, None
        for i in range(len(path) - 1):
            t, off = _point_segment(point, path[i], path[i + 1])
            if best_off is None or off < best_off:
                best_t, best_off, best_i = t, off, i
        if best_off is None or best_off > snap_max_m:
            # Not on this route. Off-route signals are never claimed (a
            # signal 2 km away is a different junction the driver is not
            # approaching).
            continue
        along = cum[best_i] + best_t * seg_len[best_i]
        # The approach is the route's own bearing at the signal, windowed
        # across a short span so the number is stable on a curve (the same
        # doctrine Callouts.place uses on the client).
        span = 12.0
        fwd = cum[best_i] + best_t * seg_len[best_i] + span
        a_i = best_i
        while fwd > cum[a_i + 1] and a_i + 1 < len(path) - 1:
            a_i += 1
        bearing = _seg_bearing(path[a_i], path[a_i + 1])
        matched.append({
            "id": sig["id"],
            "lon": round(sig["lon"], 6),
            "lat": round(sig["lat"], 6),
            "along_m": round(along, 1),
            "approach_bearing": round(bearing, 1),
        })

    matched.sort(key=lambda m: (m["along_m"], m["id"]))
    # Same-position duplicates (one physical set of lights mapped twice) are
    # one signal for the driver. Different signals at the same junction -- a
    # genuine 20 m apart -- stay distinct.
    out: List[Dict[str, Any]] = []
    for m in matched:
        if out and abs(m["along_m"] - out[-1]["along_m"]) <= duplicate_m:
            continue
        out.append(m)
    return out