"""Generate real MVT vector-tile fixtures for the tile-server test suite.

Why: `src/main.rs` tests load committed `.mvt` tiles from
`tests/fixtures/tiles/{z}/{x}/{y}.mvt` and assert the MVT wire format
(field 3 = repeated Layer, tag byte 0x1A). The fixtures must be REAL decoded
tiles (not opaque placeholders), so this script encodes a minimal but valid
Mapbox Vector Tile (v2.1) with genuine decoded geometry.

It produces a small but geographically meaningful set of tiles covering a
Qatar/Doha AOI at z12 (the test exercise set), plus a couple at z10/z14 so the
serve path is exercised across zoom levels.

MVT wire layout (protoc, field numbers from the spec):
  Tile   { repeated Layer layers = 3; }
  Layer  { uint32 version=15; string name=1; repeated Feature features=2;
           repeated string keys=3; repeated Value values=4; uint32 extent=5; }
  Feature{ uint64 id=1; repeated uint32 tags=2; GeomType type=3;
           repeated uint32 geometry=4; }
  (GeomType: UNKNOWN=0 POINT=1 LINESTRING=2 POLYGON=3)
  Value  { optional string string_value=1; ... }

Geometry encoding (command-interleaved):
  MoveTo  = 1, LineTo = 2, ClosePath = 7  (command << 3 | (count & 0x7)).
  Coordinates are zigzag-delta encoded: (dx, dy) -> zigzag((dx<<1)^(dx>>31)).
"""

from __future__ import annotations

import math
import os
import struct


# --- minimal protobuf primitives -------------------------------------------
def _varint(n: int) -> bytes:
    n &= (1 << 64) - 1
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


def _tag(field: int, wire: int) -> bytes:
    return _varint((field << 3) | wire)


def _field_uint(field: int, n: int) -> bytes:
    return _tag(field, 0) + _varint(n)


def _field_str(field: int, s: str) -> bytes:
    b = s.encode("utf-8")
    return _tag(field, 2) + _varint(len(b)) + b


def _field_bytes(field: int, b: bytes) -> bytes:
    return _tag(field, 2) + _varint(len(b)) + b


def _zigzag(n: int) -> int:
    return (n << 1) ^ (n >> 63) if n < 0 else (n << 1)


# --- geometry helpers -------------------------------------------------------
def _cmd(cmd_id: int, count: int) -> int:
    return (cmd_id & 0x7) | (count << 3)


def _encode_geom(geom_type: int, coords: list[tuple[int, int]]) -> list[int]:
    """coords are integer (already scaled to tile extent) (x, y)."""
    out: list[int] = []
    if geom_type == 1:  # Point
        out.append(_cmd(1, len(coords)))  # MoveTo
        cx = cy = 0
        for (x, y) in coords:
            out.append(_zigzag(x - cx))
            out.append(_zigzag(y - cy))
            cx, cy = x, y
    elif geom_type == 2:  # LineString (single ring of points)
        out.append(_cmd(1, 1))  # MoveTo first
        cx, cy = coords[0]
        out.append(_zigzag(cx))
        out.append(_zigzag(cy))
        out.append(_cmd(2, len(coords) - 1))  # LineTo rest
        for (x, y) in coords[1:]:
            out.append(_zigzag(x - cx))
            out.append(_zigzag(y - cy))
            cx, cy = x, y
    elif geom_type == 3:  # Polygon (closed ring)
        ring = list(coords) + [coords[0]]
        out.append(_cmd(1, 1))
        cx, cy = ring[0]
        out.append(_zigzag(cx))
        out.append(_zigzag(cy))
        out.append(_cmd(2, len(ring) - 1))
        for (x, y) in ring[1:]:
            out.append(_zigzag(x - cx))
            out.append(_zigzag(y - cy))
            cx, cy = x, y
        out.append(_cmd(7, 1))  # ClosePath
    return out


# --- lat/lon -> tile coordinate --------------------------------------------
TILE_EXTENT = 4096


def lonlat_to_tile_xy(lon: float, lat: float, z: int, x0: int, y0: int) -> tuple[int, int]:
    n = 2 ** z
    # tile-bounds in lon/lat
    lon_min = x0 / n * 360.0 - 180.0
    lat_max = math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * y0 / n))))
    lat_min = math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * (y0 + 1) / n))))
    fx = (lon - lon_min) / (360.0 / n)
    fy = (lat_max - lat) / (lat_max - lat_min)
    return int(round(fx * TILE_EXTENT)), int(round(fy * TILE_EXTENT))


def make_feature(fid: int, geom_type: int, coords: list[tuple[int, int]],
                 tags: list[int]) -> bytes:
    body = _field_uint(1, fid)
    if tags:
        body += _tag(2, 2) + _varint(len(tags)) + b"".join(_varint(t) for t in tags)
    body += _field_uint(3, geom_type)
    geom = _encode_geom(geom_type, coords)
    body += _field_bytes(4, b"".join(_varint(g) for g in geom))
    return _tag(2, 2) + _varint(len(body)) + body


def make_layer(name: str, features: list[bytes],
               keys: list[str], values: list[str]) -> bytes:
    body = _field_str(1, name)
    for f in features:
        body += f
    for k in keys:
        body += _field_str(3, k)
    for v in values:
        body += _field_str(4, v)  # string_value field 1 inside Value
    body += _field_uint(5, TILE_EXTENT)
    body += _field_uint(15, 2)  # version 2
    return _tag(3, 2) + _varint(len(body)) + body


def make_tile(layers: list[bytes]) -> bytes:
    body = b"".join(layers)
    return _tag(3, 2) + _varint(len(body)) + body


# --- fixture definitions (Doha AOI) -----------------------------------------
# (lon, lat) waypoints for a couple of roads + a building polygon + a POI.
DOA_CORNICE = [
    (51.5050, 25.2930), (51.5070, 25.2950), (51.5090, 25.2968), (51.5110, 25.2985),
]
SALWA_RD = [
    (51.5000, 25.2600), (51.5050, 25.2620), (51.5100, 25.2640), (51.5150, 25.2660),
]
BUILDING = [
    (51.5060, 25.2900), (51.5068, 25.2900), (51.5068, 25.2908), (51.5060, 25.2908),
]
POI = [(51.5080, 25.2945)]


def build_layer_for_tile(z: int, x: int, y: int) -> bytes:
    def to_xy(ll):
        return lonlat_to_tile_xy(ll[0], ll[1], z, x, y)

    corniche = make_feature(1, 2, [to_xy(p) for p in DOA_CORNICE], [0, 1])
    salwa = make_feature(2, 2, [to_xy(p) for p in SALWA_RD], [2, 1])
    building = make_feature(3, 3, [to_xy(p) for p in BUILDING], [3, 1])
    poi = make_feature(4, 1, [to_xy(p) for p in POI], [4, 1])
    keys = ["name", "kind", "highway", "ref", "amenity"]
    values = ["Corniche", "road", "primary", "B123", "cafe"]
    return make_layer(
        "basemap",
        [corniche, salwa, building, poi],
        keys,
        values,
    )


# Tile coordinates at z12 covering Doha (lon~51.5, lat~25.29).
def lonlat_to_tilexy(lon: float, lat: float, z: int) -> tuple[int, int]:
    n = 2 ** z
    xt = int((lon + 180.0) / 360.0 * n)
    lat_rad = math.radians(lat)
    yt = int((1 - math.log(math.tan(lat_rad) + 1 / math.cos(lat_rad)) / math.pi) / 2 * n)
    return xt, yt


def main() -> None:
    root = os.path.join(os.path.dirname(__file__), "..", "tests", "fixtures", "tiles")
    root = os.path.abspath(root)
    tiles = [
        (12, *lonlat_to_tilexy(51.5070, 25.2950, 12)),
        (12, *lonlat_to_tilexy(51.5050, 25.2620, 12)),
        (10, *lonlat_to_tilexy(51.5070, 25.2950, 10)),
        (14, *lonlat_to_tilexy(51.5070, 25.2950, 14)),
    ]
    for (z, x, y) in set(tiles):
        layer = build_layer_for_tile(z, x, y)
        tile = make_tile([layer])
        assert tile[0] == 0x1A, "MVT tile must start with layer tag 0x1A"
        out_dir = os.path.join(root, str(z), str(x))
        os.makedirs(out_dir, exist_ok=True)
        out_path = os.path.join(out_dir, f"{y}.mvt")
        with open(out_path, "wb") as fh:
            fh.write(tile)
        print(f"wrote {os.path.relpath(out_path)} ({len(tile)} bytes)")


if __name__ == "__main__":
    main()
