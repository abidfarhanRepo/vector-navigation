"""The speed-camera catalog and its projection onto a route (V7.3 / V7.7).

Bootstrap bakes ``<region>_cameras.geojson`` beside the roads and foot
networks -- a FeatureCollection of ``kind=camera`` points carrying the OSM
node id (feature ``id`` = ``n<osm>``) and the source tags a mapper wrote
(``maxspeed``, ``direction``, ``enforcement``). This module loads THAT
artifact, classifies each point into the closed camera-type vocabulary, and
answers one question for the router: *which cameras does this route pass,
where along it, and from which approach?*

What a camera is NOT, and what nothing here claims:

* active, enforcing, filming or flashing -- no OSM tag is any of those, so
  the wire never says so. A camera entry is a LOCATION with provenance;
* a fine -- nothing here can know one;
* an enforcement direction -- ``direction`` is carried as provenance for the
  client's conservative gate (a sane tag more than 120 degrees off the
  route's approach bearing means the camera faces the other carriageway and
  is not warned; malformed/missing tags are ignored and the location fact
  stands). The backend never decides direction: it reports the tag, the
  client decides the warning.

## The type vocabulary is closed, and it is derived from the source

``classify_camera`` maps the tags a mapper actually wrote onto exactly five
strings, and there is no sixth:

    speed          highway=speed_camera (that tag's own definition: a fixed
                   road-side or overhead speed camera), or enforcement=maxspeed
    average_speed  enforcement=average_speed (section control)
    red_light      enforcement=traffic_signals
    combined       the source states BOTH a speed-enforcement function and
                   red-light enforcement for the same device
    unknown        anything else -- including a generic man_made=surveillance
                   node, or an enforcement value that is not a speed or
                   red-light function (toll, maxweight, check, ...)

``unknown`` is not a synonym for "speed camera": it is the honest answer when
the source does not state a type, and the client NEVER announces an unknown
camera (see ``vector_geo.camera.CameraType``). Nothing here infers a type from
a camera's name, its position, or its neighbours.

## Deduplication is by SOURCE IDENTITY, never by proximity

Two distinct OSM camera nodes a few metres apart are two real cameras (Qatar
has 26 such pairs, the closest 11.5 m: at one gantry, front and rear facing).
Merging them on distance would delete a real fact, so the only merge here is
same-identity: a repeated feature id is one camera listed twice and the first
occurrence wins. Measured on the 2026-09-13 Qatar bake: 133 cameras, 133
distinct identities, 0 duplicates -- so this rule deletes nothing today and
cannot delete a real camera tomorrow.

## Projection

Route-relative and identical to the signals': perpendicular onto the route's
own segments, the FOOT REQUIRED TO LIE ON THE SEGMENT, rejected beyond a snap
gate, ordered by along-route distance. The segment search is grid-prefiltered
-- the grid is built per route and only narrows which segments a camera is
measured against, so the answer is the same one a scan of every segment gives
(``test_grid_prefilter_is_exactly_the_flat_scan``).

The catalog is OPTIONAL and its absence is a valid state: a deployment baked
before this step must route exactly as it did before. Absence means the
``cameras`` key is simply absent from the ``/navigate`` reply.
"""

import json
import logging
import math
import os
from typing import Any, Dict, List, Optional, Set, Tuple

from .haversine import haversine_meters

logger = logging.getLogger("vector_routing.cameras")

Coord = Tuple[float, float]

#: How far off the route a camera may sit and still be claimed as on it.
#: Same order as the signals' snap: a camera node sits on or beside its
#: carriageway (Qatar's 133 are a median 0.4 m from a graph vertex).
SNAP_MAX_M = 40.0

# ---- the closed type vocabulary (see the module docstring) ----------------
TYPE_SPEED = "speed"
TYPE_AVERAGE_SPEED = "average_speed"
TYPE_RED_LIGHT = "red_light"
TYPE_COMBINED = "combined"
TYPE_UNKNOWN = "unknown"

#: Every value the vocabulary can produce. Adding one means adding a
#: presentation string and a classification rule in the same change.
CAMERA_TYPES = (TYPE_SPEED, TYPE_AVERAGE_SPEED, TYPE_RED_LIGHT, TYPE_COMBINED,
                TYPE_UNKNOWN)

#: A speed-enforcement function stated for a device.
_SPEED_FUNCTION = "speed"

#: Grid cell for the per-route segment prefilter, in degrees. ~111 m of
#: latitude; the grid only narrows candidates, so its size trades memory for
#: candidate count and never changes the answer.
_GRID_DEG = 0.001

#: Degrees of latitude per metre, used to pad the segment grid by the snap
#: gate. Longitude degrees are shorter, so padding both axes with this value
#: is conservative (it can only add candidates, never lose one).
_M_PER_DEG_LAT = 111_320.0


def classify_camera(props: Dict[str, Any]) -> str:
    """The camera type the source STATES, or ``unknown``.

    Pure, and deliberately unable to infer: a camera is a speed camera
    because a tag says so (``highway=speed_camera`` is defined as a fixed
    speed camera; ``enforcement=*`` names the function explicitly), never
    because of where it stands or what it is called. Anything the vocabulary
    does not cover -- a generic surveillance node, a toll or weight
    enforcement device, a red-light value the wiki does not define -- is
    ``unknown``, which no client announces.
    """
    enforcement = str(props.get("enforcement") or "").strip().lower()
    speed = props.get("highway") == "speed_camera"
    average = enforcement == "average_speed"
    red_light = enforcement == "traffic_signals"
    if enforcement == "maxspeed":
        speed = True

    if red_light and (speed or average):
        # The source states two enforcement functions for one device: the
        # documented "both a speed trap and red light camera in one device".
        return TYPE_COMBINED
    if red_light:
        return TYPE_RED_LIGHT
    if average:
        # More specific than the generic speed-camera category, and it is
        # still speed enforcement, so it is not a combination.
        return TYPE_AVERAGE_SPEED
    if speed:
        return TYPE_SPEED
    return TYPE_UNKNOWN


class CameraCatalog:
    """The baked camera points, loaded defensively and never mutated.

    ``__len__`` of an absent or unreadable file is 0 -- a missing catalog is
    the normal state of a pre-V7.3 deployment, not an error. A malformed
    ENTRY is skipped on its own, so one bad feature cannot empty a catalog
    that is otherwise sound.
    """

    def __init__(self, cameras: List[Dict[str, Any]]) -> None:
        # Every entry carries a type from the closed vocabulary, whatever the
        # caller supplied: an entry built by hand (or by a future loader) with
        # no type, or with a word this build does not know, is `unknown`, and
        # `unknown` is what the client refuses to announce. The wire can then
        # never carry a type string outside CAMERA_TYPES.
        self._cameras = [
            {**cam,
             "type": cam.get("type") if cam.get("type") in CAMERA_TYPES else TYPE_UNKNOWN}
            for cam in cameras
        ]

    def __len__(self) -> int:
        return len(self._cameras)

    def __iter__(self):
        return iter(self._cameras)

    def type_census(self) -> Dict[str, int]:
        """How many cameras of each type the source supplied.

        The one number that says whether a camera claim is defensible: a
        census of ``{speed: 133}`` and nothing else is a deployment where
        every announced camera is one the source actually stated.
        """
        census: Dict[str, int] = {}
        for cam in self._cameras:
            census[cam["type"]] = census.get(cam["type"], 0) + 1
        return census

    @classmethod
    def from_feature_collection(cls, fc: Dict[str, Any]) -> "CameraCatalog":
        features = fc.get("features")
        if not isinstance(features, list):
            logger.warning("camera catalog has no feature list; treating it as empty")
            return cls([])
        out: List[Dict[str, Any]] = []
        for f in features:
            if not isinstance(f, dict):
                continue
            props = f.get("properties")
            if not isinstance(props, dict) or props.get("kind") != "camera":
                continue
            geo = f.get("geometry")
            coords = (geo or {}).get("coordinates") if isinstance(geo, dict) else None
            if not isinstance(coords, list) or len(coords) != 2:
                continue
            # The feature id is the stable OSM identity (`n<osm>`) and is the
            # id the client's callout source and telemetry reuse. A camera
            # without one has no identity to dedup on or to announce, so it is
            # NOT invented into `camera-<n>`: it is skipped.
            ident = f.get("id")
            if not isinstance(ident, str) or not ident.strip():
                continue
            try:
                lon, lat = float(coords[0]), float(coords[1])
            except (TypeError, ValueError):
                continue
            if not (math.isfinite(lon) and math.isfinite(lat)):
                continue
            out.append({
                "id": ident,
                "lon": lon,
                "lat": lat,
                "type": classify_camera(props),
                # Provenance only. maxspeed = the zone the camera is
                # associated with (33 of 133 nodes carry it); direction = the
                # mapper-recorded bearing (12 nodes, some malformed). Neither
                # is a claim about activity or enforcement.
                "maxspeed": props.get("maxspeed"),
                "direction": props.get("direction") or props.get("camera:direction"),
            })
        return cls(out)

    @classmethod
    def from_path(cls, path: Optional[str]) -> "CameraCatalog":
        if not path or not os.path.exists(path):
            return cls([])
        try:
            with open(path, encoding="utf-8") as fh:
                doc = json.load(fh)
            if isinstance(doc, dict):
                return cls.from_feature_collection(doc)
            return cls([])
        except (OSError, ValueError):
            logger.warning("camera catalog unreadable, continuing without it: %s", path)
            return cls([])


def _point_segment(sig: Coord, a: Coord, b: Coord) -> Optional[Tuple[float, float]]:
    """Perpendicular projection of ``sig`` onto the segment ``a->b``.

    Returns ``(t, offset_m)``, or ``None`` when the perpendicular foot does
    NOT lie on the segment. ``t`` is the fraction along the segment; the
    offset is measured at that foot.

    The foot test is the whole point of the return type. Clamping ``t`` to
    [0, 1] and then measuring at the clamped endpoint lets a point that is
    hundreds of metres past a segment's end (but roughly collinear with it)
    pass the snap gate at exactly the gate value, and lets a doubled-back
    route claim a camera at the WRONG pass. A segment may only claim a point
    whose perpendicular foot is on it; the client matcher
    (``CameraMatcher.earliestPass``) has always required this, and this is the
    same rule on the route projection so the two cannot disagree.
    """
    x1, y1 = a
    x2, y2 = b
    px, py = sig
    dx, dy = x2 - x1, y2 - y1
    denom = dx * dx + dy * dy
    if denom <= 1e-18:
        # A zero-length segment has no interior to project onto.
        return None
    t = ((px - x1) * dx + (py - y1) * dy) / denom
    if t < 0.0 or t > 1.0:
        return None
    proj = (x1 + t * dx, y1 + t * dy)
    return t, haversine_meters(sig, proj)


def _seg_bearing(a: Coord, b: Coord) -> float:
    from .router import bearing_deg

    return bearing_deg(a, b)


def _segment_grid(path: List[Coord], snap_max_m: float) -> Dict[Tuple[int, int], List[int]]:
    """Segments bucketed by grid cell, expanded by the snap gate.

    A camera within ``snap_max_m`` of a segment provably shares a cell with it
    (the segment is registered in every cell its bounding box, padded by the
    gate, touches), so a single-cell lookup is a sufficient candidate set --
    and each cell's list is built in ascending segment order, which is the
    order the flat scan visits them in, so ties break identically.
    """
    cell = _GRID_DEG
    pad = snap_max_m / _M_PER_DEG_LAT
    grid: Dict[Tuple[int, int], List[int]] = {}
    for i in range(len(path) - 1):
        ax, ay = path[i]
        bx, by = path[i + 1]
        x0 = math.floor((min(ax, bx) - pad) / cell)
        x1 = math.floor((max(ax, bx) + pad) / cell)
        y0 = math.floor((min(ay, by) - pad) / cell)
        y1 = math.floor((max(ay, by) + pad) / cell)
        for cx in range(int(x0), int(x1) + 1):
            for cy in range(int(y0), int(y1) + 1):
                grid.setdefault((cx, cy), []).append(i)
    return grid


def cameras_on_path(
    catalog: CameraCatalog,
    path: List[Coord],
    snap_max_m: float = SNAP_MAX_M,
) -> List[Dict[str, Any]]:
    """The cameras this route passes, as along-route facts, in route order.

    ``path`` is the route's own coordinate list ([lon, lat] pairs). Each
    camera is projected perpendicularly onto the closest segment whose foot
    lies on it; those beyond ``snap_max_m`` are not on this route and are not
    claimed; the survivors are ordered by along-route distance, and a repeated
    source identity is merged (see the module docstring: identity, never
    proximity).

    Returns entries: ``{"id", "lon", "lat", "along_m", "approach_bearing",
    "type", "maxspeed", "direction"}``. ``type`` is always one of
    ``CAMERA_TYPES``; ``maxspeed``/``direction`` are null when the mapper did
    not record one -- a missing speed is not invented here, and ``direction``
    is RAW provenance (the client validates it against its own route bearing,
    because a malformed value must fail validation there rather than being
    laundered into a number here). Empty for an empty catalog -- which is also
    the whole contract a pre-V7.3 deployment gets.
    """
    if not catalog._cameras or len(path) < 2:
        return []

    seg_len = [haversine_meters(path[i], path[i + 1]) for i in range(len(path) - 1)]
    cum = [0.0]
    for s in seg_len:
        cum.append(cum[-1] + s)
    if cum[-1] <= 0.0:
        return []

    grid = _segment_grid(path, snap_max_m)
    cell = _GRID_DEG

    matched: List[Dict[str, Any]] = []
    for cam in catalog._cameras:
        point: Coord = (cam["lon"], cam["lat"])
        candidates = grid.get((int(math.floor(cam["lon"] / cell)),
                               int(math.floor(cam["lat"] / cell))))
        if not candidates:
            # No segment anywhere near this camera: not on this route.
            continue
        best_t, best_off, best_i = None, None, None
        for i in candidates:
            seg = _point_segment(point, path[i], path[i + 1])
            if seg is None:
                continue
            t, off = seg
            if best_off is None or off < best_off:
                best_t, best_off, best_i = t, off, i
        if best_off is None or best_off > snap_max_m:
            # Not on this route. Off-route cameras are never claimed.
            continue
        along = cum[best_i] + best_t * seg_len[best_i]
        # The approach is the route's own bearing at the camera, windowed
        # across a short span so the number is stable on a curve (the same
        # doctrine the signals use).
        span = 12.0
        fwd = cum[best_i] + best_t * seg_len[best_i] + span
        a_i = best_i
        while fwd > cum[a_i + 1] and a_i + 1 < len(path) - 1:
            a_i += 1
        bearing = _seg_bearing(path[a_i], path[a_i + 1])
        matched.append({
            "id": cam["id"],
            "lon": round(cam["lon"], 6),
            "lat": round(cam["lat"], 6),
            "along_m": round(along, 1),
            "approach_bearing": round(bearing, 1),
            "type": cam["type"],
            "maxspeed": cam.get("maxspeed"),
            "direction": cam.get("direction"),
        })

    matched.sort(key=lambda m: (m["along_m"], m["id"]))
    out: List[Dict[str, Any]] = []
    seen: Set[str] = set()
    for m in matched:
        # Source identity is the only dedup key. Two cameras a metre apart
        # under two ids are two cameras.
        if m["id"] in seen:
            continue
        seen.add(m["id"])
        out.append(m)
    return out
