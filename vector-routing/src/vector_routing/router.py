"""High-level router for vector-routing (nearest-node snapping + route assembly)."""

import math
from typing import Any, Dict, List, Optional, Tuple, Union

from .algorithms import Route, astar
from .errors import EndpointTooFarError, RouteError
from .graph import RoutingGraph
from .haversine import haversine_meters
from .camera_catalog import CameraCatalog, cameras_on_path
from .signals import SignalCatalog, signals_on_path
from .speeds import ETA_DRIVE_BIAS, effective_kmh, effective_ms, junction_delay_s

Coord = Tuple[float, float]
Endpoint = Union[Coord, Dict[str, float]]


def _as_coord(c: Endpoint) -> Coord:
    if isinstance(c, dict):
        return (float(c["lon"]), float(c["lat"]))
    return (float(c[0]), float(c[1]))


def bearing_deg(a: Coord, b: Coord) -> float:
    """Return the initial forward azimuth (degrees) from ``a`` to ``b`` in (-180, 180]."""
    lon1, lat1 = math.radians(a[0]), math.radians(a[1])
    lon2, lat2 = math.radians(b[0]), math.radians(b[1])
    dlon = lon2 - lon1
    y = math.sin(dlon) * math.cos(lat2)
    x = math.cos(lat1) * math.sin(lat2) - math.sin(lat1) * math.cos(lat2) * math.cos(dlon)
    return math.degrees(math.atan2(y, x))


def normalize_angle(a: float) -> float:
    """Normalize an angle to the half-open interval ``(-180, 180]``."""
    a = a % 360.0
    if a > 180.0:
        a -= 360.0
    return a


def _cardinal(bearing: float) -> str:
    """Return the 8-point compass name for a bearing in degrees."""
    names = ["north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest"]
    idx = round((bearing % 360.0) / 45.0) % 8
    return names[idx]


def _classify_turn(delta: float) -> str:
    """Classify a turn given the bearing delta (outgoing - incoming), normalized to (-180, 180]."""
    mag = abs(delta)
    if mag < 20:
        return "continue"
    if mag < 45:
        return "slight-left" if delta < 0 else "slight-right"
    if mag < 135:
        return "turn-left" if delta < 0 else "turn-right"
    return "uturn"


# How far either side of a vertex the approach and exit bearings are measured.
#
# Comparing only the two edges TOUCHING a vertex reads the geometry, not the
# maneuver. A junction modelled with several short vertices spreads a real
# 90-degree turn across them, so every individual delta falls under the
# 20-degree "continue" threshold and the turn is announced as "continue on the
# same road" -- the driver is told to carry straight on and drives past it. The
# mirror image is a dogleg: two opposite 70-degree bends five metres apart, each
# large enough to be called a turn, where the road merely jogs.
#
# Both were found on live Doha routes and CORROBORATED BY OSM's own lane data:
# 8 of 67 maneuvers carrying a `turn:lanes` strip contradicted their own
# instruction, e.g. "Continue on شارع الكورنيش" beside a strip saying every lane
# turns left. Measured over a 25 m window, those cases resolve to a real turn of
# 23-61 degrees, and the dogleg to a net 0.
#
# 25 m is chosen to be longer than junction geometry (a slip-road taper or a
# kerb radius is a few metres) and shorter than the 15 m maneuver-merging
# distance times two, so two genuinely separate maneuvers cannot be smeared into
# one. It does not blunt real curvature: a motorway sweeping bend of 30 degrees
# over 300 metres still measures ~2.5 degrees across 25 m.
MANEUVER_BEARING_WINDOW_M = 25.0


def _windowed_bearings(coords, segments, i, span=MANEUVER_BEARING_WINDOW_M):
    """Approach and exit bearings at vertex ``i``, measured over ``span`` metres.

    Falls back to the adjacent edge at the ends of the route, and whenever the
    window cannot be filled -- which is the old behaviour, and correct there:
    with less than ``span`` metres of road available there is nothing else to
    measure against.
    """
    j = i
    d = 0.0
    while j > 0 and d < span:
        d += segments[j - 1]["distance_m"]
        j -= 1
    k = i
    d = 0.0
    while k < len(segments) and d < span:
        d += segments[k]["distance_m"]
        k += 1
    in_b = bearing_deg(coords[j], coords[i]) if j != i else segments[i - 1]["bearing"]
    out_b = bearing_deg(coords[i], coords[k]) if k != i else segments[i]["bearing"]
    return in_b, out_b


def _lane_fields(segments, i, n):
    """Lane/exit hints for the maneuver at vertex `i`, from its APPROACH."""
    if i <= 0 or i - 1 >= len(segments):
        return {}
    approach = segments[i - 1]
    out = {}
    # Raw tag, kept on the wire for compatibility. It is NOT direction-resolved:
    # `lane_data.turn_lanes` below is what the client should read.
    if approach.get("turn_lanes"):
        out["turn_lanes"] = approach["turn_lanes"]
    # Provenance + the lanes resolved for the direction actually driven. The
    # object is ALWAYS emitted for a step with an approach so a client can tell
    # `source: "none"` (no lane data) from an all-valid strip (lane data that
    # narrows nothing). `preferred`/`preferred_reason` are deliberately omitted:
    # there is no per-lane destination or connectivity data in this extract to
    # justify a preference, and a fabricated one is worse than none.
    resolved, direction, source = _resolve_turn_lanes(approach)
    lane_data: Dict[str, Any] = {"source": source, "direction": direction}
    if resolved:
        lane_data["turn_lanes"] = resolved
    # Lanes in the direction DRIVEN, which is not `approach_lanes` on a two-way
    # road. Always present (possibly null) on a step with an approach, so a
    # client can tell "this backend does not know" from "this backend is too old
    # to have been asked" — the same distinction `source` draws for `turn:lanes`.
    lane_data["forward_lanes"] = _forward_lane_count(approach, resolved)
    out["lane_data"] = lane_data
    lane_count = _lane_count(approach.get("lanes"))
    if lane_count is not None:
        out["approach_lanes"] = lane_count
    if approach.get("exit_ref"):
        out["exit_ref"] = approach["exit_ref"]
    if approach.get("destination"):
        out["destination"] = approach["destination"]
    return out


def _lane_count(value: Any) -> Optional[int]:
    """`lanes` as a positive int, or None when it is absent/nonsense."""
    try:
        n = int(value)
    except (TypeError, ValueError):
        return None
    return n if n > 0 else None


# The one-way values `vector_geo.graph.add_way` acts on, restated here because
# this module has to reach the same verdict about the same way. `-1`/`reverse`
# ARE one-way — the way is digitised against travel, so the graph emitted a
# single reversed edge — which is why they belong in this tuple and not outside
# it. Anything else (absent, `no`, `reversible`, `alternating`) is two-way, for
# the reason `add_way` gives: without a schedule the safe reading is both ways.
_ONEWAY_VALUES = ("yes", "1", "true", "-1", "reverse")


def _is_oneway(seg: Dict[str, Any]) -> bool:
    """Does this segment's way carry traffic in one direction only?"""
    ow = seg.get("oneway")
    if ow is True:
        return True
    if not isinstance(ow, str):
        return False
    return ow.strip().lower() in _ONEWAY_VALUES


def _lane_string_is_directional(seg: Dict[str, Any]) -> bool:
    """Does the resolved `turn:lanes` describe ONE direction of travel?

    On a one-way way it always does: the way has one direction and its lanes are
    that direction's. On a two-way way only the `:forward`/`:backward` suffixed
    tags do. An UNSUFFIXED `turn:lanes` on a two-way way is ambiguous — OSM
    discourages it, and the 16 Qatari ways that carry it cannot be read as
    "these are the forward lanes" without guessing.

    That guess is the one this function exists to refuse, because it fails in
    the worst available direction: reading a 2-cell string as 2 forward lanes on
    a `lanes=2` two-way road places the route across BOTH carriageways, i.e.
    into oncoming traffic. Falling back to halving `lanes` keeps the route on
    the driven side and simply declines to make a lane-level claim.
    """
    if _is_oneway(seg):
        return True
    way_dir = seg.get("lane_dir")
    if way_dir == "forward" and seg.get("turn_lanes_forward"):
        return True
    if way_dir == "backward" and seg.get("turn_lanes_backward"):
        return True
    return False


def _forward_lane_count(seg: Dict[str, Any], resolved: Optional[str]) -> Optional[int]:
    """How many lanes the driver has, in the direction they are driving.

    `approach_lanes` is the raw `lanes` tag, and on a two-way way OSM counts
    BOTH directions in it — 5,728 Qatari ways are two-way `lanes=2`, which is
    one lane each way and not a two-lane carriageway. A client that centres a
    route on the way centreline and sizes it from `approach_lanes` therefore
    draws the route across the oncoming lane on every one of them. This field is
    what lets the client put the route on the carriageway actually being driven.

    Three sources, in order of how much they know:

    1. **The resolved `turn:lanes` cell count**, when that string describes one
       direction (see `_lane_string_is_directional`). A cell is a lane, and this
       is the only source that is direction-specific by construction. Measured
       against the deployed Qatar graph it agrees with `lanes` on 3,301 of the
       3,306 one-way ways that carry both, so it is also the more reliable of
       the two where they disagree.
    2. **Half of `lanes`**, on a two-way way with an even count. A two-way road
       with an even number of lanes splits evenly; that is what "two-way
       `lanes=4`" means.
    3. **`lanes` itself**, on a one-way way, where the total IS the direction.

    None otherwise, and the odd two-way count is the case worth naming: a
    two-way `lanes=3` has a lane whose direction OSM has not recorded (shared,
    tidal, or a turning lane). Splitting it 2/1 or 1.5/1.5 would be a guess
    about which side of the road the driver belongs on, so this declines, and
    the client falls back to its centreline behaviour.
    """
    if resolved and _lane_string_is_directional(seg):
        return len(str(resolved).split("|"))
    total = _lane_count(seg.get("lanes"))
    if total is None:
        return None
    if _is_oneway(seg):
        return total
    if total % 2 == 0:
        return total // 2
    return None


def _resolve_turn_lanes(seg: Dict[str, Any]) -> Tuple[Optional[str], str, str]:
    """Resolve `turn:lanes` for the direction actually driven.

    Returns ``(resolved, direction, source)``:

    * ``resolved`` — pipe-separated lanes in the DRIVER's left-to-right order,
      or None when the approach carries no applicable lane string;
    * ``direction`` — ``"forward"``/``"backward"`` relative to the OSM way, or
      ``"unknown"`` when the graph carried no direction provenance;
    * ``source`` — ``"turn:lanes"`` when ``resolved`` is set, else ``"none"``.

    OSM writes an unsuffixed `turn:lanes` in the way's digitisation order, so a
    driver going the other way sees the lanes MIRRORED and the string has to be
    reversed. A `turn:lanes:backward` value is already written from the backward
    driver's point of view and must NOT be reversed. Before this, the graph
    carried no direction at all, so both directions of a two-way way were handed
    the same forward string — the client could not tell, and silently pointed
    the wrong way on the 16 two-way `turn:lanes` ways.
    """
    way_dir = seg.get("lane_dir")
    raw = seg.get("turn_lanes")
    fwd = seg.get("turn_lanes_forward")
    bwd = seg.get("turn_lanes_backward")
    if way_dir == "backward":
        if bwd:
            resolved: Optional[str] = bwd
        elif raw:
            resolved = "|".join(reversed(str(raw).split("|")))
        else:
            resolved = None
    elif way_dir == "forward":
        # An explicit `:forward` wins over the unsuffixed form when both exist.
        resolved = fwd or raw
    else:
        resolved = raw
    direction = way_dir if way_dir in ("forward", "backward") else "unknown"
    return (resolved or None, direction, "turn:lanes" if resolved else "none")


# What a driver calls each kind of road.
#
# OSM's highway classes are a data taxonomy, not a vocabulary. Interpolating one
# into a sentence produced "the tertiary road" — observed live on this stack, in
# *"Bear left to stay on the tertiary road"* — and, worse,
# "the living street road", which is not English. That is the same defect V5
# fixed for ``primary_link`` ("primary_link road" was on the driving screen),
# one step less obvious, and this module's own docstring elsewhere already
# concedes that "Continue on the tertiary road" is "useful to nobody".
#
# The mapping keeps every distinction a driver can actually SEE from the
# windscreen — a motorway, a main road, a slip road, a service road — and
# collapses the ones they cannot. Nobody can tell tertiary from unclassified by
# looking at it, so naming the difference spends the driver's attention on a
# fact they cannot use, while "the road" is both shorter and true.
_ROAD_WORDS = {
    "motorway": "the motorway",
    # A driver says "the main road" for both of these, and the difference
    # between them is a classification decision rather than something visible.
    "trunk": "the main road",
    "primary": "the main road",
    "secondary": "the road",
    "tertiary": "the road",
    "unclassified": "the road",
    "residential": "the road",
    "living_street": "the road",
    "road": "the road",
    # These two ARE visible, and mistaking either for the carriageway is the
    # mistake that costs a driver a junction.
    "service": "the service road",
    "track": "the track",
}


def _road_label(highway: Optional[str], name: Optional[str] = None) -> str:
    """How to refer to a road in an instruction.

    Prefer the actual OSM ``name`` — "Continue on Al Rayyan Road" is what a
    driver can act on. Falling back to the highway CLASS produced instructions
    like "Continue on trunk road" and "Turn right onto footway road", which read
    as debug output and were what the service actually emitted before this.

    When there is no name, the class is translated into driver vocabulary rather
    than interpolated raw — see :data:`_ROAD_WORDS`. An unknown class becomes
    "the road", which is the honest answer: inventing a phrase from a tag nobody
    has seen before is how "the living street road" got shipped.
    """
    if name:
        return name
    if not highway:
        return "the road"
    hw = highway.lower()
    # A slip road is what a driver calls it; "the motorway link road" is what a
    # database calls it. Checked before the table so every ``*_link`` class is
    # covered without listing them all.
    if hw.endswith("_link"):
        return "the slip road"
    return _ROAD_WORDS.get(hw, "the road")


_ORDINALS = ("", "first", "second", "third", "fourth", "fifth", "sixth",
             "seventh", "eighth", "ninth", "tenth")


def display_name(seg: Dict[str, Any], lang: Optional[str]) -> Optional[str]:
    """The road name to SHOW, for a requested language.

    `lang="en"` prefers OSM's `name:en` and falls back to `name`; anything else
    (including None) keeps the local name, which is the behaviour every existing
    caller had. The fallback direction matters: 253 of Qatar's named roads have
    no `name:en`, and "Continue on شارع X" is useful to everyone, whereas
    "Continue on the tertiary road" is useful to nobody.

    One function rather than an inline `or` at each site, because the choice has
    to be the same for the instruction, the step's road field and the route
    label — a route offered "via Al Arouba" whose instructions then say
    "شارع العروبة" is worse than either language used consistently.
    """
    if lang == "en":
        return seg.get("name_en") or seg.get("name")
    return seg.get("name")


def _instruction(mtype: str, bearing: float, highway: Optional[str],
                 name: Optional[str] = None,
                 roundabout_exit: Optional[int] = None,
                 same_road: bool = False) -> str:
    """Build human-readable instruction text for a maneuver step.

    ``same_road`` means the driver is already on the road being named — a bend
    in the carriageway rather than a change of road. "Slight right ONTO Al
    Akhdari" while already on Al Akhdari reads as an instruction to leave the
    road you are on and rejoin it, and a driver looking for a side road will not
    find one. Navigators say "bear right to stay on" for exactly this case.
    """
    if mtype == "arrive":
        return "Arrive at destination"
    road = _road_label(highway, name)
    if same_road and mtype in ("slight-left", "slight-right", "turn-left", "turn-right"):
        side = "left" if mtype.endswith("left") else "right"
        verb = "Bear" if mtype.startswith("slight") else "Keep"
        return f"{verb} {side} to stay on {road}"
    if mtype == "roundabout":
        # Only claim an exit number when it was actually counted from the graph.
        # A guessed ordinal is worse than none: the driver acts on it.
        if roundabout_exit and roundabout_exit < len(_ORDINALS):
            return f"At the roundabout, take the {_ORDINALS[roundabout_exit]} exit onto {road}"
        return f"At the roundabout, exit onto {road}"
    if mtype == "depart":
        return f"Head {_cardinal(bearing)} on {road}"
    if mtype == "continue":
        return f"Continue on {road}"
    if mtype == "slight-left":
        return f"Slight left onto {road}"
    if mtype == "slight-right":
        return f"Slight right onto {road}"
    if mtype == "turn-left":
        return f"Turn left onto {road}"
    if mtype == "turn-right":
        return f"Turn right onto {road}"
    if mtype == "uturn":
        return f"Make a U-turn onto {road}"
    return f"Continue on {road}"


def _parse_kmh(props: Dict[str, Any]) -> Optional[float]:
    """Extract a maxspeed in km/h from edge props, tolerant of key/units.

    Accepts either ``maxspeed_kmh`` (int/float) or ``maxspeed`` (int, or a raw
    OSM string such as '50', '30 mph', 'none'). Returns ``None`` when unknown.
    """
    raw = props.get("maxspeed_kmh")
    if raw is None:
        raw = props.get("maxspeed")
    if raw is None:
        return None
    if isinstance(raw, (int, float)):
        return float(raw)
    if isinstance(raw, str):
        raw = raw.strip().lower()
        if raw in ("none", "signals", "walk", ""):
            return None
        try:
            return float(raw)
        except ValueError:
            pass
        parts = raw.split()
        if len(parts) == 2 and parts[1] in ("mph", "km/h"):
            try:
                n = float(parts[0])
                return n * 1.60934 if parts[1] == "mph" else n
            except ValueError:
                return None
    return None


# Public alias: the learned-speed overlay needs the same maxspeed parsing so the
# learned factor is expressed against the exact baseline the router would
# otherwise have used. Two copies of unit parsing is how ETAs silently drift.
parse_kmh = _parse_kmh


def _edge_time_weight(u: str, to: str, w: float, props: Dict[str, Any]) -> float:
    """Travel-time edge cost (seconds) for A*/Dijkstra relaxation.

    Uses the edge length ``w`` (metres) divided by the effective speed from
    :mod:`vector_routing.speeds`, which is the SINGLE place a speed is decided.

    It used to fall back to a flat 30 km/h here while the ETA path fell back to
    50 — so the router optimised against one set of speeds and then reported a
    journey time computed from another. Worse, only 10% of ways carry maxspeed,
    so the flat fallback costed 77% of trunk roads at 30 km/h and made the
    router prefer a tagged residential street at 50 over the trunk beside it.
    """
    return w / effective_ms(props)


def _distinguishing_roads(answers: List[Dict[str, Any]]) -> List[str]:
    """One road name per route: the longest road it uses that the others do not.

    "12 min / 11.0 km" beside "13 min / 11.2 km" tells a driver nothing about
    which way either route goes, and time-and-distance is all `describe()`
    produces. What a driver who knows the city actually wants is the road name:
    "via Al Corniche" against "via Salwa Road" is the whole decision.

    Longest-exclusive rather than longest: the roads two routes SHARE are, by
    definition, not what separates them, and on a peninsula the shared prefix is
    often most of the journey. A route with no exclusive named road at all gets
    an empty label, and the UI then shows time and distance alone rather than a
    misleading "via" — which happens legitimately when two options differ only
    by a slip road.

    With a SINGLE route in the list every road it uses is trivially exclusive,
    so the label degrades to "via <its longest road>". That is deliberate and
    is the useful direction to degrade in: it is still a true statement about
    the route, and a caller showing one route's label gets something rather than
    nothing. Callers that only want a label to CHOOSE between options should not
    render one when there is nothing to choose.
    """
    # metres per (route index, road name)
    per_route: List[Dict[str, float]] = []
    for a in answers:
        by_name: Dict[str, float] = {}
        for step in a.get("steps", []):
            # `road` already holds the name for the requested language (see
            # display_name), so the label follows the instructions rather than
            # disagreeing with them: a route offered "via Al Arouba" whose
            # instructions then say "شارع العروبة" is worse than either language
            # used consistently.
            name = step.get("road") or step.get("name")
            if not name:
                continue
            by_name[name] = by_name.get(name, 0.0) + float(step.get("distance_m") or 0.0)
        per_route.append(by_name)

    labels: List[str] = []
    for i, mine in enumerate(per_route):
        others = set()
        for j, theirs in enumerate(per_route):
            if j != i:
                others |= set(theirs.keys())
        exclusive = {n: m for n, m in mine.items() if n not in others}
        if not exclusive:
            labels.append("")
            continue
        best = max(exclusive.items(), key=lambda kv: kv[1])[0]
        labels.append(f"via {best}")
    return labels


class Router:
    """Snaps endpoints to the nearest graph nodes and assembles ``Route`` objects."""

    # Hard limit on how far an endpoint may be moved to reach the road network.
    # 2 km is generous -- it covers an airport apron, a large private estate and
    # a desert track -- while still refusing a point that is simply not in the
    # baked region. Beyond this, returning a route is worse than returning an
    # error, because the route looks valid.
    MAX_SNAP_M = 2000.0

    def __init__(self, graph: RoutingGraph, restrictions=None) -> None:
        self._graph = graph
        # Turn restrictions (``TurnRestrictions`` or None). Held as a predicate
        # so the hot path is one attribute read: ``None`` means the search runs
        # byte-for-byte as it did before restrictions existed, which is both the
        # correct behaviour for a graph that carries none and the rollback.
        self._restrictions = restrictions
        live = restrictions is not None and len(restrictions) > 0
        self._banned_turn = restrictions.as_predicate() if live else None
        # Derived from the SAME object as the predicate, never assembled
        # separately: `astar` enforces a ban only at nodes in this set, so a set
        # that disagrees with the predicate under-enforces silently.
        self._turn_nodes = restrictions.via_nodes if live else None
        # The baked signal catalog (V7 Stage 5). Optional and empty-capable: a
        # deployment without one routes exactly as it did before. Loaded after
        # construction via set_signals, the same way the foot graph is.
        self._signals: Optional[SignalCatalog] = None
        self._cameras: Optional[CameraCatalog] = None

    def set_cameras(self, catalog: Optional[CameraCatalog]) -> None:
        """Set the baked camera catalog, or clear it (None clears).

        The catalog is route-independent -- it describes where cameras ARE,
        not which route the driver is on. V7.3, mirroring set_signals.
        """
        self._cameras = catalog

    def set_signals(self, catalog: Optional[SignalCatalog]) -> None:
        """Set the baked signal catalog, or clear it (None clears).

        The catalog is route-independent -- it describes where signals ARE, not
        which route passes them -- so it is set once at startup. Each route's
        own signal list is projected per answer in ``_assemble``.
        """
        self._signals = catalog

    def restrictions(self):
        """The active ``TurnRestrictions``, or None."""
        return self._restrictions

    # ---- the two places a MODE is decided -------------------------------
    #
    # Everything else in this class -- snapping, the A* call, path assembly,
    # duration accumulation, the error contract -- is mode-agnostic. Only two
    # questions have a car-specific answer: what does an edge COST the search,
    # and how long does traversing it TAKE. Naming them as overridable methods
    # is what lets ``foot_graph.FootRouter`` be a walking router in a few lines
    # rather than a second copy of this file, and a second copy is how the two
    # drift apart on the next fix to either.
    #
    # They are a PAIR on purpose: cost and duration must come from one speed
    # answer. Splitting them is the exact defect `_edge_time_weight` documents
    # -- the router optimised against a 30 km/h fallback and then reported an
    # ETA built from 50.

    def _edge_weight(self, u: str, to: str, w: float, props: Dict[str, Any]) -> float:
        """Relaxation cost for one directed edge. Car: free-flow travel time (s)."""
        return _edge_time_weight(u, to, w, props)

    def _edge_duration_s(self, w: float, props: Dict[str, Any]) -> float:
        """Reported traversal time (seconds) for one directed edge.

        Keeps its own floor (``max(kmh, 1.0)``) rather than reusing
        ``effective_ms`` (``max(kmh / 3.6, 1.0)``): the two differ, this is the
        one the reported ETA has always used, and unifying them would be an ETA
        change arriving as a refactor's side effect.
        """
        speed_ms = max(float(effective_kmh(props)), 1.0) / 3.6
        return w / speed_ms

    def _snap_routable(self, lon: float, lat: float, radius_m: float = 800.0) -> str:
        """Snap to the nearest node that has at least one OUTGOING edge.

        A plain nearest-node snap can land on a dead-end (or a node you cannot
        legally depart) when the tap is slightly off the road, so we first look
        within ``radius_m`` for a node with outgoing edges.

        The fallback is BOUNDED by ``MAX_SNAP_M``. It used to fall through to the
        global nearest node with no limit at all, which meant a tap in the Gulf,
        in the desert, or simply outside the baked region silently produced a
        route between two other places -- a confident, plausible-looking answer
        to a question nobody asked. Refusing is the diagnosable behaviour: the
        caller gets the requested point, the distance, and the limit.
        """
        g = self._graph
        best = g.nearest_routable_node(lon, lat, max_m=radius_m)
        if best is not None:
            return best
        fallback = g.nearest_node(lon, lat)
        d = haversine_meters((lon, lat), g.node_coord(fallback))
        if d > self.MAX_SNAP_M:
            raise EndpointTooFarError(lon, lat, d, self.MAX_SNAP_M)
        return fallback

    def _snap_info(self, requested: Coord, node_key: str) -> Dict[str, Any]:
        """Where an endpoint was asked for, where it landed, and how far that is."""
        coord = self._graph.node_coord(node_key)
        return {
            "requested": [requested[0], requested[1]],
            "snapped": [coord[0], coord[1]],
            "distance_m": round(haversine_meters(requested, coord), 1),
            "routable": bool(self._graph.neighbors(node_key)),
        }

    def route(self, origin: Endpoint, destination: Endpoint) -> Route:
        """Route between two (lon, lat) endpoints (tuple or dict), by travel time."""
        o = _as_coord(origin)
        d = _as_coord(destination)
        src = self._snap_routable(o[0], o[1])
        tgt = self._snap_routable(d[0], d[1])
        return self.route_by_node(src, tgt)

    def route_alternatives(
        self, origin: Endpoint, destination: Endpoint, wanted: int = 2, graph=None
    ) -> List[Route]:
        """Up to `wanted` genuinely different routes, best first.

        `/route?alternatives=1` used to be accepted and ignored: the parameter
        existed and the engine always returned one path. See
        :mod:`vector_routing.alternatives` for why penalisation is used rather
        than k-shortest-paths (which returns near-identical routes).
        """
        from .alternatives import find_alternatives

        o = _as_coord(origin)
        d = _as_coord(destination)
        src = self._snap_routable(o[0], o[1])
        tgt = self._snap_routable(d[0], d[1])

        def compute(multiplier):
            if multiplier is None:
                return self.route_by_node(src, tgt, graph=graph)
            g = graph if graph is not None else self._graph
            def weighted(u, to, w, props):
                return self._edge_weight(u, to, w, props) * multiplier(u, to)
            distance_m, keys = astar(g, src, tgt, weight=weighted,
                                     banned_turn=self._banned_turn,
                                     turn_nodes=self._turn_nodes)
            return Route(
                distance_m=distance_m,
                duration_s=self._compute_duration(keys, g),
                path=[g.node_coord(k) for k in keys],
                node_keys=keys,
                waypoint_count=len(keys),
            )

        return find_alternatives(compute, wanted=wanted)

    def route_by_node(self, source_key: str, target_key: str, graph=None) -> Route:
        """Route directly between two node keys (by travel time).

        ``graph`` defaults to ``self._graph``; an alternate view (e.g. a
        congestion-overlay view) may be supplied to reroute around live traffic
        without mutating the underlying graph.
        """
        g = graph if graph is not None else self._graph
        distance_m, keys = astar(g, source_key, target_key, weight=self._edge_weight,
                                 banned_turn=self._banned_turn,
                                 turn_nodes=self._turn_nodes)
        coords = [g.node_coord(k) for k in keys]
        duration_s = self._compute_duration(keys, g)
        return Route(
            distance_m=distance_m,
            duration_s=duration_s,
            path=coords,
            node_keys=keys,
            waypoint_count=len(coords),
        )

    def _edge_props(self, src: str, dst: str) -> Dict[str, Any]:
        """Return the graph edge properties for the directed edge ``src -> dst``."""
        for to, _w, p in self._graph.neighbors(src):
            if to == dst:
                return p
        return {}

    def navigate(
        self,
        origin: Endpoint,
        destination: Endpoint,
        waypoints: Optional[List[Endpoint]] = None,
        graph=None,
        speed_for_edge=None,
        lang: Optional[str] = None,
    ) -> Dict[str, Any]:
        """Compute a multi-leg turn-by-turn route with ETA.

        ``origin``/``destination``/``waypoints`` are (lon, lat) endpoints (tuple
        or dict). Each leg (from -> via1 -> ... -> to) reuses the existing node
        snapping + A* path (``route_by_node``). The per-leg coordinate lists are
        concatenated (dropping the shared join node) into one full route, then
        turn-by-turn maneuvers and a haversine-based ETA are computed.

        ``graph`` optionally overrides the search graph (e.g. a congestion
        overlay view); defaults to ``self._graph``.

        ``speed_for_edge(node_a, node_b, props) -> Optional[kmh]`` optionally
        supplies an observed speed per edge (issue 07's learned profiles). It
        affects the reported **ETA**, which the search graph alone cannot: the
        per-segment duration below is derived from ``props`` maxspeed, so
        without this hook a learned overlay would change which road is chosen
        and still quote the OSM travel time for it. Since issue 07's falsifiable
        claim is about ETA accuracy, that gap would make the claim untestable.

        Returns a dict with ``path`` (lon/lat coords), ``distance_m``,
        ``duration_s``, ``steps`` (one maneuver per vertex, including ETA), and
        ``learned_coverage`` — the fraction of route segments that used an
        observed speed, which is what ``EtaErrorLog`` splits its distribution on.
        """
        g = graph if graph is not None else self._graph
        endpoints: List[Endpoint] = [origin] + list(waypoints or []) + [destination]
        if len(endpoints) < 2:
            raise RouteError("navigate requires an origin and a destination")

        leg_routes: List[Route] = []
        # Snapping is recorded, not just performed. A tap inside an airport
        # terminal legitimately snaps ~136 m to the nearest service road, and a
        # tap in the desert may snap several hundred metres — but a client that
        # cannot SEE the snap distance cannot tell a good route from a route
        # between two other places. Reporting it is what makes an endpoint
        # problem diagnosable instead of mysterious.
        snaps: List[Dict[str, Any]] = []
        for i in range(len(endpoints) - 1):
            o = _as_coord(endpoints[i])
            d = _as_coord(endpoints[i + 1])
            src = self._snap_routable(o[0], o[1])
            tgt = self._snap_routable(d[0], d[1])
            if i == 0:
                snaps.append(self._snap_info(o, src))
            snaps.append(self._snap_info(d, tgt))
            leg_routes.append(self.route_by_node(src, tgt, graph=g))

        # Concatenate node keys, dropping the shared join node between legs.
        full_keys: List[str] = []
        for idx, leg in enumerate(leg_routes):
            if idx == 0:
                full_keys = list(leg.node_keys)
            else:
                full_keys.extend(leg.node_keys[1:])

        return self._assemble(full_keys, snaps, g, speed_for_edge, lang)

    def _assemble(
        self, full_keys: List[str], snaps: List[Dict[str, Any]], g,
        speed_for_edge=None, lang: Optional[str] = None,
    ) -> Dict[str, Any]:
        """Turn a node path into the full navigate() answer.

        Extracted from :meth:`navigate` unchanged so that alternatives can
        produce the SAME shape. Before this, `/route?alternatives=1`
        returned geometry and a duration but no `steps`, and `/navigate`
        had no alternatives parameter at all — so a client could display a
        second route and then had no way to navigate it. The web client
        drew alternative chips against exactly this gap.

        Everything below is a pure function of the node path plus the
        endpoint snap records, which is why one implementation can serve
        both callers: the only thing alternatives change is WHICH path.
        """
        node_coord = self._graph.node_coord
        full_coords = [node_coord(k) for k in full_keys]

        # V7 Stage 5: the signals this route passes, as along-route facts, in
        # route order. Computed per answer (the path is the answer's own) and
        # only when a catalog was baked -- the wire key is absent otherwise,
        # so an old client parsing an old backend sees exactly what it always
        # saw. No timing field is emitted, ever: the extract has no timing.
        signals = (
            signals_on_path(self._signals, full_coords)
            if self._signals is not None and len(self._signals) > 0
            else []
        )

        # V7.3: the cameras this route passes, same doctrine and same
        # conditional-key rule. A camera entry is a LOCATION with provenance
        # (maxspeed/direction) -- never activity, never a fine.
        cameras = (
            cameras_on_path(self._cameras, full_coords)
            if self._cameras is not None and len(self._cameras) > 0
            else []
        )

        # Per-segment distance, duration and outgoing bearing.
        segments: List[Dict[str, Any]] = []
        learned_segments = 0
        for a, b in zip(full_keys, full_keys[1:]):
            props = self._edge_props(a, b)
            seg_distance = haversine_meters(node_coord(a), node_coord(b))
            learned_kmh = None
            if speed_for_edge is not None:
                learned_kmh = speed_for_edge(a, b, props)
            if learned_kmh is not None and float(learned_kmh) > 0:
                maxspeed = float(learned_kmh)
                learned_segments += 1
            else:
                maxspeed = effective_kmh(props)
            speed_ms = max(float(maxspeed), 1.0) / 3.6
            seg_duration = seg_distance / speed_ms
            # Free-flow speeds are optimistic by design (speeds.py); a
            # measured calibration brings the DRIVING ETA in line with real
            # drives (median observed/predicted 1.25x, n=18). Learned
            # (observed) segments are already real-world speeds and are not
            # inflated twice.
            if learned_kmh is None:
                seg_duration *= ETA_DRIVE_BIAS
            segments.append(
                {
                    "distance_m": seg_distance,
                    "duration_s": seg_duration,
                    "highway": props.get("highway"),
                    "name": props.get("name"),
                    # 48,427 of Qatar's 48,680 named roads carry `name:en`
                    # (99.5%), and nothing read it: every instruction and every
                    # route label was built from `name` alone, so a driver whose
                    # phone is in English was told "Continue on شارع الكورنيش"
                    # and offered a route "via العروبة". Qatar's road signs are
                    # bilingual; the data has been bilingual all along.
                    "name_en": props.get("name:en"),
                    "junction": props.get("junction"),
                    # Lane guidance travels with the segment so the maneuver
                    # builder can attach the lanes of the approach — the lanes
                    # that matter are the ones you are IN, not the ones you are
                    # turning onto.
                    "turn_lanes": props.get("turn:lanes"),
                    # Direction-specific lane tags, plus the traversal direction
                    # the graph recorded when it built this edge. Resolving them
                    # is `_resolve_turn_lanes`'s job (see `_lane_fields`).
                    "turn_lanes_forward": props.get("turn:lanes:forward"),
                    "turn_lanes_backward": props.get("turn:lanes:backward"),
                    "lane_dir": props.get("_way_dir"),
                    "lanes": props.get("lanes"),
                    # Whether `lanes` counts one direction or two. The graph
                    # already copies `oneway` onto every directed edge (it is
                    # what decided the edge existed at all); carrying it here is
                    # what lets `_forward_lane_count` avoid drawing a two-way
                    # road's route across the oncoming carriageway.
                    "oneway": props.get("oneway"),
                    "exit_ref": props.get("junction:ref") or props.get("destination:ref"),
                    "destination": props.get("destination"),
                    "bearing": bearing_deg(node_coord(a), node_coord(b)),
                    "learned": learned_kmh is not None,
                }
            )

        total_distance = sum(s["distance_m"] for s in segments)
        link_duration = sum(s["duration_s"] for s in segments)
        steps = self._build_steps(full_coords, segments, full_keys, g, lang)

        # Free-flow link speeds alone describe a vehicle that never slows for a
        # junction. Over a dozen maneuvers that is minutes of missing time, and
        # the error scales with TURN COUNT rather than distance, so a turn-heavy
        # urban route is under-estimated much more than a motorway run. The
        # penalties live in speeds.TURN_DELAY_S and are applied to the reported
        # duration only -- not to the routing cost, because that would change
        # which route is chosen and there is no evidence yet that Vector picks
        # zigzag routes.
        junction_s = junction_delay_s(s["type"] for s in steps)
        total_duration = link_duration + junction_s

        return {
            "path": full_coords,
            "distance_m": total_distance,
            "duration_s": total_duration,
            "link_duration_s": link_duration,
            "junction_delay_s": junction_s,
            "steps": steps,
            "learned_segments": learned_segments,
            "learned_coverage": (learned_segments / len(segments)) if segments else 0.0,
            "snap": snaps,
            "snap_max_m": max((s["distance_m"] for s in snaps), default=0.0),
            "signals": signals,
            "cameras": cameras,
        }


    def navigate_alternatives(
        self,
        origin: Endpoint,
        destination: Endpoint,
        wanted: int = 3,
        graph=None,
        speed_for_edge=None,
        lang: Optional[str] = None,
    ) -> List[Dict[str, Any]]:
        """Up to `wanted` full turn-by-turn routes, best first.

        This closes a gap that made the alternatives feature undeliverable
        rather than merely incomplete. `/route?alternatives=1` returned
        geometry, a distance and a duration for each option — and no `steps`.
        `/navigate` returned steps, and had no alternatives parameter. So a
        client could DRAW a second route and, on being asked to drive it, had
        nothing to drive: re-requesting `/navigate` for the same endpoints
        returns the PRIMARY route, because there is no way to say "the one you
        called route_id 1". Choosing an alternative would have silently
        navigated the fastest one.

        Each returned dict is exactly the shape :meth:`navigate` returns, plus
        ``route_id``, ``primary``, ``duration_delta_s`` and ``label``.

        Waypoints are deliberately not supported here. Alternatives are
        produced by penalising the edges of the previous answer, and doing that
        per leg of a multi-leg route multiplies the searches by the number of
        legs for an option nobody has asked for.
        """
        from .alternatives import describe

        g = graph if graph is not None else self._graph
        o = _as_coord(origin)
        d = _as_coord(destination)
        src = self._snap_routable(o[0], o[1])
        tgt = self._snap_routable(d[0], d[1])
        # The endpoints are the same for every alternative, so the snap records
        # are computed once rather than per route.
        snaps = [self._snap_info(o, src), self._snap_info(d, tgt)]

        routes = self.route_alternatives(origin, destination, wanted=wanted, graph=graph)
        summaries = describe(routes)
        out: List[Dict[str, Any]] = []
        for r, summary in zip(routes, summaries):
            answer = self._assemble(list(r.node_keys), snaps, g, speed_for_edge, lang)
            answer["route_id"] = summary["route_id"]
            answer["primary"] = summary["primary"]
            answer["duration_delta_s"] = summary["duration_delta_s"]
            out.append(answer)

        for answer, label in zip(out, _distinguishing_roads(out)):
            answer["label"] = label
        return out

    def _build_steps(
        self, coords: List[Coord], segments: List[Dict[str, Any]],
        node_keys: Optional[List[str]] = None, graph=None,
        lang: Optional[str] = None,
    ) -> List[Dict[str, Any]]:
        """Build turn-by-turn maneuvers by MERGING the per-vertex geometry.

        This used to emit one step per vertex, which is not turn-by-turn — it is
        a dump of the geometry. A 10.4 km Doha route produced **315 steps, 305 of
        them "continue"**, 35 of them under five metres. A driver following that
        would be told to continue three hundred times; Google gives about a dozen
        maneuvers for the same trip.

        A step is emitted only where the driver must DO something:

        * departure and arrival;
        * a real turn (``_classify_turn`` returns something other than
          ``continue``);
        * a change of road identity, so "continue onto Al Rayyan Road" appears
          where one road becomes another without a turn.

        Everything between two maneuvers is absorbed into the preceding step, so
        ``distance_m`` becomes *distance until the next maneuver* — the number a
        navigator actually announces ("in 400 m, turn right"), and the number the
        client's countdown needs. That is also OSRM's own step semantics, so a
        client written against either backend behaves the same.
        """
        n = len(coords)
        if n == 0:
            return []
        if n == 1:
            return [{
                "index": 0, "type": "arrive", "instruction": "Arrive at destination",
                "road": None,
                "location": [coords[0][0], coords[0][1]], "distance_m": 0.0,
                "duration_s": 0.0, "cumulative_distance_m": 0.0,
                "cumulative_duration_s": 0.0, "bearing": 0.0,
            }]

        # A maneuver whose road is unnamed should not be announced as a road
        # change every time the OSM way id changes along the same street.
        def identity(seg: Dict[str, Any]) -> Any:
            # Deliberately the LOCAL name, whatever language is being displayed.
            # This decides "am I still on the same road?", which drives the
            # "bear right to stay on" phrasing and the maneuver merging — and it
            # must give the same answer in every language, or the same route
            # would merge differently for an English and an Arabic client.
            return seg.get("name") or seg.get("highway")

        # A roundabout is a RUN of edges, not a turn. Treating each of its
        # vertices as an ordinary bend produces a string of "slight right"
        # instructions where a driver needs one sentence: "at the roundabout,
        # take the second exit". Detect the runs first and suppress the internal
        # geometry, then announce the exit.
        in_roundabout = [
            bool(s.get("junction")) and str(s.get("junction")).lower()
            in ("roundabout", "circular")
            for s in segments
        ]

        def has_choice(i: int) -> bool:
            """Is there anywhere else to go at vertex ``i``?

            A bend the driver cannot get wrong is not a maneuver. Where the node
            has exactly one way out other than the one we arrived on, the road
            simply curves and there is no decision to announce; saying "bear
            left to stay on Al Corniche" there is chatter, and in the voice
            channel it is chatter that talks over the instruction that matters.

            Conservative by construction: when the graph is not available the
            answer is True, so the caller announces the bend exactly as before.
            """
            if graph is None or node_keys is None or not (0 < i < len(node_keys)):
                return True
            came_from = node_keys[i - 1]
            outs = [to for to, _w, _p in graph.neighbors(node_keys[i]) if to != came_from]
            return len(outs) > 1

        def exits_before(entry_i: int, exit_i: int) -> int:
            """How many exits are passed, counting the one being taken.

            An exit is a node ON the roundabout with an outgoing edge that
            LEAVES it. Without the graph we cannot count, and saying "take the
            Nth exit" with a guessed N is worse than not saying it.
            """
            if graph is None or node_keys is None:
                return 0
            count = 0
            for k in range(entry_i + 1, min(exit_i + 1, len(node_keys))):
                for _to, _w, pr in graph.neighbors(node_keys[k]):
                    j = str(pr.get("junction") or "").lower()
                    if j not in ("roundabout", "circular"):
                        count += 1
                        break
            return count

        # Distance along the route to each vertex; needed while deciding
        # maneuvers, not only while merging them.
        cum_at = [0.0] * n
        for idx in range(1, n):
            cum_at[idx] = cum_at[idx - 1] + segments[idx - 1]["distance_m"]

        # 1. Decide which vertices are maneuvers.
        maneuver_at: Dict[int, str] = {0: "depart"}
        # The vertices where the route ENTERS a roundabout. The approach bend
        # into one is geometry, not a maneuver (see 1a below).
        roundabout_entries: List[int] = []
        delta_at: Dict[int, float] = {}        # vertex -> windowed bearing change
        roundabout_exit: Dict[int, int] = {}   # vertex -> exit ordinal
        i = 1
        while i < n - 1:
            if in_roundabout[i] and not in_roundabout[i - 1]:
                # Entry. Find where we leave it.
                entry = i
                roundabout_entries.append(entry)
                j = i
                while j < n - 1 and in_roundabout[j]:
                    j += 1
                nth = exits_before(entry, j)
                maneuver_at[j] = "roundabout"
                if nth > 0:
                    roundabout_exit[j] = nth
                # Everything strictly inside the roundabout is suppressed.
                i = j + 1
                continue
            out_seg = segments[i]
            in_seg = segments[i - 1]
            in_b, out_b = _windowed_bearings(coords, segments, i)
            delta = normalize_angle(out_b - in_b)
            mtype = _classify_turn(delta)
            delta_at[i] = delta
            same_road = identity(out_seg) == identity(in_seg)
            if mtype == "uturn":
                # Always announced: a U-turn is never something a driver does by
                # simply following the road.
                maneuver_at[i] = mtype
            elif mtype != "continue" and not (same_road and not has_choice(i)):
                maneuver_at[i] = mtype
            elif not same_road:
                # Straight on, but the road changed name/class: worth saying.
                maneuver_at[i] = "continue"
            i += 1
        maneuver_at[n - 1] = "arrive"

        # 1a. The approach bend into a roundabout is not its own maneuver.
        #
        # `_windowed_bearings` measures 25 m either side of a vertex, so a vertex
        # on the short approach to a roundabout is measured partly against the
        # RING, not the road the driver is on. On live Doha routes this produced
        # a spurious "Keep left to stay on the service road" or "Make a U-turn"
        # step immediately before "At the roundabout, take the Nth exit" on 31 of
        # 77 roundabout crossings — the driver is told to turn one way at the
        # exact moment the only instruction that matters is the exit. The
        # roundabout IS one maneuver; its entry is geometry, not a decision to
        # announce.
        _TURNS = ("slight-left", "slight-right", "turn-left", "turn-right", "uturn")
        for i in [k for k, t in maneuver_at.items() if t in _TURNS]:
            for e in roundabout_entries:
                if 0 < e - i and 0.0 <= cum_at[e] - cum_at[i] <= MANEUVER_BEARING_WINDOW_M:
                    del maneuver_at[i]
                    break

        # 1b. Non-maximum suppression over the bearing window.
        #
        # A windowed delta is non-zero not only AT the corner but at every
        # vertex within the window of it, so one physical turn reports itself
        # several times: a pair of 90-degree corners drawn with 24 m spacing
        # produced "turn left ... turn left ... turn right ... turn right" for
        # two turns. Where consecutive turns fall inside one window AND bend the
        # same way, they are one corner; keep the sharpest reading of it.
        #
        # Turns bending OPPOSITE ways are not collapsed here: that is a dogleg,
        # and the windowed delta has already cancelled it to near zero, so it
        # never became two maneuvers in the first place.
        #
        # "Same way" is measured as an angle between the two readings, NOT as a
        # matching sign. A U-turn's delta sits on the +/-180 wrap, so two
        # readings of one turnaround come back as +178 and -179 — opposite signs
        # for a three-degree difference. A sign test therefore split every Doha
        # dual-carriageway U-turn slot into two consecutive "Make a U-turn"
        # instructions 23 m apart, which is not a maneuver anyone can perform.

        def _same_corner(i: int, j: int) -> bool:
            return abs(normalize_angle(delta_at.get(i, 0.0) - delta_at.get(j, 0.0))) < 90.0

        def _collapse(run):
            if len(run) < 2:
                return
            keep = max(run, key=lambda k: abs(delta_at.get(k, 0.0)))
            for k in run:
                if k != keep:
                    del maneuver_at[k]

        corner: List[int] = []
        for i in sorted(k for k in maneuver_at if maneuver_at[k] in _TURNS):
            if (corner
                    and cum_at[i] - cum_at[corner[-1]] < MANEUVER_BEARING_WINDOW_M
                    and _same_corner(i, corner[-1])):
                corner.append(i)
                continue
            _collapse(corner)
            corner = [i]
        _collapse(corner)

        # 2. Suppress maneuvers that are too close together to be separately
        #    actionable. A junction modelled as three vertices two metres apart
        #    otherwise becomes three spoken instructions. Departure and arrival
        #    are never suppressed.
        MIN_MANEUVER_SPACING_M = 15.0

        kept: List[int] = []
        for i in sorted(maneuver_at):
            if i in (0, n - 1) or not kept:
                kept.append(i)
                continue
            if cum_at[i] - cum_at[kept[-1]] < MIN_MANEUVER_SPACING_M and kept[-1] != 0:
                # Keep the sharper of the two: a slight bend should not mask the
                # real turn it sits beside.
                prev = kept[-1]
                rank = {"roundabout": 5, "uturn": 4, "turn-left": 3,
                        "turn-right": 3, "slight-left": 2, "slight-right": 2,
                        "continue": 1}
                if rank.get(maneuver_at[i], 0) > rank.get(maneuver_at[prev], 0):
                    kept[-1] = i
                continue
            kept.append(i)

        # 3. Emit one step per kept maneuver, absorbing the geometry between it
        #    and the next one.
        steps: List[Dict[str, Any]] = []
        for idx, i in enumerate(kept):
            nxt = kept[idx + 1] if idx + 1 < len(kept) else None
            if nxt is None:
                leg_distance = 0.0
                leg_duration = 0.0
            else:
                leg_distance = cum_at[nxt] - cum_at[i]
                leg_duration = sum(segments[k]["duration_s"] for k in range(i, nxt))

            seg = segments[i] if i < n - 1 else segments[n - 2]
            mtype = maneuver_at[i]
            steps.append(
                {
                    "index": len(steps),
                    "type": mtype,
                    "instruction": _instruction(
                        mtype, seg["bearing"], seg.get("highway"),
                        display_name(seg, lang),
                        roundabout_exit.get(i),
                        # The approach and the exit are the same road when their
                        # identity has not changed across the maneuver.
                        same_road=(0 < i < n - 1
                                   and identity(segments[i]) == identity(segments[i - 1])),
                    ),
                    "location": [coords[i][0], coords[i][1]],
                    # The road this leg is DRIVEN on, separate from the
                    # instruction sentence that mentions it. A client that wants
                    # to show "Al Corniche — 8.2 km" cannot recover the name by
                    # parsing "Continue on شارع الكورنيش", and
                    # `_distinguishing_roads` needs per-road distances to say
                    # which road separates two alternatives.
                    "road": display_name(seg, lang),
                    # Both raw values travel as well, so a client can relabel
                    # without another round trip and nothing has to guess which
                    # language `road` happens to hold.
                    "road_local": seg.get("name"),
                    "road_en": seg.get("name_en"),
                    "distance_m": round(leg_distance, 3),
                    "duration_s": round(leg_duration, 3),
                    "cumulative_distance_m": round(cum_at[i], 3),
                    "cumulative_duration_s": round(
                        sum(segments[k]["duration_s"] for k in range(0, i)), 3
                    ),
                    "bearing": round(seg["bearing"], 2),
                    # The APPROACH's lanes: at vertex i the driver is still on
                    # segment i-1, and that is the carriageway they must choose a
                    # lane in. Using segments[i] would describe the road they are
                    # turning onto, which is too late to act on.
                    **_lane_fields(segments, i, n),
                }
            )
        return steps

    def _compute_duration(self, keys: List[str], graph=None) -> float:
        g = graph if graph is not None else self._graph
        total = 0.0
        for a, b in zip(keys, keys[1:]):
            for to, w, p in g.neighbors(a):
                if to == b:
                    total += self._edge_duration_s(w, p)
                    break
        return total
