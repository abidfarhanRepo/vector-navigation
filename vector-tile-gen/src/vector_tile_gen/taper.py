"""Carriageway width tapers at lane-count steps — the fix for jagged road edges.

WHAT WAS WRONG
--------------
The app paints a drivable way ``lanes x 3.5 m`` wide (``VectorStyle.LANES``,
mirrored here by ``lanes.surface_lanes``). Every OSM way is its own line with
its own width, and where one way continues into the next with a different lane
count the painted surface jumps from one width to the other in a single
square step. On the S24 Ultra at navigation zoom that step reads as a notch in
the kerb: 2->1, 3->4, 5->2 and 4->7 all showed it (V8 native acceptance §9.4).

WHAT THIS DOES
--------------
At every node where exactly two drivable ways meet end to end (a continuation,
not a junction) and their painted widths differ, the LAST stretch of the wider
way before the node is cut into short pieces. Each piece carries the way's own
id and tags plus ``lw``: a fractional lane count stepping from the wider width
down to the narrower one. The style reads ``lw`` ahead of ``lanes``, so the
surface, kerb casing and skirt all narrow together over the taper instead of
at the node.

- Only z14+ tiles get the pieces (the zooms the carriageway layers draw at);
  every other zoom keeps the untouched way. ``TAPER_MIN_ZOOM``.
- The pieces never compete for a tile's feature budget: selection is made on
  whole ways and a chosen way is expanded afterwards (``expand_selected``), so
  a tapered tile holds exactly the features the untapered one did.
- The taper is ``TAPER_M_PER_LANE`` per lane of difference, capped at
  ``TAPER_MAX_SHARE`` of the wider way so a short way stepping at both ends
  tapers at both without the two meeting.
- The parts are NESTED, not laid end to end. At an end taper, part j runs
  from ``LEVER_M`` back inside the full-width road all the way to cut j, and
  carries the width of the step that ends at cut j; so any point in the taper
  lies under several parts, and the widest of them is exactly the staircase
  width there. Two earlier layouts failed in the GL JS bench, both on the old
  style over tapered tiles: parts that merely met left an antialiased hairline
  at every joint, and short overlapping parts did not fix it, because MVT
  integer coordinates quantise a 3 m part's two endpoints independently — the
  parts' headings jittered by ~3.5 deg (S06, Al Rayyan Rd, -11.3 / -10.6 /
  -7.1 deg on one straight road) and a 5-lane road's kerb came out as a
  sawtooth. A nested part's direction is fixed by the way's own vertices
  30 m back, and every part's end lies INSIDE the next part, so no joint is
  exposed. A client that has never heard of ``lw`` (every build before this
  one) draws all the parts at the way's full width, and their union is the
  original road.
- Tunnels are not tapered: their surface is drawn at 45% opacity, and a
  double-drawn overlap would read as a band.
- Junctions (three or more arms) are left alone: a merge or diverge is where
  two surfaces overlap, which the style's round caps close, and cutting
  either way there would move markings the lane layer has already trimmed.

NOT a routing input, a lane count, or anything but paint. The ``lanes`` tag is
left as it was on every piece; only the new ``lw`` says how wide to draw it.
The body and parts carry no ``name``/``name:*``/``ref``. The road's label rides
on a CARRIER: the whole way, unchanged but for ``taper_carrier``, which new
clients' carriageway layers skip and old clients draw as the original road
(``_unlabelled``, ``taper_way``).
"""

from __future__ import annotations

import copy
import math
from collections import Counter, defaultdict
from typing import Any, Dict, Iterable, List, Sequence, Tuple

from .lanes import surface_lanes

TAPER_MIN_ZOOM = 14           # VectorStyle W_Z0: the carriageway layers start here
TAPER_M_PER_LANE = 30.0       # along-road length per lane of width difference
TAPER_MAX_SHARE = 0.45        # of the wider way's length, per end
TAPER_MIN_M = 6.0             # shorter than this is not worth cutting
PIECE_M = 3.0                 # target spacing between width steps
MAX_STEP_LANES = 0.03         # ... but never a bigger width change than this per step:
                              # ~5 cm a side, under a pixel at z19. At 0.2 lanes the
                              # kerb visibly rippled at every step (GL JS bench, S06)
MAX_PIECES = 128
LEVER_M = 30.0                # every part starts this far back inside the full-width road
BEND_TOL_DEG = 0.5            # a vertex bending less than this counts as straight
_R = 6378137.0


def _drivable(f: Any) -> bool:
    p = getattr(f, "properties", None) or {}
    return (p.get("kind") == "road" and p.get("car") is True
            and getattr(f, "geometry_type", None) == "LineString"
            and len(getattr(f, "coordinates", None) or ()) >= 2)


def _tunnel(f: Any) -> bool:
    return (f.properties or {}).get("tunnel") not in (None, "", "no", "0", "false")


def _key(c: Sequence[float]) -> Tuple[int, int]:
    # The converter writes a shared OSM node's coordinates identically into
    # every way that uses it, so exact equality at 1e-7 deg is a node identity.
    return (round(c[0] * 1e7), round(c[1] * 1e7))


def _dist(a: Sequence[float], b: Sequence[float]) -> float:
    k = math.cos(math.radians((a[1] + b[1]) / 2.0))
    dx = math.radians(b[0] - a[0]) * k
    dy = math.radians(b[1] - a[1])
    return _R * math.hypot(dx, dy)


def _cum(coords: Sequence[Sequence[float]]) -> List[float]:
    out = [0.0]
    for a, b in zip(coords, coords[1:]):
        out.append(out[-1] + _dist(a, b))
    return out


def _bends(coords: Sequence[Sequence[float]]) -> List[bool]:
    """True at every interior vertex where the line changes direction."""
    out = [False] * len(coords)
    for i in range(1, len(coords) - 1):
        a, b, c = coords[i - 1], coords[i], coords[i + 1]
        k = math.cos(math.radians(b[1]))
        h1 = math.atan2((b[1] - a[1]), (b[0] - a[0]) * k)
        h2 = math.atan2((c[1] - b[1]), (c[0] - b[0]) * k)
        d = abs((math.degrees(h2 - h1) + 180.0) % 360.0 - 180.0)
        out[i] = d > BEND_TOL_DEG
    return out


def _point_at(coords, cum, s: float) -> Tuple[int, List[float]]:
    """(index of the segment containing s, the point at along-distance s)."""
    s = max(0.0, min(cum[-1], s))
    i = 0
    while i + 2 < len(cum) and cum[i + 1] < s:
        i += 1
    seg = cum[i + 1] - cum[i]
    t = 0.0 if seg <= 0 else (s - cum[i]) / seg
    a, b = coords[i], coords[i + 1]
    return i, [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t]


def _substring(coords, cum, s0: float, s1: float) -> List[List[float]]:
    i0, p0 = _point_at(coords, cum, s0)
    i1, p1 = _point_at(coords, cum, s1)
    out = [p0]
    for j in range(i0 + 1, i1 + 1):
        if cum[j] > s0 and cum[j] < s1:
            out.append(list(coords[j]))
    out.append(p1)
    return out


CARRIER = "taper_carrier"     # the whole-way copy that carries the label (see below)


def _unlabelled(props: Dict[str, Any], lw: Any = None) -> Dict[str, Any]:
    """A body's or part's tags: the way's own, minus what LABELS it (plus ``lw``).

    MapLibre spaces a line label's anchors along the line's OWN geometry and
    resolves collisions across the tile, so relabelling a way on shorter lines
    moves labels — on it and, through collisions, on its neighbours. Measured
    in the GL JS bench with the old style: taking the name off the parts but
    leaving it on a shortened body still moved road labels in 12 of 17 views.
    So neither the body nor any part carries the name: the carrier does.
    """
    out = {k: v for k, v in props.items()
           if not (k == "name" or k.startswith("name:") or k == "ref")}
    if lw is not None:
        out["lw"] = lw
    return out


def _clone(f: Any, coords: List[List[float]], props: Dict[str, Any]) -> Any:
    g = copy.copy(f)
    g.coordinates = coords
    g.properties = props
    if hasattr(f, "bbox"):
        xs = [c[0] for c in coords]
        ys = [c[1] for c in coords]
        g.bbox = (min(xs), min(ys), max(xs), max(ys))
    return g


def plan_steps(features: Iterable[Any]) -> Tuple[Dict[int, Dict[str, float]], Dict[str, Any]]:
    """Which ends of which ways taper, and down to what width.

    Returns ({feature index: {"start"|"end": narrower lane count}}, stats).
    Indices are positions in ``features`` as given.
    """
    feats = list(features)
    ends: Dict[Tuple[int, int], List[Tuple[int, str]]] = defaultdict(list)
    arms: Counter = Counter()
    for i, f in enumerate(feats):
        if not _drivable(f):
            continue
        cs = f.coordinates
        for j, c in enumerate(cs):
            k = _key(c)
            if j == 0 or j == len(cs) - 1:
                arms[k] += 1
                ends[k].append((i, "start" if j == 0 else "end"))
            else:
                arms[k] += 2
    plans: Dict[int, Dict[str, float]] = defaultdict(dict)
    stats: Counter = Counter()
    for k, v in ends.items():
        if arms[k] != 2 or len(v) != 2:
            continue
        (ia, ea), (ib, eb) = v
        if ia == ib:
            continue            # a closed loop meeting itself
        if _tunnel(feats[ia]) or _tunnel(feats[ib]):
            continue            # drawn translucent: an overlap would show
        wa = surface_lanes(feats[ia].properties)
        wb = surface_lanes(feats[ib].properties)
        if wa == wb:
            continue
        wide, wend, narrow = (ia, ea, wb) if wa > wb else (ib, eb, wa)
        plans[wide][wend] = float(narrow)
        stats[f"step_{max(wa, wb)}_to_{min(wa, wb)}"] += 1
        stats["steps"] += 1
    return dict(plans), dict(sorted(stats.items()))


def taper_way(f: Any, plan: Dict[str, float]) -> List[Any]:
    """The way ``f`` as its carrier, untapered body and nested taper parts (see
    the module note). Output order: carrier, start-taper parts, body, end-taper
    parts."""
    cs = [list(c) for c in f.coordinates]
    cum = _cum(cs)
    total = cum[-1]
    if total <= 0:
        return [f]
    wide = float(surface_lanes(f.properties))
    lengths: Dict[str, float] = {}
    for end, narrow in plan.items():
        L = min(TAPER_M_PER_LANE * (wide - narrow), TAPER_MAX_SHARE * total)
        if L >= TAPER_MIN_M:
            lengths[end] = L
    if not lengths:
        return [f]

    def steps(end: str) -> List[Tuple[float, float, float]]:
        """(from, to, lw) for each step, ordered away from the node's far side.
        `from`/`to` are along-line cuts; lw is the width over that step."""
        L, narrow = lengths[end], plan[end]
        n = max(2, int(math.ceil(L / PIECE_M)),
                int(math.ceil((wide - narrow) / MAX_STEP_LANES)))
        n = min(MAX_PIECES, n)
        out = []
        for q in range(n):
            # q = 0 is the step next to the full-width body, q = n-1 next to the node
            frac = 1.0 - (q + 0.5) / n
            lw = round(narrow + (wide - narrow) * frac, 3)
            if end == "end":
                out.append((total - L + L * q / n, total - L + L * (q + 1) / n, lw))
            else:
                out.append((L - L * (q + 1) / n, L - L * q / n, lw))
        return out

    parts: List[Any] = []
    body0 = lengths.get("start", 0.0)
    body1 = total - lengths.get("end", 0.0)
    # A lever reaches back into the full-width BODY only, never into the taper
    # at the other end: on a short way tapered at both ends, a 30 m lever from
    # one taper ran into the other and painted its near-full width over the
    # narrowing (w619559071, 56.5 m: a 1.91-lane jump in the drawn width).
    if "start" in lengths:
        L = lengths["start"]
        reach = min(body1, L + LEVER_M)
        # nearest the node first: the narrowest part is the one reaching the start
        for c0, _c1, lw in reversed(steps("start")):
            parts.append(_clone(f, _substring(cs, cum, c0, reach), _unlabelled(f.properties, lw)))
    tail: List[Any] = []
    if "end" in lengths:
        L = lengths["end"]
        back = max(body0, total - L - LEVER_M)
        for _c0, c1, lw in steps("end"):
            tail.append(_clone(f, _substring(cs, cum, back, c1), _unlabelled(f.properties, lw)))
    if body1 - body0 > 1e-6:
        parts.append(_clone(f, _substring(cs, cum, body0, body1), _unlabelled(f.properties)))
    # The CARRIER: the way itself, unchanged but for one flag. It is the only
    # feature that keeps the name, so labels are placed on exactly the geometry
    # they always were. A new client's carriageway layers skip it
    # (`VectorStyle.DRIVABLE`: `!has taper_carrier`) and draw the body and parts;
    # an old client draws it too, at the way's full width — which is precisely
    # the original road, with the body and parts inside it.
    carrier = _clone(f, [list(c) for c in cs], dict(f.properties, **{CARRIER: True}))
    return [carrier] + parts + tail


def taper_parts(features: Sequence[Any]) -> Tuple[Dict[int, List[Any]], Dict[str, Any]]:
    """{feature index: the way's tapered parts, in order} for every way that tapers.

    The bake does NOT put these into the feature list it buckets and ranks.
    A tile's features are chosen from the untapered ways exactly as they always
    were, and only then is each chosen way swapped for its parts
    (``expand_selected``). Were the pieces ranked in their own right, twenty
    pieces of one road would take twenty places under the per-tile cap, and in
    a dense z14 tile they pushed out buildings, parks and other roads (measured
    on the first tapered bake: 90 tile/kind losses, 7 tiles losing roads).
    """
    plans, stats = plan_steps(features)
    parts: Dict[int, List[Any]] = {}
    pieces = 0
    for i in sorted(plans):
        got = taper_way(features[i], plans[i])
        if len(got) > 1:
            parts[i] = got
            pieces += sum(1 for g in got if "lw" in g.properties)
    stats = dict(stats, ways_tapered=len(parts), taper_pieces=pieces,
                 features_in=len(features))
    return parts, stats


def _intersects(a, b) -> bool:
    return not (a[2] < b[0] or b[2] < a[0] or a[3] < b[1] or b[3] < a[1])


def expand_selected(selected: Sequence[Any], parts_by_obj: Dict[int, List[Any]],
                    tile_bbox: Tuple[float, float, float, float],
                    margin: float = 0.25) -> List[Any]:
    """``selected`` with every tapered way replaced by its parts near this tile.

    ``parts_by_obj`` is keyed by ``id()`` of the ORIGINAL feature object. A
    part is kept if its bbox meets the tile grown by ``margin`` of a tile on
    every side — far wider than the renderer's line buffer, so nothing at a
    tile edge goes missing, while parts at the far end of a long way stay out.
    """
    w = (tile_bbox[2] - tile_bbox[0]) * margin
    h = (tile_bbox[3] - tile_bbox[1]) * margin
    box = (tile_bbox[0] - w, tile_bbox[1] - h, tile_bbox[2] + w, tile_bbox[3] + h)
    out: List[Any] = []
    for f in selected:
        ps = parts_by_obj.get(id(f))
        if ps is None:
            out.append(f)
            continue
        near = [g for g in ps[1:] if g.bbox is None or _intersects(g.bbox, box)]
        out.append(ps[0])           # the carrier: the selected way itself, always
        out.extend(near)
    return out


def apply_tapers(features: Sequence[Any]) -> Tuple[List[Any], Dict[str, Any]]:
    """``features`` with every tapered way replaced by its parts, in place.

    Order is preserved, and a feature that is not tapered is the SAME object,
    not a copy. (The bake uses ``taper_parts`` + ``expand_selected``; this is
    the whole-list view of the same result.)
    """
    parts, stats = taper_parts(features)
    out: List[Any] = []
    for i, f in enumerate(features):
        out.extend(parts.get(i, (f,)))
    return out, dict(stats, features_out=len(out))
