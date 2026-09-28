"""Which MVT layers a Vector tile may carry — the one rule every gate shares.

Session 50's rule was "exactly one layer, named ``basemap``": a tile whose
roads sit in any other layer is invisible to the style and is the "streets
vanish" bug class. V8 adds one OPTIONAL layer, ``lanes`` (junction-aware lane
markings, z15), which only ever rides NEXT TO a basemap layer. The rule is
therefore:

    valid    basemap
    valid    basemap + lanes
    invalid  anything without basemap (including lanes alone)
    invalid  any layer name not in the allowlist
    invalid  any name appearing twice

This module is the single definition. ``learned_layer.verify_tile_bytes``,
``validate_tiles.py``, ``release.scan_tree``/``verify_manifest`` and
``validate_release.py`` all call :func:`check_layer_names` rather than keeping
their own copy, so they cannot drift apart.

Layer names are read from the RAW protobuf (:func:`raw_layers`), not from
``decode_tile``: the decoder returns layers as a mapping keyed by name, so two
``basemap`` layers collapse into one and a duplicate is undetectable there.

Stdlib only, like ``release.py``.
"""

from __future__ import annotations

from typing import Iterable, List, Optional, Tuple

BASEMAP_LAYER = "basemap"
LANES_LAYER = "lanes"

#: Layers allowed alongside ``basemap``. Adding a name here is a contract
#: change for every client style and every gate; it is not a place for
#: experiments.
OPTIONAL_LAYERS = frozenset({LANES_LAYER})

#: How far outside the tile, in tile units (of a 4096 extent), any vertex of
#: any feature in any layer may lie. The bake (``encode.encode_tile``) clips
#: every geometry to the tile grown by this much on each side; the release gate
#: (``release.scan_tree`` -> ``geometry_out_of_range``) refuses a release with
#: a vertex beyond it. Same number on both sides, so it lives here, in the one
#: module both the bake and the far-side gate import.
#:
#: Why 256: the buffer has to be wider than anything the renderer draws PAST a
#: clipped end -- half the widest carriageway plus its round cap -- or the cut
#: shows as a notch at the tile seam. A 512 px tile of extent 4096 is 8 units
#: per screen pixel at its own zoom, so 256 units is 32 px there; at the deepest
#: overzoom (z20 drawn from z15 tiles, 32x) one pixel is 0.25 units and 256
#: units is 1,024 px past the edge. The widest carriageway at z20 is well under
#: that. And it is far inside int16 even after MapLibre Native's internal x2
#: rescale to 8192: (4096 + 256) * 2 = 8,704, against a limit of 32,767.
#:
#: Before this existed nothing was clipped: a long way crossing a tile was
#: written with vertices up to +/-208,166 tile units, which MapLibre Native
#: drops ("paths outside valid range of coordinate_type": 18,422 features in
#: 9,520 z14/z15 tiles) or, when overzoomed, draws as phantom straight roads.
CLIP_BUFFER = 256


def check_layer_names(names: Iterable[Optional[str]]) -> Tuple[bool, str]:
    """Apply the rule above to the layer names of ONE tile, in file order."""
    names = list(names)
    if not names:
        return False, "no layers"
    dups = sorted({str(n) for n in names if names.count(n) > 1})
    if dups:
        return False, f"duplicate layers {dups!r}"
    if BASEMAP_LAYER not in names:
        return False, f"expected layer '{BASEMAP_LAYER}', got {names!r}"
    extra = sorted(str(n) for n in names
                   if n != BASEMAP_LAYER and n not in OPTIONAL_LAYERS)
    if extra:
        return False, (f"unexpected layers {extra!r} next to '{BASEMAP_LAYER}' "
                       f"(allowed: {sorted(OPTIONAL_LAYERS)!r})")
    return True, "valid"


def _varint(buf: bytes, pos: int) -> Tuple[int, int]:
    result = shift = 0
    while True:
        if pos >= len(buf):
            raise ValueError("truncated varint")
        b = buf[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        shift += 7
        if not b & 0x80:
            return result, pos
        if shift > 63:
            raise ValueError("varint too long")


def _skip(buf: bytes, pos: int, wire: int) -> int:
    if wire == 0:
        return _varint(buf, pos)[1]
    if wire == 1:
        return pos + 8
    if wire == 2:
        ln, pos = _varint(buf, pos)
        return pos + ln
    if wire == 5:
        return pos + 4
    raise ValueError(f"unsupported wire type {wire}")


def raw_layers(data: bytes) -> List[Tuple[Optional[str], bytes]]:
    """``[(name, layer_message_bytes)]`` for every Tile.layers entry, in order.

    Raises ``ValueError`` on bytes that are not a well-formed Tile message.
    Unknown top-level fields are skipped, as a protobuf reader would.
    """
    out: List[Tuple[Optional[str], bytes]] = []
    pos = 0
    while pos < len(data):
        key, pos = _varint(data, pos)
        field, wire = key >> 3, key & 7
        if field == 3 and wire == 2:
            ln, pos = _varint(data, pos)
            body = data[pos:pos + ln]
            if len(body) != ln:
                raise ValueError("truncated layer")
            pos += ln
            name = None
            p = 0
            while p < len(body):
                k, p = _varint(body, p)
                f, w = k >> 3, k & 7
                if f == 1 and w == 2:
                    n, p = _varint(body, p)
                    name = body[p:p + n].decode("utf-8")
                    p += n
                else:
                    p = _skip(body, p, w)
            out.append((name, body))
        else:
            pos = _skip(data, pos, wire)
        if pos > len(data):
            raise ValueError("truncated tile")
    return out


def raw_layer_names(data: bytes) -> List[Optional[str]]:
    return [name for name, _ in raw_layers(data)]


def _layer_parts(body: bytes) -> Tuple[Optional[str], int, List[bytes]]:
    """``(name, extent, [feature_message_bytes])`` of one raw Layer message."""
    name: Optional[str] = None
    extent = 4096  # the MVT spec default when the field is absent
    feats: List[bytes] = []
    p = 0
    n = len(body)
    while p < n:
        k, p = _varint(body, p)
        f, w = k >> 3, k & 7
        if f == 1 and w == 2:
            ln, p = _varint(body, p)
            name = body[p:p + ln].decode("utf-8")
            p += ln
        elif f == 2 and w == 2:
            ln, p = _varint(body, p)
            feats.append(body[p:p + ln])
            p += ln
        elif f == 5 and w == 0:
            extent, p = _varint(body, p)
        else:
            p = _skip(body, p, w)
        if p > n:
            raise ValueError("truncated layer")
    return name, extent, feats


def _feature_geometry(feat: bytes) -> bytes:
    """The packed ``geometry`` (field 4) of one raw Feature message."""
    p = 0
    n = len(feat)
    while p < n:
        k, p = _varint(feat, p)
        f, w = k >> 3, k & 7
        if f == 4 and w == 2:
            ln, p = _varint(feat, p)
            return feat[p:p + ln]
        p = _skip(feat, p, w)
    return b""


def _geometry_bounds(geom: bytes) -> Optional[Tuple[int, int, int, int]]:
    """``(minx, miny, maxx, maxy)`` in tile units of every vertex, or None.

    Walks the MVT command stream (MoveTo / LineTo carry zigzag deltas from a
    running cursor; ClosePath carries none) without building any geometry.
    Hand-inlined varint reading: this runs over every vertex of every tile in
    a release, on a gate that must stay stdlib-only and fast.
    """
    x = y = 0
    minx = miny = maxx = maxy = None
    p = 0
    n = len(geom)
    vals: List[int] = []
    # Unpack the varints once; the command walk below is then plain indexing.
    while p < n:
        b = geom[p]
        p += 1
        if b < 0x80:
            vals.append(b)
            continue
        v = b & 0x7F
        shift = 7
        while True:
            if p >= n:
                raise ValueError("truncated geometry varint")
            b = geom[p]
            p += 1
            v |= (b & 0x7F) << shift
            if b < 0x80:
                break
            shift += 7
        vals.append(v)
    i = 0
    m = len(vals)
    while i < m:
        cmd = vals[i]
        i += 1
        cid, count = cmd & 7, cmd >> 3
        if cid == 7:  # ClosePath
            continue
        if cid not in (1, 2):
            raise ValueError(f"unknown geometry command {cid}")
        for _ in range(count):
            if i + 1 >= m:
                raise ValueError("truncated geometry parameters")
            dx = vals[i]
            dy = vals[i + 1]
            i += 2
            x += (dx >> 1) ^ -(dx & 1)
            y += (dy >> 1) ^ -(dy & 1)
            if minx is None:
                minx = maxx = x
                miny = maxy = y
                continue
            if x < minx:
                minx = x
            elif x > maxx:
                maxx = x
            if y < miny:
                miny = y
            elif y > maxy:
                maxy = y
    if minx is None:
        return None
    return minx, miny, maxx, maxy


def geometry_out_of_range(data: bytes, buffer: int = CLIP_BUFFER) -> List[Tuple[Optional[str], int, int]]:
    """Per layer of one tile: ``(name, features_out_of_range, worst_value)``.

    A feature is out of range when any vertex lies outside
    ``[-buffer, extent + buffer]`` on either axis (``extent`` is the layer's
    own). ``worst_value`` is the single coordinate furthest outside that range,
    signed as it is in the tile (0 when the layer has none). Only layers with
    at least one offending feature are listed, so an empty list is a pass.

    Stdlib only: the production gate runs this in a container with no MVT
    library installed. Raises ``ValueError`` on malformed bytes.
    """
    out: List[Tuple[Optional[str], int, int]] = []
    for _name, body in raw_layers(data):
        name, extent, feats = _layer_parts(body)
        lo, hi = -buffer, extent + buffer
        bad = 0
        worst = 0
        worst_excess = 0
        for feat in feats:
            bounds = _geometry_bounds(_feature_geometry(feat))
            if bounds is None:
                continue
            minx, miny, maxx, maxy = bounds
            here = False
            for v in (minx, miny):
                if v < lo:
                    here = True
                    if lo - v > worst_excess:
                        worst_excess, worst = lo - v, v
            for v in (maxx, maxy):
                if v > hi:
                    here = True
                    if v - hi > worst_excess:
                        worst_excess, worst = v - hi, v
            if here:
                bad += 1
        if bad:
            out.append((name, bad, worst))
    return out
