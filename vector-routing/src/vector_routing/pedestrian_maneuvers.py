"""Structured pedestrian maneuver FACTS, sourced from graph geometry and promoted OSM data.

4B.1 of V7.4. The recon's rule, restated for this layer: Vector must not say
"cross the road" unless the map can establish a crossing. 4A.4 made the
establishment visible — ``footway=crossing`` and ``crossing=*`` ride on foot-graph
edge props, and the ``<region>_crossings.geojson`` artifact preserves the crossing
TYPE (marked/zebra/traffic_signals/uncontrolled/...) that mostly rides on
`highway=crossing` NODES (only 168 of 2,989 Qatari crossing ways carry their own
`crossing=*` tag).

This module turns a ROUTE — a list of node keys plus the graph that produced them
— into a sequence of structured maneuver facts. It is deliberately NOT an
instruction generator:

* no prose, no voice phrases, no "turn left in 200 metres";
* every fact carries the source that established it (edge props, the crossings
  artifact, or the bearing geometry itself), so nothing here is a guess;
* a crossing fact is NEVER inferred from two roads intersecting — it requires a
  ``footway=crossing`` way or an explicit ``crossing=*`` edge tag;
* a stairs fact requires ``highway=steps`` edges, and step facts
  (``step_count``/``handrail``/``incline``) appear only where the source promoted
  them.

Facts, in route order::

    depart          the walk begins (index 0)
    cross           a maximal run of crossing edges, with approach/enter/leave
                    positions, the crossing type, and the crossed road when the
                    road graph can name it
    stairs          a maximal run of highway=steps edges, plus the promoted step
                    facts when they exist
    turn            a real direction change (windowed bearing), with the way
                    being travelled after it
    transition      the travelled way's identity changes (sidewalk -> road,
                    footway -> pedestrian area, ...) without a real turn
    arrive          the walk ends

Heading towards 4B.2 (which converts facts into maneuvers like "cross the road")
and 4B.4 (the /foot wire contract that exposes them): a fact is the truth a
client or 4B.2 can act on, and the standing doctrine — source fact -> graph fact
-> measured behavior -> interpretation -> presentation — places this module at
"graph fact -> measured behavior".
"""

import json
import math
import os
from typing import Any, Callable, Dict, Iterable, List, Optional, Tuple

from .graph import RoutingGraph
from .router import (
    MANEUVER_BEARING_WINDOW_M,
    _classify_turn,
    _windowed_bearings,
    bearing_deg,
    normalize_angle,
)
from .pedestrian_cost import is_crossing_edge
from .signals import SignalIndex

#: How far a `highway=crossing` artifact node may be from a walked crossing
#: segment and still name it. The artifact node usually sits at the crossing
#: point (the road centreline the pedestrian crosses); the crossing WAY is a
#: few metres of footway drawn across the carriageway. 15 m is generous for
#: the short Qatari crossings (p50 13.6 m) while still refusing a neighbour's
#: crossing: measured over the whole 260912 bake, 2,734 of 2,961 crossing ways
#: have an artifact node within it.
CROSSING_NODE_RADIUS_M = 15.0

#: Grid cell for the crossings-artifact lookup. 0.005 deg is ~550 m at Qatar's
#: latitude: crossing nodes are a few thousand points over the whole country,
#: so a coarse cell keeps each query to a handful of list scans.
CATALOG_CELL_DEG = 0.005

# A way counts as the crossed ROAD (as opposed to the pavement it connects to)
# when its highway class is one a vehicle drives. The foot graph also carries
# walkable versions of these (residential streets, foot=yes arterials): they
# are still roads, and a crossing crosses them.
DRIVABLE_CLASSES = frozenset({
    "motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link",
    "secondary", "secondary_link", "tertiary", "tertiary_link", "unclassified",
    "residential", "service", "living_street", "road",
})

#: Turn classes worth a fact. "continue" is the absence of a turn; a fact that
#: says "you are continuing" exists only as a transition (the way changed under
#: you). Mirrors the driving merger's vocabulary, minus prose.
_TURN_CLASSES = ("slight-left", "slight-right", "turn-left", "turn-right", "uturn")

# The raw props a fact may cite directly as source material. Everything beyond
# these (surface, lit, width, ...) is preserved on the edges but is not part of
# a maneuver's identity — same discipline as the driving identity() function.
_IDENTITY_PROPS = ("highway", "footway", "name", "name_en")


class CrossingCatalog:
    """The preserved ``<region>_crossings.geojson`` artifact, queryable by point.

    4A.4 wrote ``highway=crossing`` (and kerb-only) NODES to a separate artifact
    under the signals/cameras/barriers doctrine: raw tags (``crossing=marked``,
    ``kerb=lowered``, ``tactile_paving``), OSM node id as feature id, never in
    the main collection. It was staged but deliberately not loaded — 4A.4 stayed
    data. This is the first consumer: it answers "what kind of crossing is here?"
    for a crossing run, so a cross fact can say *marked* rather than only
    "crossing".

    Absence is a normal state: a deployment baked before 4A.4 has no catalog,
    and cross facts then carry whatever ``crossing=*`` the WAY itself promoted
    (168 of 2,961 Qatari crossing ways have one), or None.
    """

    def __init__(self, features: Iterable[Dict[str, Any]] = ()) -> None:
        self._features: List[Dict[str, Any]] = []
        self._grid: Dict[Tuple[int, int], List[Dict[str, Any]]] = {}
        for f in features:
            props = f.get("properties") or {}
            if props.get("kind") != "crossing":
                continue       # kerb-only points are kerb facts, not crossing facts
            geom = f.get("geometry") or {}
            coord = geom.get("coordinates")
            if not coord or len(coord) < 2:
                continue
            self._features.append(f)
            gx = int(coord[0] / CATALOG_CELL_DEG)
            gy = int(coord[1] / CATALOG_CELL_DEG)
            self._grid.setdefault((gx, gy), []).append(f)

    @classmethod
    def from_feature_collection(cls, fc: Any) -> "CrossingCatalog":
        if isinstance(fc, dict) and "features" in fc:
            return cls(fc["features"])
        if isinstance(fc, list):
            return cls(fc)
        return cls()

    @classmethod
    def from_path(cls, path: str) -> "CrossingCatalog":
        if not path or not os.path.exists(path):
            return cls()
        try:
            with open(path, "r", encoding="utf-8") as fh:
                return cls.from_feature_collection(json.load(fh))
        except (OSError, ValueError):
            return cls()

    def __len__(self) -> int:
        return len(self._features)

    def near(self, lon: float, lat: float,
             radius_m: float = CROSSING_NODE_RADIUS_M) -> Optional[Dict[str, Any]]:
        """The closest crossing node within ``radius_m``, as preserved facts.

        Returns the RAW tags the artifact carried — only the keys that exist, so
        an unmarked crossing node (``crossing=unmarked``) stays distinguishable
        from a node with no type at all. ``distance_m`` is included so a client
        can see how close the fact's evidence was.
        """
        best: Optional[Tuple[float, Dict[str, Any]]] = None
        gx, gy = int(lon / CATALOG_CELL_DEG), int(lat / CATALOG_CELL_DEG)
        span = int(math.ceil(radius_m / (111_000.0 * CATALOG_CELL_DEG))) + 1
        for x in range(gx - span, gx + span + 1):
            for y in range(gy - span, gy + span + 1):
                for f in self._grid.get((x, y), ()):
                    p = f["geometry"]["coordinates"]
                    d = _haversine((lon, lat), (p[0], p[1]))
                    if d <= radius_m and (best is None or d < best[0]):
                        best = (d, f)
        if best is None:
            return None
        _d, f = best
        props = f.get("properties") or {}
        out: Dict[str, Any] = {
            "id": f.get("id"),
            "distance_m": round(_d, 1),
        }
        for key in ("crossing", "crossing_markings", "kerb", "tactile_paving"):
            if props.get(key):
                out[key] = props[key]
        return out


def _haversine(a: Tuple[float, float], b: Tuple[float, float]) -> float:
    """Metres between two (lon, lat) points, mirroring :mod:`vector_routing.haversine`."""
    R = 6_371_000.0
    lon1, lat1 = math.radians(a[0]), math.radians(a[1])
    lon2, lat2 = math.radians(b[0]), math.radians(b[1])
    dlon, dlat = lon2 - lon1, lat2 - lat1
    h = math.sin(dlat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(dlon / 2) ** 2
    return 2 * R * math.asin(math.sqrt(h))


# ---------------------------------------------------------------------------
# Route decomposition: segments, distances, runs, identity
# ---------------------------------------------------------------------------

def route_segments(node_keys: List[str], graph: RoutingGraph) -> List[Dict[str, Any]]:
    """Per-geometry-segment facts for a path of node keys.

    One entry per ``(node_keys[i], node_keys[i+1])`` pair — the same alignment
    ``FootRouter.segment_tags`` guarantees on the wire — carrying the edge props
    the graph built the segment from, its metres, and its outgoing bearing.
    """
    out: List[Dict[str, Any]] = []
    for a, b in zip(node_keys, node_keys[1:]):
        props: Dict[str, Any] = {}
        w = 0.0
        for to, weight, p in graph.neighbors(a):
            if to == b:
                props, w = p, weight
                break
        out.append({
            "props": props,
            "distance_m": w,
            "bearing": bearing_deg(graph.node_coord(a), graph.node_coord(b)),
        })
    return out


def _cumulative(segments: List[Dict[str, Any]]) -> List[float]:
    """Distance along the route to the start of each segment (cum[0] == 0)."""
    out = [0.0]
    for s in segments:
        out.append(out[-1] + s["distance_m"])
    return out


def _identity(seg: Dict[str, Any]) -> tuple:
    """The pedestrian-way identity of a segment: what a person is walking ON.

    ``(highway, footway, name)`` — the same trio that decides "am I still on the
    same path?" for driving (``Router._build_steps.identity``), extended with
    ``footway`` so a sidewalk and a footway that both report highway=footway are
    distinguished — which is exactly the distinction 4B.2 needs for "leave the
    walkway".
    """
    p = seg["props"]
    return (p.get("highway"), p.get("footway"), p.get("name"))


def _way_identity(seg: Dict[str, Any]) -> Dict[str, Any]:
    """A fact's ``road``/``from``/``to`` shape: the identity, as preserved tags."""
    p = seg["props"]
    return {k: p[k] for k in _IDENTITY_PROPS if p.get(k)}


def _source_props(seg: Dict[str, Any]) -> Dict[str, Any]:
    """The identity props of a segment, as the ``source`` of a boundary fact."""
    return {k: v for k, v in seg["props"].items() if k in _IDENTITY_PROPS}


def _is_crossing_seg(seg: Dict[str, Any]) -> bool:
    """Is this segment a crossing? See :func:`pedestrian_cost.is_crossing_edge`."""
    return is_crossing_edge(seg["props"])


def _is_steps_seg(seg: Dict[str, Any]) -> bool:
    return (seg["props"].get("highway") or "").strip().lower() == "steps"


def _runs(predicate: Callable[[Dict[str, Any]], bool],
          segments: List[Dict[str, Any]]) -> List[Tuple[int, int]]:
    """Maximal runs of consecutive segments satisfying ``predicate``.

    Returns ``(start_index, end_index_exclusive)`` pairs in segment-index space.
    A crossing drawn as several consecutive OSM ways (as the diagonal Corniche
    crossings are) is ONE run: one event a person performs, not three.
    """
    runs: List[Tuple[int, int]] = []
    i = 0
    n = len(segments)
    while i < n:
        if not predicate(segments[i]):
            i += 1
            continue
        j = i
        while j < n and predicate(segments[j]):
            j += 1
        runs.append((i, j))
        i = j
    return runs


# ---------------------------------------------------------------------------
# Fact builders
# ---------------------------------------------------------------------------

def _signals_on_run(run: Tuple[int, int], node_keys: List[str],
                    signals: Optional[SignalIndex]) -> List[Dict[str, Any]]:
    """The surveyed signals that stand ON this crossing, in route order.

    The association is the graph's own identity: a signal node is in the list
    because the crossing way has that vertex, not because anything is nearby.
    See :class:`vector_routing.signals.SignalIndex` for the measurement behind
    that rule.

    Each entry carries ``index`` — the signal's own vertex on the walk geometry,
    which is the same index space as ``enter_index``/``leave_index`` — so a map
    can place the marker at the surveyed position without projecting, matching
    or rounding anything.

    Empty when no catalog is loaded, when the deployment baked none, or when
    the crossing genuinely has no signal on it -- all three are the same answer
    to the only question this answers, which is *did the map say this crossing
    has a signal?*
    """
    if not signals or len(signals) == 0:
        return []
    start, end = run
    out: List[Dict[str, Any]] = []
    seen: set = set()
    for i in range(start, end + 1):
        sig = signals.at(node_keys[i])
        if sig is None or sig["id"] in seen:
            continue
        seen.add(sig["id"])
        out.append(dict(sig, index=i))
    return out


def _cross_fact(run: Tuple[int, int], segments: List[Dict[str, Any]],
                cum: List[float], coords: List[tuple], node_keys: List[str],
                crossings: Optional[CrossingCatalog],
                road_graph: Optional[RoutingGraph],
                signals: Optional[SignalIndex] = None) -> Dict[str, Any]:
    """One crossing event: approach, enter, leave, type, and the road crossed.

    The event's truth never depends on the enrichments: the run came from
    ``footway=crossing``/``crossing=*`` edge props. Everything else is a fact
    ABOUT it — the type when an artifact node or a way tag names it, the road
    when the road graph can name it — each traceable to its own source, each
    None when absent rather than guessed.
    """
    start, end = run
    enter = start               # vertex index: first node ON the crossing
    leave = end                 # vertex index: first node AFTER the crossing
    approach = enter - 1        # vertex index: last node still off it
    cross_m = sum(s["distance_m"] for s in segments[start:end])
    mid = (start + end) // 2    # a segment in the middle of the run

    # What made these edges a crossing, kept as the fact's source.
    source: Dict[str, Any] = {}
    way_type: Optional[str] = None
    for s in segments[start:end]:
        t = (s["props"].get("crossing") or "").strip()
        if t and not way_type:
            way_type = t
    if segments[start]["props"].get("footway"):
        source["footway"] = str(segments[start]["props"]["footway"]).strip().lower()
    if way_type:
        source["crossing"] = way_type

    # The type: the crossing's own tag wins; otherwise the highway=crossing
    # artifact node near the middle of the run (the 4A.4 doctrine: the type
    # usually lives on the NODE, not the way).
    crossing_type = way_type
    type_source = "way" if way_type else None
    artifact: Optional[Dict[str, Any]] = None
    if crossings is not None and len(crossings) > 0 and not way_type:
        point = coords[mid if mid < len(coords) else enter]
        artifact = crossings.near(point[0], point[1])
        if artifact is not None:
            type_source = "catalog" if artifact.get("crossing") else "catalog_node"
            crossing_type = artifact.get("crossing") or None

    return {
        "type": "cross",
        "index": enter,
        "distance_m": round(cum[enter], 1),
        # The three positions of a crossing, as vertex indexes a client can
        # place on the geometry: APPROACH is the last node before the crossed
        # road (this is where "in X m, cross the road" comes from), ENTER is
        # the first node on it, LEAVE the first node after it.
        "approach_index": approach if approach >= 0 else None,
        "approach_distance_m": round(cum[approach], 1) if approach >= 0 else 0.0,
        "enter_index": enter,
        "leave_index": leave,
        "crossing_distance_m": round(cross_m, 1),
        # How far the walk goes from the approach position (the last node off
        # the crossing) to the crossing itself — the "in N m, cross the road"
        # number, derived from the geometry rather than guessed.
        "distance_to_crossing_m": round(
            cum[enter] - (cum[approach] if approach >= 0 else cum[enter]), 1),
        "crossing": crossing_type,
        "crossing_type_source": type_source,
        # Preserved crossing-site facts, from the artifact when it has them.
        "crossing_markings": artifact.get("crossing_markings") if artifact else None,
        "kerb": artifact.get("kerb") if artifact else None,
        "tactile_paving": artifact.get("tactile_paving") if artifact else None,
        "road": _road_crossed(run, node_keys, road_graph),
        "road_source": ("road_graph_shared_node"
                         if road_graph is not None else None),
        # V7 traffic lights: the SURVEYED signals standing on this crossing,
        # established by graph node identity. Empty is the normal state and
        # carries no implication that the crossing is uncontrolled -- it says
        # only that the map does not place a signal on it. Nothing here is a
        # phase, a cycle or a state: the source has no timing anywhere.
        "signals": _signals_on_run(run, node_keys, signals),
        "source": source,
        "bearing": round(segments[start]["bearing"], 2),
    }


def _road_crossed(run: Tuple[int, int], node_keys: List[str],
                  road_graph: Optional[RoutingGraph]) -> Optional[Dict[str, Any]]:
    """The road a crossing crosses, when the road graph can name it.

    Sourced the cheap, traceable way: OSM draws a crossing way THROUGH the road
    it crosses, usually sharing node coordinates with it (measured on the
    260912 bake: 2,871 of 2,961 crossing ways have a node that coincides with a
    car-road node, 1,812 of them at a NAMED road). So the crossed road is found
    by looking the crossing run's node keys up in the road graph's adjacency.
    The road that shares the most crossing nodes is the one the crossing runs
    across; a tangential road at a junction shares a coincident node but not the
    run's length, so the count prefers the crossed road over the neighbour.

    Returns ``None`` when no road graph was supplied or no road shares a node —
    the ~3 % of Qatari crossings drawn without touching the carriageway. A cross
    fact with ``road: null`` is a true fact about the map, not a hole.
    """
    if road_graph is None:
        return None
    scored: Dict[tuple, List[float]] = {}
    start, end = run
    # Every vertex the crossing touches, from the approach-side node (index
    # ``start``) through the leave node (index ``end`` on the node list).
    for n in node_keys[start:end + 1]:
        for _to, _w, p in road_graph.neighbors(n):
            hw = p.get("highway")
            if not hw or hw not in DRIVABLE_CLASSES:
                continue
            ident = (hw, p.get("name"), p.get("name:en"))
            scored.setdefault(ident, [0.0, float("inf")])
            scored[ident][0] += 1.0
    if not scored:
        return None
    # Most shared nodes; ties broken with the nearest shared node (a dead
    # tiebreak weight here, retained for deterministic ordering).
    best = max(scored.items(), key=lambda kv: (kv[1][0], -kv[1][1]))
    hw, name, name_en = best[0]
    return {"highway": hw, "name": name, "name_en": name_en}


def _stairs_fact(run: Tuple[int, int], segments: List[Dict[str, Any]],
                 cum: List[float]) -> Dict[str, Any]:
    """One stairs event, plus only the step facts the source actually promoted."""
    start, end = run
    source: Dict[str, Any] = {"highway": "steps"}
    extra: Dict[str, Any] = {}
    for s in segments[start:end]:
        for key in ("step_count", "handrail", "incline"):
            v = s["props"].get(key)
            if v and key not in extra:
                extra[key] = str(v)
                source[key] = str(v)
    return {
        "type": "stairs",
        "index": start,
        "distance_m": round(cum[start], 1),
        "begin_index": start,
        "end_index": end,
        "stairs_distance_m": round(sum(s["distance_m"] for s in segments[start:end]), 1),
        **extra,
        "source": source,
        "bearing": round(segments[start]["bearing"], 2),
    }


def _turn_facts(segments: List[Dict[str, Any]], cum: List[float],
                coords: List[tuple], suppressed: set) -> Dict[int, Dict[str, Any]]:
    """One fact per real direction change, windowed like the driving merger.

    ``_windowed_bearings`` measures 25 m either side of a vertex (see
    ``router.MANEUVER_BEARING_WINDOW_M``), so split-junction geometry — several
    short vertices spreading one physical 90-degree corner — reads as ONE turn,
    and a gentle bend on a long footpath reads as "continue". Consecutive turns
    of the same corner within one window collapse to the sharpest reading, the
    same non-maximum suppression the driving steps use.

    ``suppressed`` holds vertex indexes inside or immediately beside crossing or
    stairs runs: there the cross/stairs fact is the event, and a window pulled
    half over the crossing would misread the approach bend as a turn anyway.

    Returns ``{vertex_index: fact}`` — no prose, just the measured geometry.
    """
    delta_at: Dict[int, float] = {}
    for i in range(1, len(segments)):
        if i in suppressed:
            continue
        in_b, out_b = _windowed_bearings(coords, segments, i)
        delta_at[i] = normalize_angle(out_b - in_b)

    kind_at: Dict[int, str] = {
        i: _classify_turn(d) for i, d in delta_at.items() if _classify_turn(d) in _TURN_CLASSES
    }

    def _same_corner(i: int, j: int) -> bool:
        return abs(normalize_angle(delta_at[i] - delta_at[j])) < 90.0

    def _collapse(run):
        if len(run) < 2:
            return
        keep = max(run, key=lambda k: abs(delta_at[k]))
        for k in run:
            if k != keep:
                del kind_at[k]

    corner: List[int] = []
    for i in sorted(kind_at):
        if (corner
                and cum[i] - cum[corner[-1]] < MANEUVER_BEARING_WINDOW_M
                and _same_corner(i, corner[-1])):
            corner.append(i)
            continue
        _collapse(corner)
        corner = [i]
    _collapse(corner)

    out: Dict[int, Dict[str, Any]] = {}
    for i, kind in kind_at.items():
        out[i] = {
            "type": "turn",
            "index": i,
            "distance_m": round(cum[i], 1),
            "turn": kind,
            "delta_deg": round(delta_at[i], 1),
            # The way the walk continues on after the corner.
            "road": _way_identity(segments[i]),
            # Bibliography: this fact IS the bearing geometry — the measurement
            # window that produced it, so the source claim is explicit and
            # falsifiable; there is no OSM tag behind a corner.
            "source": {"bearing_window_m": MANEUVER_BEARING_WINDOW_M},
            "bearing": round(segments[i]["bearing"], 2),
        }
    return out


def _transition_facts(segments: List[Dict[str, Any]], cum: List[float],
                      turn_indexes: set, suppressed: set) -> List[Dict[str, Any]]:
    """One fact per change of pedestrian-way identity that is not already an event.

    "Leave the walkway onto the road" and "sidewalk into a pedestrian square"
    are transitions a guidance system must be able to SEE, but the same boundary
    is already the cross fact's leave phase or the stairs fact's end phase, so
    those are suppressed here — this fact fires for the others.

    A transition is, by construction, a CONTINUE: any vertex where the walk also
    turns became a turn fact (which carries the outgoing way identity, exactly
    like the driving "turn right onto X"). So "the way changed under you, and
    you did not turn" is 4B.2's "continue onto/into ...", as a fact, not a
    phrase, and the only possible turn classification here is "continue".
    """
    out: List[Dict[str, Any]] = []
    for i in range(1, len(segments)):
        if i in suppressed or i in turn_indexes:
            continue
        if _identity(segments[i - 1]) == _identity(segments[i]):
            continue
        out.append({
            "type": "transition",
            "index": i,
            "distance_m": round(cum[i], 1),
            "from": _way_identity(segments[i - 1]),
            "to": _way_identity(segments[i]),
            "turn": "continue",
            "source": _source_props(segments[i - 1]),
            "bearing": round(segments[i]["bearing"], 2),
        })
    return out


def build_pedestrian_maneuvers(
    node_keys: List[str],
    graph: RoutingGraph,
    crossings: Optional[CrossingCatalog] = None,
    road_graph: Optional[RoutingGraph] = None,
    signals: Optional[SignalIndex] = None,
) -> List[Dict[str, Any]]:
    """The structured maneuver facts of a walk: geometry + source data, no prose.

    ``node_keys`` is the walked path (``Route.node_keys``); ``graph`` is the foot
    graph the route was found on. ``crossings`` (the 4A.4 artifact) enriches
    crossing facts with their preserved type/kerb/tactile facts; ``road_graph``
    (usually the service's car graph) lets a cross fact name the road it
    crosses. Neither is required: without them the facts are still complete and
    sourced — a cross fact is a cross fact because the walked edge says
    ``footway=crossing``, not because a catalog was loaded.

    ``signals`` (V7 traffic lights) adds the surveyed signals that stand ON a
    crossing, by graph node identity. Also not required: without it, ``signals``
    on a cross fact is empty, which is the same answer a signal-free crossing
    gives — the fact layer never claims a crossing is UNCONTROLLED, only whether
    the map places a signal on it.

    Emits exactly one fact per genuine event, in route order, each with
    ``index`` (a vertex index, aligned with the walk geometry) and ``distance_m``
    (walked metres at that vertex)::

        depart, cross*, stairs*, turn*, transition*, arrive

    ``distance_to_next_m`` closes every fact except arrive, so "in X m, cross /
    turn / take the stairs" is derivable from the facts alone — the 4B.2 phrase
    is not this module's job.
    """
    if len(node_keys) < 2:
        return []

    segments = route_segments(node_keys, graph)
    coords = [graph.node_coord(k) for k in node_keys]
    cum = _cumulative(segments)

    cross_runs = _runs(_is_crossing_seg, segments)
    stairs_runs = _runs(_is_steps_seg, segments)

    # Vertices claimed by run events: inside either kind of run, or on the
    # boundary vertex at each end. A turn measured with its window half over a
    # crossing is geometry, not a maneuver, and the leave phase of a crossing
    # is the cross fact's own position.
    in_run: set = set()
    for start, end in cross_runs + stairs_runs:
        for i in range(start, end + 1):
            in_run.add(i)

    delta_at: Dict[int, float] = {}
    for i in range(1, len(segments)):
        if i in in_run:
            continue
        in_b, out_b = _windowed_bearings(coords, segments, i)
        delta_at[i] = normalize_angle(out_b - in_b)

    turn_indexes = _turn_facts(segments, cum, coords, in_run)
    transitions = _transition_facts(segments, cum, set(turn_indexes), in_run)

    events: Dict[int, List[Dict[str, Any]]] = {0: []}
    if segments:
        events[0].append({
            "type": "depart",
            "index": 0,
            "distance_m": 0.0,
            "road": _way_identity(segments[0]),
            "source": _source_props(segments[0]),
            "bearing": round(segments[0]["bearing"], 2),
        })
    for run in cross_runs:
        events.setdefault(run[0], []).append(
            _cross_fact(run, segments, cum, coords, node_keys, crossings, road_graph,
                        signals))
    for run in stairs_runs:
        events.setdefault(run[0], []).append(
            _stairs_fact(run, segments, cum))
    for i, fact in turn_indexes.items():
        events.setdefault(i, []).append(fact)
    for fact in transitions:
        events.setdefault(fact["index"], []).append(fact)

    last = len(node_keys) - 1
    events.setdefault(last, []).append({
        "type": "arrive",
        "index": last,
        "distance_m": round(cum[last], 1),
        "road": _way_identity(segments[-1]) if segments else None,
        "source": _source_props(segments[-1]) if segments else {},
        "bearing": 0.0,
    })

    ordered: List[Dict[str, Any]] = []
    for i in sorted(events):
        ordered.extend(events[i])
    for idx, fact in enumerate(ordered):
        nxt = ordered[idx + 1] if idx + 1 < len(ordered) else None
        fact["distance_to_next_m"] = (
            round(cum[nxt["index"]] - fact["distance_m"], 1)
            if nxt is not None else 0.0)
    return ordered