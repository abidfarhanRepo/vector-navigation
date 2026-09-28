"""Pure-Python Mapbox Vector Tile (MVT) encoder/decoder + tile pipeline.

Implements the MVT wire format (protobuf Tile/Layer/Feature/Value) with no
external dependencies, so the M1 slice stays green in the dependency-free CI
image. Coordinates are projected from WGS84 lon/lat into integer tile-local
space [0, extent] via Web-Mercator, then command-interleaved per the MVT spec.

Reference: https://github.com/mapbox/vector-tile-spec/tree/master/2.1
"""

import math
from typing import Any, Dict, Iterator, List, Optional, Tuple

from .layers import CLIP_BUFFER

EXTENT_DEFAULT = 4096

# --- low-level protobuf primitives -----------------------------------------

def _varint(n: int) -> bytes:
    """Unsigned varint encoding (n treated as unsigned 64-bit)."""
    n &= 0xFFFFFFFFFFFFFFFF
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            break
    return bytes(out)


def _zigzag(n: int) -> int:
    """32-bit zigzag encode of a signed integer."""
    return ((n << 1) ^ (n >> 31)) & 0xFFFFFFFF


def _unzigzag(n: int) -> int:
    return (n >> 1) ^ -(n & 1)


def _tag(field: int, wire: int) -> bytes:
    return _varint((field << 3) | wire)


def _encode_string(field: int, s: str) -> bytes:
    b = s.encode("utf-8")
    return _tag(field, 2) + _varint(len(b)) + b


def _encode_uint(field: int, n: int) -> bytes:
    return _tag(field, 0) + _varint(n)


def _encode_bytes_field(field: int, payload: bytes) -> bytes:
    return _tag(field, 2) + _varint(len(payload)) + payload


# --- Web-Mercator projection ------------------------------------------------

def lonlat_to_local(lon: float, lat: float, z: int, x: int, y: int,
                    extent: int = EXTENT_DEFAULT) -> Tuple[int, int]:
    """Project (lon, lat) to integer tile-local coords in [0, extent]."""
    n = 2 ** z
    x_merc = (lon + 180.0) / 360.0
    sinlat = math.sin(math.radians(lat))
    y_merc = 0.5 - math.log((1.0 + sinlat) / (1.0 - sinlat)) / (4.0 * math.pi)
    local_x = (x_merc - x / n) * n * extent
    local_y = (y_merc - y / n) * n * extent
    return int(round(local_x)), int(round(local_y))


def tile_bbox(z: int, x: int, y: int) -> Tuple[float, float, float, float]:
    """Geographic (min_lon, min_lat, max_lon, max_lat) covered by a tile."""
    n = 2 ** z
    lon_min = x / n * 360.0 - 180.0
    lon_max = (x + 1) / n * 360.0 - 180.0

    def merc_to_lat(y_merc: float) -> float:
        return math.degrees(math.atan(math.sinh((0.5 - y_merc) * 2.0 * math.pi)))

    lat_top = merc_to_lat(y / n)
    lat_bottom = merc_to_lat((y + 1) / n)
    return (lon_min, lat_bottom, lon_max, lat_top)


# --- geometry command encoding ---------------------------------------------

_MOVE_TO = 1
_LINE_TO = 2
_CLOSE_PATH = 7


def _geom_commands(geometry_type: str, coords: Any, z: int, x: int, y: int,
                   extent: int) -> List[bytes]:
    cmds: List[bytes] = []

    def to_local(pt):
        return lonlat_to_local(pt[0], pt[1], z, x, y, extent)

    if geometry_type in ("Point", "MultiPoint"):
        pts = [coords] if isinstance(coords[0], (int, float)) else coords
        local = [to_local(p) for p in pts]
        cmds.append(_varint((len(local) << 3) | _MOVE_TO))
        px = py = 0
        for cx, cy in local:
            cmds.append(_varint(_zigzag(cx - px)))
            cmds.append(_varint(_zigzag(cy - py)))
            px, py = cx, cy
        return cmds

    if geometry_type in ("LineString", "MultiLineString"):
        lines = [coords] if isinstance(coords[0][0], (int, float)) else coords
        for line in lines:
            local = [to_local(p) for p in line]
            if len(local) < 2:
                continue
            cmds.append(_varint((1 << 3) | _MOVE_TO))
            cmds.append(_varint(_zigzag(local[0][0])))
            cmds.append(_varint(_zigzag(local[0][1])))
            cmds.append(_varint(((len(local) - 1) << 3) | _LINE_TO))
            px, py = local[0]
            for cx, cy in local[1:]:
                cmds.append(_varint(_zigzag(cx - px)))
                cmds.append(_varint(_zigzag(cy - py)))
                px, py = cx, cy
        return cmds

    if geometry_type == "Polygon":
        rings = coords  # coords is a list of rings; each ring is a list of [lon, lat]
        cmds += _encode_rings(rings, to_local)
        return cmds

    if geometry_type == "MultiPolygon":
        for poly in coords:
            cmds += _encode_rings(poly, to_local)
        return cmds

    return cmds  # unknown geometry -> empty


def _encode_rings(rings, to_local) -> List[bytes]:
    cmds: List[bytes] = []
    for ring in rings:
        local = [to_local(p) for p in ring]
        if len(local) >= 2 and local[0] == local[-1]:
            local = local[:-1]
        if len(local) < 3:
            continue
        cmds.append(_varint((1 << 3) | _MOVE_TO))
        cmds.append(_varint(_zigzag(local[0][0])))
        cmds.append(_varint(_zigzag(local[0][1])))
        cmds.append(_varint(((len(local) - 1) << 3) | _LINE_TO))
        px, py = local[0]
        for cx, cy in local[1:]:
            cmds.append(_varint(_zigzag(cx - px)))
            cmds.append(_varint(_zigzag(cy - py)))
            px, py = cx, cy
        cmds.append(_varint((1 << 3) | _CLOSE_PATH))
    return cmds


_GEOM_TYPE_CODE = {"Point": 1, "LineString": 2, "Polygon": 3}


# --- clipping to the tile (+ buffer) ----------------------------------------
#
# Features are SELECTED by bbox intersection, which says nothing about how far
# the rest of the geometry runs: a 20 km motorway touching a tile used to be
# written whole, with vertices up to +/-208,166 tile units. MapLibre Native
# drops any feature beyond int16 and draws phantom straight roads for the rest
# when overzoomed. So every geometry is clipped, in lon/lat and BEFORE
# quantization, to the tile grown by CLIP_BUFFER tile units on each side (see
# layers.CLIP_BUFFER for why 256).
#
# The box is grown LINEARLY in lon and lat, which is exactly the frame
# mapbox-vector-tile quantizes in (``quantize_bounds`` is a linear map of the
# lon/lat bbox onto 0..extent), so the clip edge lands on -256 / extent+256
# after rounding.

_LINE_TYPES = ("LineString", "MultiLineString")
_POLY_TYPES = ("Polygon", "MultiPolygon")
_POINT_TYPES = ("Point", "MultiPoint")


def clip_box(bbox, extent: int = EXTENT_DEFAULT,
             buffer: int = CLIP_BUFFER) -> Tuple[float, float, float, float]:
    """The tile's lon/lat bbox grown by ``buffer`` tile units on every side."""
    minx, miny, maxx, maxy = bbox
    dx = (maxx - minx) * buffer / extent
    dy = (maxy - miny) * buffer / extent
    return (minx - dx, miny - dy, maxx + dx, maxy + dy)


def _positions(coords: Any) -> Iterator[Tuple[float, float]]:
    """Every [lon, lat] position in a GeoJSON coordinates array, any nesting."""
    if not coords:
        return
    if isinstance(coords[0], (int, float)):
        yield coords[0], coords[1]
        return
    for c in coords:
        yield from _positions(c)


def _inside(coords: Any, box) -> bool:
    minx, miny, maxx, maxy = box
    for x, y in _positions(coords):
        if not (minx <= x <= maxx and miny <= y <= maxy):
            return False
    return True


def clip_geometry(geometry_type: str, coords: Any,
                  box) -> Optional[Tuple[str, Any]]:
    """Clip one GeoJSON geometry to ``box`` (lon/lat). None if nothing is left.

    A geometry already inside the box is returned AS GIVEN, the same objects,
    so a tile whose features all sit inside it encodes to exactly the bytes it
    did before clipping existed. Lines may come back as MultiLineStrings and
    polygons as MultiPolygons; a part of lower dimension produced where a
    geometry only touches the edge (a point from a line, a line from a
    polygon) is discarded, since it would be a different feature type.
    """
    if _inside(coords, box):
        return geometry_type, coords
    import shapely
    from shapely.geometry import mapping, shape
    from shapely.geometry import MultiLineString, MultiPoint, MultiPolygon

    geom = shape({"type": geometry_type, "coordinates": coords})
    try:
        clipped = shapely.clip_by_rect(geom, *box)
    except shapely.errors.GEOSException:
        # An invalid polygon (self-intersection in the source) can defeat the
        # rectangle clipper; repair it and fall back to a general intersection.
        clipped = shapely.make_valid(geom).intersection(shapely.box(*box))
    if clipped.is_empty:
        return None

    if geometry_type in _LINE_TYPES:
        want, multi = ("LineString",), MultiLineString
    elif geometry_type in _POLY_TYPES:
        want, multi = ("Polygon",), MultiPolygon
    else:
        want, multi = ("Point",), MultiPoint
    parts = []
    stack = [clipped]
    while stack:
        g = stack.pop()
        if g.is_empty:
            continue
        if g.geom_type in want:
            parts.append(g)
        elif hasattr(g, "geoms"):
            stack.extend(reversed(list(g.geoms)))
    if not parts:
        return None
    out = parts[0] if len(parts) == 1 else multi(parts)
    gj = mapping(out)
    return gj["type"], _as_lists(gj["coordinates"])


def _as_lists(c: Any) -> Any:
    if isinstance(c, (tuple, list)) and c and isinstance(c[0], (int, float)):
        return [float(v) for v in c]
    return [_as_lists(v) for v in c]


# --- feature / layer / tile encoding ---------------------------------------


def _fnv1a_64(s: str) -> int:
    h = 0xCBF29CE484222325
    for b in s.encode("utf-8"):
        h ^= b
        h = (h * 0x100000001B3) & 0xFFFFFFFFFFFFFFFF
    return h


def _encode_value(field: int, value: str) -> bytes:
    return _encode_string(field, value)


def encode_tile(layers: List[Tuple[str, List[Dict[str, Any]]]],
                extent: int = EXTENT_DEFAULT,
                clip_buffer: int = CLIP_BUFFER) -> bytes:
    """Encode a list of (layer_name, features) into an MVT Tile (bytes).

    Delegates to ``mapbox-vector-tile`` (a spec-valid MVT implementation) so
    the output is decodable by MapLibre and standard tooling. Feature dicts
    carry lon/lat ``coordinates``; the tile's geographic bbox comes from the
    features' ``_z/_x/_y`` and is used to quantize into tile-local space.

    EVERY geometry in EVERY layer is first clipped to the tile grown by
    ``clip_buffer`` tile units (:func:`clip_geometry`); a feature with nothing
    left inside that box is dropped. Ids and properties are untouched.
    """
    try:
        import mapbox_vector_tile
    except ImportError:  # pragma: no cover - dependency is declared
        raise RuntimeError("mapbox-vector-tile is required to encode MVT tiles")
    # mapbox-vector-tile 2.x and 3.x have DIFFERENT encode signatures:
    #   2.x: encode(features, layer_name=..., quantize_bounds=..., ...)
    #   3.x: encode([{name, features}, ...], quantize_bounds=..., ...)
    # The CI mirror ships 2.2.0; dev envs often have 3.x. Try the 3.x form and
    # fall back to the 2.x form on the signature error 2.x raises, so the same
    # code passes in both environments without fragile version sniffing.
    #
    # EVERY non-empty layer is encoded. Until V8 this loop returned after the
    # first one, so a second layer passed in was dropped without a word; no
    # caller passed one, which is the only reason it never showed. A tile with
    # a single non-empty layer goes through exactly the call it always did, so
    # its bytes are unchanged (pinned by test_encode_multilayer).
    layer_docs = []
    tile = bbox = None
    for name, feats in layers:
        if not feats:
            continue
        here = (feats[0]["_z"], feats[0]["_x"], feats[0]["_y"])
        if tile is None:
            tile = here
            bbox = list(tile_bbox(*here))
            box = clip_box(bbox, extent, clip_buffer)
        elif here != tile:
            raise ValueError(f"layer {name!r} is for tile {here}, not {tile}")
        gj_feats = []
        for f in feats:
            clipped = clip_geometry(f["geometry_type"], f["coordinates"], box)
            if clipped is None:
                continue
            gtype, coords = clipped
            gj = {
                "type": "Feature",
                "geometry": {"type": gtype, "coordinates": coords},
                "properties": f.get("properties") or {},
            }
            fid = f.get("id")
            if fid is not None:
                gj["id"] = fid if isinstance(fid, int) else _fnv1a_64(str(fid))
            gj_feats.append(gj)
        if gj_feats:
            layer_docs.append({"name": name, "features": gj_feats})
    if not layer_docs:
        return b""
    try:
        return mapbox_vector_tile.encode(
            layer_docs,
            quantize_bounds=bbox,
            y_coord_down=False,
            extents=extent,
        )
    except (KeyError, TypeError):
        # 2.x API: flat features + layer_name kwarg — one layer per call.
        if len(layer_docs) != 1:
            raise RuntimeError("this mapbox-vector-tile cannot encode more than "
                               "one layer per tile")
        return mapbox_vector_tile.encode(
            layer_docs[0]["features"],
            layer_name=layer_docs[0]["name"],
            quantize_bounds=bbox,
            y_coord_down=False,
            extents=extent,
        )


# --- minimal decoder (for round-trip tests / verification) -----------------

def _read_varint(buf: bytes, pos: int):
    result = 0
    shift = 0
    while True:
        b = buf[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            break
        shift += 7
    return result, pos


def _parse_message(buf: bytes) -> Dict[int, List[Any]]:
    fields: Dict[int, List[Any]] = {}
    pos = 0
    n = len(buf)
    while pos < n:
        key, pos = _read_varint(buf, pos)
        field_no = key >> 3
        wire = key & 0x7
        if wire == 0:
            val, pos = _read_varint(buf, pos)
        elif wire == 2:
            ln, pos = _read_varint(buf, pos)
            val = buf[pos:pos + ln]
            pos += ln
        elif wire == 1:
            val = buf[pos:pos + 8]
            pos += 8
        elif wire == 5:
            val = buf[pos:pos + 4]
            pos += 4
        else:
            raise ValueError(f"unsupported wire type {wire}")
        fields.setdefault(field_no, []).append(val)
    return fields


def decode_tile(data: bytes) -> Dict[str, Any]:
    """Decode an MVT Tile into a dict for verification (uses mapbox-vector-tile).

    Returns the same legacy shape as before so callers/tests keep working:
    ``{"layers": [{"name", "extent", "features": [{"id", "type", "properties",
    "geometry"}]}]}`` where ``type`` is the numeric MVT geometry code.

    ``geometry`` is a list of rings of ``[x, y]`` in **TILE-LOCAL** coordinates:
    integers in ``0..extent`` (``extent`` is the layer's own, typically 4096),
    with **y increasing DOWNWARD** from the tile's top edge.

    **These are not longitude and latitude.** This docstring used to say they
    were, and that claim produced at least one incorrect measurement before it
    was caught. The behaviour is correct and every caller is written against
    the behaviour; it was the documentation that was wrong. To get geographic
    coordinates, invert the projection with the tile's own z/x/y — the decoded
    values carry no knowledge of which tile they came from.

    Pinned by ``test_decode_tile_returns_tile_local_y_down``.
    """
    try:
        import mapbox_vector_tile
    except ImportError:  # pragma: no cover
        raise RuntimeError("mapbox-vector-tile is required to decode MVT tiles")
    dec = mapbox_vector_tile.decode(data)
    code_for = {"Point": 1, "LineString": 2, "Polygon": 3,
                "MultiPoint": 1, "MultiLineString": 2, "MultiPolygon": 3}
    result: Dict[str, Any] = {"layers": []}
    for name, layer in dec.items():
        feats = []
        for f in layer.get("features", []):
            g = f.get("geometry", {}) or {}
            gtype = g.get("type", "")
            coords = g.get("coordinates", [])
            if gtype == "Point":
                geom = [[coords]]
            elif gtype in ("LineString", "MultiPoint"):
                geom = [coords]
            elif gtype == "Polygon":
                geom = coords
            elif gtype == "MultiLineString":
                geom = coords
            elif gtype == "MultiPolygon":
                geom = [ring for poly in coords for ring in poly]
            else:
                geom = []
            # mapbox-vector-tile.decode returns y-UP tile-local coords; the rest
            # of the codebase (lonlat_to_local) and MapLibre use y-DOWN, so
            # flip y to keep decode_tile consistent with the projection helpers.
            extent = layer.get("extent", EXTENT_DEFAULT)
            geom = [[[cx, extent - cy] for cx, cy in ring] for ring in geom]
            feats.append({
                "id": f.get("id"),
                "type": code_for.get(gtype, 0),
                "properties": f.get("properties") or {},
                "geometry": geom,
            })
        result["layers"].append({
            "name": name,
            "extent": layer.get("extent", EXTENT_DEFAULT),
            "features": feats,
        })
    return result

