"""V8 junction-aware lane markings — the ``lanes`` MVT layer.

WHAT THIS IS FOR
----------------
Until V8 the app drew lane dividers by offsetting the WHOLE road line in the
style (``line-offset``). MapLibre evaluates an offset per feature with no
knowledge of any other feature, so every approach painted its dividers straight
across the junction it ran into, and across every carriageway it met there.
The style cannot fix that: a marking would have to sit above its own deck and
below every other deck, which one layer order cannot express. The fix is
geometry that *stops*: dividers generated here, offset already, trimmed back
from each junction, and written as their own layer next to ``basemap``.

It is a VISUAL layer. Nothing here is lane guidance, a turn instruction or a
routing input, and nothing reads it but the style.

WHAT IT DRAWS, AND WHY SO LITTLE
--------------------------------
A divider is a statement: "there are exactly N lanes here, and this is where
the boundary between two of them is." Only the tag supports that, so the gate
is the tag, and every way that fails it draws NOTHING rather than a guess:

  drawn      ``lanes`` an integer 2..6 (the style's MAX_MARKED_LANES)
             one-way (``oneway=yes|-1``); or two-way with 2 lanes (one each
             way is the only arrangement there is); or two-way where
             ``lanes:forward + lanes:backward == lanes`` places the centre line
  suppressed ``lanes`` absent / malformed / 1 / 7+, a roundabout, a tunnel,
             ``highway=service``, a bridge whose elevation is only implied, a
             two-way road wider than 2 lanes with no stated split, conflicting
             directional or ``turn:lanes`` data, and the WIDER side of a
             lane-count step — all of it up to POCKET_MAX_M, only the taper
             beyond that (see ``_lane_steps``)

The suppression reason for every segment is counted in ``LaneResult.stats``.

GEOMETRY, IN ORDER
------------------
1. Junction index over drivable ways, keyed by OSM NODE ID (the converter's
   ``--lane-attrs-out`` sidecar), not by coordinate.
2. Elevation. Each way has a level from an explicit ``layer``; a bridge or
   tunnel WITHOUT ``layer`` has an implied level only, which is "uncertain",
   never a guessed +1/-1. Two ways sharing a node are classified pairwise:
     same         same explicit level
     different    explicitly different levels AND the node is interior to both
                  (a crossing that happens to share a node) — or where the way
                  that ends there also meets a road of its OWN level passing
                  through, which is what it joined
     uncertain    anything else: implied levels, or different levels where one
                  way ENDS at the node with nothing of its own level to join (a
                  ramp or abutment — a real connection between levels)
   A way is trimmed at a junction node only if some OTHER incident way there is
   ``same`` or ``uncertain`` to it. The trim is TOPOLOGICAL — it applies only
   to the ways that meet at the node — so a flyover passing over a ground
   junction is never cut by it, and neither is a parallel road that happens to
   lie within some radius of a junction it does not belong to.
3. Split each way at the interior junction nodes where it is trimmed.
4. Confidence per way (tags) and per segment (lane steps).
5. Offset each divider in a conformal metric frame (Web Mercator, scaled by
   1/cos(lat) at the segment) — no fixed-latitude constant.
6. Trim the CENTRELINE back from each trimmed end by an along-road distance,
   then offset the trimmed centreline — so every divider of the carriageway
   stops on the same line across it, the way painted lines stop at a stop
   line. The distance for way W at node N is the widest half-carriageway among
   the OTHER ways W meets there, plus a margin: W's own width is irrelevant,
   its markings must stop before they reach the carriageway they are entering.
   (A disc around the node was tried first and rejected: a divider offset d
   sideways leaves a disc of radius r after sqrt(r^2 - d^2), so the outer
   dividers of a wide road stopped short of the inner ones, or — when d > r —
   ran straight through the mouth of the road they were meeting.)
   That is S1. S2 then LENGTHENS the trim where S1 is too short: at an acute
   merge, diverge or slip-road mouth the other carriageway overlaps this one
   for far longer than its half-width, so the trim becomes the distance from
   the node at which every divider has left the painted surface (+ margin) of
   every other way met there (``_surface_trim``), capped at S2_MAX_TRIM_M. It
   applies to BRANCH ends
   (``_arm_roles``): a through road's dividers stay on its own carriageway
   across a merge, and cutting them would be an artificial gap. The one
   exception is an OPPOSING carriageway — the other half of a divided road
   where the two split or join: no marking may lie on the surface of traffic
   going the other way, so a through end clears that surface too.
   It is still topological — only the ways sharing the node count — and
   never shorter than S1; at a right angle the two are the same. S2 exists because S1 was measured to fail
   named cases (the V8-LANE-PRODUCTION case report, B/D/E/G/J), which is the
   condition the brief set for it.
   The cut is exact (``shapely.ops.substring``), so no densification is needed
   and none is done — and therefore nothing needs re-simplifying afterwards.
7. Drop carriageways shorter than ``MIN_RUN_M`` after trimming.
8. Sort by (way, segment, divider, piece), deterministically.
The builder then thins each tile's copy on the tile grid with the same
``simplify_geometry`` the basemap uses, and encodes it with ``encode_tile``.

NO CARRIAGEWAY SHIFT
--------------------
The V8 prototype also inferred which side a road gained lanes on and shifted
the markings accordingly. It was measured and failed — 152 ways painted across
themselves, markings off the road — and there is no ground truth in Qatar to
validate a shift against (``placement`` on 23 drivable ways, ``width:lanes`` on
0). It is not implemented here, not even disabled: an unsafe code path left in
place is one flag away from shipping. The wider side of a lane step, which is
where a shift would have applied, is suppressed instead (near the step).

ASSUMPTIONS, STATED
-------------------
3.5 m lanes everywhere; the carriageway centred on the OSM way; Qatar drives
on the right (so on a two-way road the backward lanes are on the left of the
digitised direction).
"""

from __future__ import annotations

import json
import math
import re
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from typing import Any, Dict, Iterable, List, Optional, Sequence, Set, Tuple

LANE_WIDTH_M = 3.5
MIN_MARKED_LANES = 2
MAX_MARKED_LANES = 6          # VectorStyle.MAX_MARKED_LANES
MIN_RUN_M = 6.0               # a marking shorter than this reads as a stray dash
JUNCTION_MARGIN_M = 1.6       # beyond the other carriageway's edge, for the kerb and paint
LANES_ZOOM = 15               # the only zoom the layer is written at (style minzoom 15)
CONTINUATION_MIN_DEG = 150.0  # two arms this close to opposite are one road continuing
# The wider side of a lane-count step (see _lane_steps). Both numbers are
# measured, not tuned to a result — 04/11 in the V8-LANE-PRODUCTION evidence:
#  * <= POCKET_MAX_M the whole segment is a pocket or an auxiliary lane: the
#    added lane runs its full length and which side it is on is unknown, so
#    NOTHING is drawn. 75% of Qatar's wider-side segments are <= 200 m.
#  * longer, it is a road that is simply wider for a while (35% of the km is
#    in segments over 1 km, mostly motorway mainline before a diverge). The
#    asymmetry is local to the step, so markings are withheld for
#    STEP_TAPER_M_PER_LANE per lane of difference from the step end — the short
#    end of a design lane-change taper (~90-175 m per lane) — and drawn beyond.
POCKET_MAX_M = 300.0
# A side arm within this angle of the through line makes a node a merge or
# diverge (lanes added or dropped on one side) rather than a crossing.
MERGE_MAX_DEG = 60.0
STEP_TAPER_M_PER_LANE = 100.0
MITRE_LIMIT = 2.0
# S2 (see step 6 of the module doc). S2 exists for the MOUTH: the stretch
# where a branch's lines cross the other road at an angle. It is capped at
# S2_MAX_TRIM_M, the distance the acceptance tests allow markings to be
# withheld from a junction. Uncapped (first version, 300 m window) it also
# removed markings wherever a branch runs ALONGSIDE the road it meets with
# the two painted surfaces overlapping — up to 282 m of a 4-lane primary
# approaching a merge (w619511981), and more than 5 m beyond 50 m on 449
# ways. That overlap is two roads mapped closer than their tagged widths
# allow, the lines there are parallel rather than crossing, and it is
# reported (`branch_parallel_overlap` in the evidence), not deleted.
S2_MAX_TRIM_M = 50.0
SURFACE_WINDOW_M = S2_MAX_TRIM_M + 40.0   # the cap plus the widest half-width that matters

_R = 6378137.0
_INT = re.compile(r"^\s*-?\d+\s*$")
_RINGS = ("roundabout", "circular")


# ---------------------------------------------------------------------------
# Tags
# ---------------------------------------------------------------------------

def parse_int(v: Any) -> Optional[int]:
    if v is None:
        return None
    s = str(v)
    return int(s) if _INT.match(s) else None


def fallback_lanes(highway: Optional[str]) -> int:
    """The style's own class fallback (VectorStyle.LANES) — the width a
    road with no ``lanes`` tag is PAINTED at, which is what a junction trim
    has to clear."""
    hw = (highway or "").lower()
    if hw in ("service", "track"):
        return 1
    if hw in ("motorway", "trunk", "primary"):
        return 3
    return 2


def surface_lanes(props: Dict[str, Any]) -> int:
    n = parse_int(props.get("lanes"))
    return n if n is not None and n >= 1 else fallback_lanes(props.get("highway"))


def _flag(v: Any) -> bool:
    return v not in (None, "", "no", "0", "false")


def level_of(props: Dict[str, Any], attrs: Dict[str, Any]) -> Tuple[Optional[int], str]:
    """(level, evidence). level None means only IMPLIED (bridge/tunnel, no layer)."""
    layer = parse_int(attrs.get("layer"))
    if layer is not None:
        return layer, "layer"
    if _flag(props.get("bridge")):
        return None, "bridge_no_layer"
    if _flag(props.get("tunnel")):
        return None, "tunnel_no_layer"
    return 0, "default"


def level_relation(a: Optional[int], b: Optional[int]) -> str:
    if a is None or b is None:
        return "uncertain"
    return "same" if a == b else "different"


# ---------------------------------------------------------------------------
# Data
# ---------------------------------------------------------------------------

@dataclass(slots=True)
class Way:
    idx: int
    id: str                     # "w123"
    osm_id: int
    coords: List[Tuple[float, float]]
    nodes: List[int]
    props: Dict[str, Any]
    attrs: Dict[str, Any]
    level: Optional[int] = 0
    level_evidence: str = "default"
    half_width_m: float = 0.0
    conf: str = ""              # "draw" or "suppress"
    reason: str = ""            # suppression reason, or the drawn configuration


@dataclass(slots=True)
class Segment:
    way: int                    # Way.idx
    part: int
    start: int                  # vertex index into the way
    end: int
    start_node: int
    end_node: int
    trim_start_m: float = 0.0   # 0 = not trimmed at that end
    trim_end_m: float = 0.0
    step_taper_m: float = 0.0   # markings withheld next to a lane step (long wider sections)
    start_role: str = ""        # at a junction end: "through" or "branch" (see _arm_roles)
    end_role: str = ""
    start_partner: int = -1     # the way a THROUGH end continues into (way idx)
    end_partner: int = -1
    # opposing carriageways at each end; None (not an empty set per segment:
    # 256k of those are ~110 MB) when there are none
    start_opposing: Optional[Set[int]] = None
    end_opposing: Optional[Set[int]] = None
    conf: str = ""
    reason: str = ""


@dataclass(slots=True)
class Run:
    way_osm_id: int
    part: int
    pos: int                    # divider position, lanes from the left kerb (1..n-1)
    piece: int
    cls: str                    # lane | solid | centre
    lanes: int
    level: int
    bridge: bool
    coords: List[Tuple[float, float]]   # lon, lat
    length_m: float


@dataclass
class LaneResult:
    ways: List[Way]
    segments: List[Segment]
    runs: List[Run]
    node_class: Dict[int, str]          # junction node -> same_surface|different_level|uncertain
    node_coord: Dict[int, Tuple[float, float]]
    stats: Dict[str, Any] = field(default_factory=dict)


# ---------------------------------------------------------------------------
# Projection: Web Mercator metres, scaled locally
# ---------------------------------------------------------------------------

def _merc(lon: float, lat: float) -> Tuple[float, float]:
    return (_R * math.radians(lon),
            _R * math.log(math.tan(math.pi / 4 + math.radians(lat) / 2)))


def _unmerc(x: float, y: float) -> Tuple[float, float]:
    return (math.degrees(x / _R),
            math.degrees(2 * math.atan(math.exp(y / _R)) - math.pi / 2))


def _scale(lat: float) -> float:
    """Mercator units per ground metre at ``lat``. Mercator is conformal, so
    an offset or a disc radius scaled by this is correct in every direction."""
    return 1.0 / math.cos(math.radians(lat))


# ---------------------------------------------------------------------------
# Confidence
# ---------------------------------------------------------------------------

def way_confidence(props: Dict[str, Any], attrs: Dict[str, Any],
                   level_evidence: str) -> Tuple[str, str, Optional[Dict[str, Any]]]:
    """("draw", config, plan) or ("suppress", reason, None) from the tags alone.

    ``plan`` = {"n": lanes, "centre": position-or-None, "cls": "lane"|"solid"}.
    """
    raw = props.get("lanes")
    if raw is None:
        return "suppress", "no_lanes", None
    n = parse_int(raw)
    if n is None or n < 1:
        return "suppress", "malformed_lanes", None
    if n == 1:
        return "suppress", "one_lane", None
    if n > MAX_MARKED_LANES:
        return "suppress", "lanes_over_max", None
    if props.get("junction") in _RINGS:
        return "suppress", "roundabout", None
    if (props.get("highway") or "").lower() == "service":
        return "suppress", "service", None
    if _flag(props.get("tunnel")):
        return "suppress", "tunnel", None            # the style draws no markings in tunnels
    if level_evidence == "bridge_no_layer":
        return "suppress", "bridge_level_unknown", None

    oneway = props.get("oneway") in ("yes", "-1")
    turn = props.get("turn:lanes")
    fwd = parse_int(attrs.get("lanes:forward"))
    bwd = parse_int(attrs.get("lanes:backward"))
    both = parse_int(attrs.get("lanes:both_ways")) or 0
    dir_tags = any(attrs.get(k) is not None for k in
                   ("lanes:forward", "lanes:backward", "lanes:both_ways"))

    if oneway:
        if turn is not None:
            if len(str(turn).split("|")) != n:
                return "suppress", "turn_lanes_conflict", None
            return "draw", "oneway_assigned", {"n": n, "centre": None, "cls": "solid"}
        return "draw", "oneway", {"n": n, "centre": None, "cls": "lane"}

    # two-way
    if turn is not None:
        # Plain `turn:lanes` on a two-way road does not say which direction
        # it describes; `turn:lanes:forward/backward` would.
        return "suppress", "turn_lanes_two_way", None
    if dir_tags:
        if fwd is None or bwd is None or both or fwd < 1 or bwd < 1 or fwd + bwd != n:
            return "suppress", "direction_split_conflict", None
        return "draw", "two_way_split", {"n": n, "centre": bwd, "cls": "lane"}
    if n == 2:
        return "draw", "two_way_2", {"n": 2, "centre": 1, "cls": "lane"}
    return "suppress", "two_way_split_unknown", None


# ---------------------------------------------------------------------------
# The bake
# ---------------------------------------------------------------------------

def load_lane_attrs(path: str) -> Dict[str, Dict[str, Any]]:
    with open(path, encoding="utf-8") as fh:
        doc = json.load(fh)
    if doc.get("schema") != "vector-lane-attrs-1":
        raise ValueError(f"{path}: not a vector-lane-attrs-1 sidecar")
    return doc["ways"]


def _drivable(f: Any) -> bool:
    p = getattr(f, "properties", None) or {}
    return (p.get("kind") == "road" and p.get("car") is True
            and getattr(f, "geometry_type", None) == "LineString")


def build_lanes(features: Iterable[Any], lane_attrs: Dict[str, Dict[str, Any]]) -> LaneResult:
    from shapely.geometry import LineString
    from shapely.ops import substring

    stats: Dict[str, Any] = {}
    reasons: Counter = Counter()

    # --- ways, in OSM id order (never file or set order) ------------------
    cand = [f for f in features if _drivable(f)]
    stats["input_drivable_ways"] = len(cand)
    ways: List[Way] = []
    misaligned = 0
    for f in sorted(cand, key=lambda f: int(str(f.id).lstrip("w"))):
        a = lane_attrs.get(str(f.id))
        # The feature's own coordinate lists and properties, by reference: the
        # lane stage reads them and never writes, and copying 126k ways' worth
        # was most of the stage's +0.35 GiB peak RSS (measured, 13-memory-probe).
        coords = f.coordinates
        if a is None or len(a.get("nodes") or ()) != len(coords):
            misaligned += 1
            continue
        w = Way(idx=len(ways), id=str(f.id), osm_id=int(str(f.id).lstrip("w")),
                coords=coords, nodes=a["nodes"], props=f.properties, attrs=a)
        w.level, w.level_evidence = level_of(w.props, w.attrs)
        w.half_width_m = surface_lanes(w.props) * LANE_WIDTH_M / 2.0
        ways.append(w)
    stats["ways_without_aligned_sidecar"] = misaligned
    stats["ways"] = len(ways)
    stats["level_evidence"] = dict(sorted(Counter(w.level_evidence for w in ways).items()))

    # --- node index: arms and incidences ----------------------------------
    # Two passes, so the per-node incidence lists — the largest structure here —
    # exist only for the nodes that can be junctions (>= 3 arms), not for every
    # one of Qatar's ~1.1M road vertices.
    arms: Counter = Counter()
    for w in ways:
        last = len(w.nodes) - 1
        for i, n in enumerate(w.nodes):
            arms[n] += 1 if i in (0, last) else 2
    inc: Dict[int, List[Tuple[int, int]]] = defaultdict(list)   # node -> [(way idx, vertex idx)]
    node_coord: Dict[int, Any] = {}
    for w in ways:
        for i, n in enumerate(w.nodes):
            if arms[n] >= 3:
                inc[n].append((w.idx, i))
                node_coord.setdefault(n, w.coords[i])
    del arms

    def interior(w: Way, i: int) -> bool:
        return 0 < i < len(w.nodes) - 1

    junctions = sorted(n for n, occ in inc.items() if len({wi for wi, _ in occ}) >= 2)
    stats["junction_nodes"] = len(junctions)

    # pairwise relation at a node, topology-aware (see module doc, step 2)
    def joins_own_level(n: int, o: Tuple[int, int]) -> bool:
        """Does the way at ``o`` meet a way of its own level passing through n?"""
        lv = ways[o[0]].level
        return any(p[0] != o[0] and ways[p[0]].level == lv and lv is not None
                   and interior(ways[p[0]], p[1]) for p in inc[n])

    def pair_rel(n: int, a: Tuple[int, int], b: Tuple[int, int]) -> str:
        wa, wb = ways[a[0]], ways[b[0]]
        r = level_relation(wa.level, wb.level)
        if r != "different":
            return r
        for (w, i), o in ((a, a), (b, b)):
            if not interior(ways[w], i) and not joins_own_level(n, o):
                return "uncertain"      # a level transition at a real connection
        return "different"

    node_class: Dict[int, str] = {}
    # (way idx, node) -> trim radius in metres, for every place a way is trimmed
    trim_at: Dict[Tuple[int, int], float] = {}
    # (way idx, node) -> the OTHER ways whose surfaces it must clear there (S2)
    trim_with: Dict[Tuple[int, int], Set[int]] = defaultdict(set)
    trim_decisions: Counter = Counter()
    for n in junctions:
        occ = inc[n]
        rels = set()
        for i in range(len(occ)):
            for j in range(i + 1, len(occ)):
                if occ[i][0] != occ[j][0]:
                    rels.add(pair_rel(n, occ[i], occ[j]))
        # A node where a same-level junction ALSO has a different-level way
        # passing through it is a same-surface junction for the ways on that
        # surface; the pass-through way is left alone by the trim rule below.
        node_class[n] = ("uncertain" if "uncertain" in rels else
                         "same_surface" if "same" in rels else "different_level")
        if node_class[n] == "same_surface" and "different" in rels:
            trim_decisions["same_surface_nodes_with_level_pass_through"] += 1
        for o in occ:
            r = 0.0
            for p in occ:
                if p[0] == o[0]:
                    continue
                if pair_rel(n, o, p) in ("same", "uncertain"):
                    r = max(r, ways[p[0]].half_width_m + JUNCTION_MARGIN_M)
                    trim_with[(o[0], n)].add(p[0])
            key = (o[0], n)
            if r > 0.0:
                trim_at[key] = max(trim_at.get(key, 0.0), r)
                trim_decisions["trimmed"] += 1
            else:
                trim_decisions["passes_through_different_level"] += 1
    stats["junction_node_classes"] = dict(sorted(Counter(node_class.values()).items()))
    stats["way_node_trim_decisions"] = dict(trim_decisions)

    # --- way confidence ----------------------------------------------------
    plans: Dict[int, Dict[str, Any]] = {}
    for w in ways:
        w.conf, w.reason, plan = way_confidence(w.props, w.attrs, w.level_evidence)
        if plan is not None:
            plans[w.idx] = plan

    # --- split at interior trimmed nodes -----------------------------------
    segments: List[Segment] = []
    for w in ways:
        cuts = [0] + [i for i in range(1, len(w.nodes) - 1)
                      if (w.idx, w.nodes[i]) in trim_at] + [len(w.nodes) - 1]
        for part, (a, b) in enumerate(zip(cuts, cuts[1:])):
            if b <= a:
                continue
            s = Segment(way=w.idx, part=part, start=a, end=b,
                        start_node=w.nodes[a], end_node=w.nodes[b],
                        trim_start_m=trim_at.get((w.idx, w.nodes[a]), 0.0),
                        trim_end_m=trim_at.get((w.idx, w.nodes[b]), 0.0))
            segments.append(s)
    stats["segments_after_split"] = len(segments)

    # --- through / branch at every junction end (S2 applies to branches) ---
    role_counts = _arm_roles(ways, segments, set(junctions))
    stats["junction_arm_roles"] = role_counts
    # A THROUGH end's continuation is not a road it meets: it is itself,
    # carrying on. Its width must not size the gap (a mainline split into two
    # ways at a diverge would otherwise open a gap of its OWN width there).
    # Re-derive S1 for through ends without the partner; branches keep all.
    for s in segments:
        for end in ("start", "end"):
            role = s.start_role if end == "start" else s.end_role
            partner = s.start_partner if end == "start" else s.end_partner
            node = s.start_node if end == "start" else s.end_node
            if role != "through" or partner < 0 or partner == s.way:
                continue
            others = [o for o in trim_with.get((s.way, node), ()) if o != partner]
            r = max((ways[o].half_width_m + JUNCTION_MARGIN_M for o in others), default=0.0)
            if end == "start":
                s.trim_start_m = r
            else:
                s.trim_end_m = r

    # --- lane steps: the wider side is not resolvable ----------------------
    stepped = _lane_steps(ways, segments, inc, plans, pair_rel)
    stats["segments_wider_side_of_lane_step"] = len(stepped)

    # --- generate, trim, drop ---------------------------------------------
    deck_cache: Dict[Tuple[int, int, int], Any] = {}
    runs: List[Run] = []
    counts: Counter = Counter()
    for si, s in enumerate(segments):
        w = ways[s.way]
        if w.conf != "draw":
            s.conf, s.reason = "suppress", w.reason
            reasons[w.reason] += 1
            continue
        pts_ll = w.coords[s.start:s.end + 1]
        lat_mid = sum(p[1] for p in pts_ll) / len(pts_ll)
        k = _scale(lat_mid)
        merc = [_merc(*p) for p in pts_ll]
        # identical consecutive vertices are a zero-length edge, not geometry
        merc = [p for i, p in enumerate(merc) if i == 0 or p != merc[i - 1]]
        if len(merc) < 2:
            s.conf, s.reason = "suppress", "degenerate"
            reasons["degenerate"] += 1
            continue
        line = LineString(merc)
        plan = plans[s.way]
        n = plan["n"]
        offsets = [(n / 2.0 - pos) * LANE_WIDTH_M for pos in range(1, n)]
        for end in ("start", "end"):
            node = s.start_node if end == "start" else s.end_node
            s1 = s.trim_start_m if end == "start" else s.trim_end_m
            role = s.start_role if end == "start" else s.end_role
            if s1 <= 0:
                continue
            others = sorted(trim_with.get((s.way, node), ()))
            if role != "branch":
                # a THROUGH end clears only an opposing carriageway's surface
                opposing = (s.start_opposing if end == "start" else s.end_opposing) or ()
                others = [o for o in others if o in opposing]
                if not others:
                    continue
            t = _surface_trim(line, end == "start", offsets, k, s1,
                              [_deck_near(ways[o], _merc(*node_coord[node]), deck_cache)
                               for o in others])
            if t > s1:
                counts["ends_where_S2_exceeds_S1"] += 1
                counts["S2_extra_trim_m"] += round(t - s1, 1)
                if end == "start":
                    s.trim_start_m = t
                else:
                    s.trim_end_m = t
        if si in stepped:
            if line.length / k <= POCKET_MAX_M:
                s.conf, s.reason = "suppress", "lane_step_wider_side"
                reasons["lane_step_wider_side"] += 1
                continue
            # a long wider section: withhold only the taper at each step end
            for end, delta in stepped[si].items():
                zone = STEP_TAPER_M_PER_LANE * delta
                s.step_taper_m += zone
                if end == "start":
                    s.trim_start_m = max(s.trim_start_m, zone)
                else:
                    s.trim_end_m = max(s.trim_end_m, zone)
            counts["lane_step_long_segments_taper_withheld"] += 1
        s.conf, s.reason = "draw", w.reason
        reasons["drawn:" + w.reason] += 1
        counts["candidate_dividers"] += n - 1
        if s.trim_start_m > 0 or s.trim_end_m > 0:
            a0 = s.trim_start_m * k
            a1 = line.length - s.trim_end_m * k
            if (a1 - a0) / k < MIN_RUN_M:
                counts["segments_trimmed_away"] += 1
                counts["dropped_short_pieces"] += n - 1
                continue
            line = substring(line, a0, a1)
            counts["trimmed_dividers"] += n - 1
        elif line.length / k < MIN_RUN_M:
            counts["dropped_short_pieces"] += n - 1
            continue
        for pos in range(1, n):
            left_m = (n / 2.0 - pos) * LANE_WIDTH_M
            div = line if left_m == 0 else line.offset_curve(
                left_m * k, join_style="mitre", mitre_limit=MITRE_LIMIT)
            if div.is_empty:
                counts["offset_empty"] += 1
                continue
            parts = [div] if div.geom_type == "LineString" else list(getattr(div, "geoms", []))
            parts = [g for g in parts if g.geom_type == "LineString" and not g.is_empty]
            cls = "centre" if plan["centre"] == pos else plan["cls"]
            piece = 0
            for g in parts:
                length_m = g.length / k
                if length_m < MIN_RUN_M:
                    counts["dropped_short_pieces"] += 1
                    continue
                ll = [_unmerc(x, y) for x, y in g.coords]
                runs.append(Run(way_osm_id=w.osm_id, part=s.part, pos=pos, piece=piece,
                                cls=cls, lanes=n, level=w.level or 0,
                                bridge=_flag(w.props.get("bridge")),
                                coords=[(round(a, 7), round(b, 7)) for a, b in ll],
                                length_m=length_m))
                piece += 1
    runs.sort(key=lambda r: (r.way_osm_id, r.part, r.pos, r.piece))
    stats["segment_outcomes"] = dict(sorted(reasons.items()))
    stats.update({k: v for k, v in sorted(counts.items())})
    stats["final_runs"] = len(runs)
    stats["final_length_km"] = round(sum(r.length_m for r in runs) / 1000.0, 3)
    return LaneResult(ways=ways, segments=segments, runs=runs,
                      node_class=node_class, node_coord=node_coord, stats=stats)


def _out_dir(ways, s: "Segment", end: str) -> Optional[Tuple[float, float]]:
    """Unit direction a segment leaves its ``end`` node in (merc), judged at
    the first vertex >= 5 m away so a tiny kink at the node does not decide it."""
    w = ways[s.way]
    pts = w.coords[s.start:s.end + 1]
    if end == "end":
        pts = pts[::-1]
    a = _merc(*pts[0])
    k = _scale(pts[0][1])
    for p in pts[1:]:
        b = _merc(*p)
        dx, dy = b[0] - a[0], b[1] - a[1]
        L = math.hypot(dx, dy)
        if L / k >= 5.0 or p is pts[-1]:
            return (dx / L, dy / L) if L else None
    return None


def _flow_out(ways, s: "Segment", end: str) -> Optional[bool]:
    """For a one-way segment, does traffic LEAVE the node at this end? None
    for a two-way road."""
    ow = ways[s.way].props.get("oneway")
    if ow not in ("yes", "-1"):
        return None
    out = end == "start"
    return (not out) if ow == "-1" else out


def _arm_roles(ways, segments, junctions: Set[int]) -> Dict[str, int]:
    """Mark every segment end at a junction "through" or "branch".

    Two ends are THROUGH when each is the other's best straight continuation
    (the most nearly opposite arm, and at least CONTINUATION_MIN_DEG apart): a
    road carrying on across the node. Every other end is a BRANCH: a side road,
    a slip or ramp joining or leaving, an approach that ends there.

    Why it matters: at a merge or diverge the two carriageways' surfaces
    overlap. A THROUGH road's dividers there stay on its own carriageway — they
    merely cross a surface the two share — and cutting them would leave an
    artificial gap in a correct marking. A BRANCH's dividers run at an angle
    across the through road's lanes, which is the defect. So only branches get
    the S2 surface trim.
    """
    cos_min = math.cos(math.radians(CONTINUATION_MIN_DEG))
    arms: Dict[int, List[Tuple[int, str, Tuple[float, float]]]] = defaultdict(list)
    for si, s in enumerate(segments):
        for end, node in (("start", s.start_node), ("end", s.end_node)):
            if node in junctions:
                u = _out_dir(ways, s, end)
                if u is not None:
                    arms[node].append((si, end, u))
    counts: Counter = Counter()
    cos90 = 0.0
    for node in sorted(arms):
        lst = arms[node]
        # OPPOSING carriageways: two one-way arms on the same side of the node
        # (under 90 degrees apart) with traffic going opposite ways — the two
        # halves of a divided road where it splits or joins. A marking on an
        # opposing carriageway's surface is the median-crossing defect, so
        # these are recorded for S2 whatever the through/branch role.
        for i, (si, end, u) in enumerate(lst):
            fi = _flow_out(ways, segments[si], end)
            if fi is None:
                continue
            opp = set()
            for j, (sj, fend, v) in enumerate(lst):
                if j == i or segments[sj].way == segments[si].way:
                    continue
                fj = _flow_out(ways, segments[sj], fend)
                if fj is not None and fj != fi and u[0] * v[0] + u[1] * v[1] > cos90:
                    opp.add(segments[sj].way)
            if opp:
                if end == "start":
                    segments[si].start_opposing = opp
                else:
                    segments[si].end_opposing = opp
                counts["ends_beside_an_opposing_carriageway"] += 1
        best: Dict[int, Optional[int]] = {}
        for i, (_, _, u) in enumerate(lst):
            cand = [(u[0] * v[0] + u[1] * v[1], j) for j, (_, _, v) in enumerate(lst) if j != i]
            cand = [c for c in cand if c[0] <= cos_min]
            best[i] = min(cand)[1] if cand else None
        for i, (si, end, _) in enumerate(lst):
            b = best[i]
            role = "through" if b is not None and best.get(b) == i else "branch"
            partner = segments[lst[b][0]].way if role == "through" else -1
            if end == "start":
                segments[si].start_role, segments[si].start_partner = role, partner
            else:
                segments[si].end_role, segments[si].end_partner = role, partner
            counts[role] += 1
    return dict(sorted(counts.items()))


def _deck_near(w: "Way", centre: Tuple[float, float], cache: Dict) -> Any:
    """The painted surface of way ``w`` within SURFACE_WINDOW_M of ``centre``
    (merc), grown by the junction margin — i.e. the area another way's
    markings must stay out of. Flat-capped, as the style's butt-capped deck is.
    """
    from shapely.geometry import LineString, box
    import shapely
    key = (w.idx, int(centre[0]), int(centre[1]))
    if key in cache:
        return cache[key]
    lat = sum(c[1] for c in w.coords) / len(w.coords)
    k = _scale(lat)
    m = [_merc(*c) for c in w.coords]
    m = [p for i, p in enumerate(m) if i == 0 or p != m[i - 1]]
    win = SURFACE_WINDOW_M * k
    g = LineString(m) if len(m) >= 2 else None
    if g is not None:
        g = shapely.clip_by_rect(g, centre[0] - win, centre[1] - win, centre[0] + win, centre[1] + win)
    d = (g.buffer((w.half_width_m + JUNCTION_MARGIN_M) * k, cap_style="flat")
         if g is not None and not g.is_empty else None)
    cache[key] = d
    return d


def _surface_trim(line, at_start: bool, offsets: Sequence[float], k: float,
                  s1_m: float, decks: Sequence[Any]) -> float:
    """S2: metres from the node end of ``line`` until EVERY divider has left
    EVERY other deck — counting only overlap that starts at the node end (within
    the S1 distance of it), so a road the way merely passes later is not a
    reason to trim here. Never less than S1: at a right angle the two agree.
    Never more than max(S1, S2_MAX_TRIM_M): a branch running ALONGSIDE the
    road it meets is not a mouth, and the acceptance tests forbid removing a
    marking more than 50 m from a junction.
    """
    from shapely.geometry import Point
    best = s1_m
    touch = s1_m * k
    for d in offsets:
        div = line if d == 0 else line.offset_curve(d * k, join_style="mitre",
                                                   mitre_limit=MITRE_LIMIT)
        if div.is_empty or div.geom_type != "LineString":
            continue
        Ld = div.length
        for D in decks:
            if D is None:
                continue
            inter = div.intersection(D)
            if inter.is_empty:
                continue
            parts = [inter] if inter.geom_type == "LineString" else \
                [g for g in getattr(inter, "geoms", []) if g.geom_type == "LineString"]
            for g in parts:
                a = div.project(Point(g.coords[0]))
                b = div.project(Point(g.coords[-1]))
                lo, hi = min(a, b), max(a, b)
                if at_start and lo <= touch:
                    best = max(best, hi / k)
                elif not at_start and hi >= Ld - touch:
                    best = max(best, (Ld - lo) / k)
    return min(best, max(s1_m, S2_MAX_TRIM_M))


def _lane_steps(ways, segments, inc, plans, pair_rel) -> Dict[int, Dict[str, int]]:
    """Segments that are the WIDER side of a lane-count step, by how much.

    A lane step is a place where lanes appear or disappear with nothing to
    account for them. It is decided by LANE CONSERVATION at a node, for each
    segment end E carrying a drawable lane count n:

    * every OTHER segment end at the node must carry straight on from E (the
      arms point within ``180 - CONTINUATION_MIN_DEG`` degrees of opposite):
      a plain way split, or a pure merge/diverge. If any arm leaves at a
      turning angle the node is a JUNCTION — lanes differ there because
      traffic turns, not because the road widened — and E is an approach,
      trimmed like any other;
    * those continuing arms must all be on E's level and all state a lane
      count (a missing count means the balance is unknown: not flagged);
    * S = the SUM of their lanes. S >= n: every lane is accounted for (a
      motorway's 4 lanes diverging into a 3-lane mainline and a 1-lane ramp).
      S < n: n - S lanes appear from nowhere, and which side they are on is
      exactly what the data does not say (``placement`` on 23 drivable ways).

    The first version compared E with ANY continuing arm, so the 1-lane exit
    ramp at every motorway diverge made the 4-lane approach "the wider side of
    a 4 -> 1 step" and withheld its markings before every exit; this rule was
    written after measuring that.

    The same applies at a MERGE or DIVERGE junction (see the second branch
    below), measured against the through continuation's lanes.

    Returns {segment index: {"start"|"end": n - S}}; ``build_lanes`` turns that
    into "suppress" or "withhold near the end" by segment length
    (POCKET_MAX_M, STEP_TAPER_M_PER_LANE).
    """
    ends: Dict[int, List[Tuple[int, str]]] = defaultdict(list)
    for si, s in enumerate(segments):
        ends[s.start_node].append((si, "start"))
        ends[s.end_node].append((si, "end"))

    def out_dir(si: int, end: str) -> Optional[Tuple[float, float]]:
        s = segments[si]
        w = ways[s.way]
        pts = w.coords[s.start:s.end + 1]
        if end == "end":
            pts = pts[::-1]
        a = _merc(*pts[0])
        # the first vertex at least 5 m away, so a tiny kink at the node
        # does not decide the direction
        k = _scale(pts[0][1])
        for p in pts[1:]:
            b = _merc(*p)
            dx, dy = b[0] - a[0], b[1] - a[1]
            L = math.hypot(dx, dy)
            if L / k >= 5.0 or p is pts[-1]:
                return (dx / L, dy / L) if L else None
        return None

    cos_min = math.cos(math.radians(CONTINUATION_MIN_DEG))
    cos_merge = math.cos(math.radians(MERGE_MAX_DEG))
    stepped: Dict[int, Dict[str, int]] = {}
    for node in sorted(ends):
        lst = ends[node]
        if len(lst) < 2:
            continue
        for si, e in lst:
            s = segments[si]
            if s.way not in plans:
                continue
            n = plans[s.way]["n"]
            u = out_dir(si, e)
            if u is None:
                continue
            vi = s.start if e == "start" else s.end
            total, balanced = 0, True
            for sj, f in lst:
                if sj == si:
                    continue
                t = segments[sj]
                v = out_dir(sj, f)
                wj = t.start if f == "start" else t.end
                n_t = parse_int(ways[t.way].props.get("lanes"))
                if (v is None or u[0] * v[0] + u[1] * v[1] > cos_min      # a turning arm
                        or (t.way != s.way and pair_rel(node, (s.way, vi), (t.way, wj)) != "same")
                        or n_t is None or n_t < 1):
                    balanced = False
                    break
                total += n_t
            if balanced and total < n:
                d = stepped.setdefault(si, {})
                d[e] = max(d.get(e, 0), n - total)
                continue
            # A MERGE or DIVERGE: E is THROUGH, its continuation has fewer
            # lanes, and every other arm leaves at an acute angle to the
            # through line — lanes added or dropped on ONE side (case D: a
            # 4-lane primary becoming 6 as a 2-lane road merges from the
            # right). The ramp explains the lanes, not where the wider
            # carriageway's centre is: the OSM line usually just continues
            # the narrower road's, and symmetric dividers about it are then
            # off by up to half the difference. At a CROSSING (a side arm at
            # a wide angle) lanes differ because traffic turns, and E is an
            # ordinary approach — case B.
            role = s.start_role if e == "start" else s.end_role
            partner = s.start_partner if e == "start" else s.end_partner
            if role != "through" or partner < 0 or partner == s.way:
                continue
            n_p = parse_int(ways[partner].props.get("lanes"))
            if n_p is None or n_p < 1 or n_p >= n:
                continue
            acute = True
            for sj, f in lst:
                t = segments[sj]
                if sj == si or t.way == partner:
                    continue
                v = out_dir(sj, f)
                # within MERGE_MAX_DEG of the through line, either way along it
                if v is None or abs(u[0] * v[0] + u[1] * v[1]) < cos_merge:
                    acute = False
                    break
            if acute:
                d = stepped.setdefault(si, {})
                d[e] = max(d.get(e, 0), n - n_p)
    return stepped


# ---------------------------------------------------------------------------
# Tiles
# ---------------------------------------------------------------------------

def run_properties(r: Run) -> Dict[str, Any]:
    """What a lane feature carries. Small on purpose: every distinct value is
    a string in the tile's value table.

    cls   lane | solid | centre — the only styling decision in the data
    n     lanes on the carriageway, i  divider position from the left kerb
    way   the OSM way id, so any marking can be traced to its source
    lvl   the explicit OSM layer, only when non-zero
    brg   1 on a bridge — the style's own deck grouping (`has bridge`), which
          is what decides whether a marking draws above the bridge decks or
          below them; `lvl` is not the same test (17 drivable ways carry a
          `layer` without being bridges)
    """
    p: Dict[str, Any] = {"cls": r.cls, "n": r.lanes, "i": r.pos, "way": r.way_osm_id}
    if r.level:
        p["lvl"] = r.level
    if r.bridge:
        p["brg"] = 1
    return p


def bucket_runs(runs: Sequence[Run], z: int) -> Dict[Tuple[int, int], List[Run]]:
    """Every run goes WHOLE into every tile its bbox touches — the basemap's
    own convention. Clipping would restart the dash pattern at each tile edge
    (MapLibre measures dash distance from the start of the feature in that
    tile), so a dashed divider would visibly stutter at every tile boundary.
    """
    from .tiles import lonlat_to_tile
    out: Dict[Tuple[int, int], List[Run]] = defaultdict(list)
    for r in runs:
        xs = [c[0] for c in r.coords]
        ys = [c[1] for c in r.coords]
        x0, y0 = lonlat_to_tile(z, min(xs), max(ys))
        x1, y1 = lonlat_to_tile(z, max(xs), min(ys))
        for x in range(min(x0, x1), max(x0, x1) + 1):
            for y in range(min(y0, y1), max(y0, y1) + 1):
                out[(x, y)].append(r)
    return out
