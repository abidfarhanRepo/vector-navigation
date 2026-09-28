#!/usr/bin/env python3
"""Decode the two committed West Bay tiles and print what they contain.

These are the bytes the live deployment served on 2026-09-24, release
``vector-tiles-2026-09-24T1733Z-e039746``. Run this to check Vector's claims
about its 3D basemap and its lane markings yourself rather than taking the
README's word for it:

    pip install mapbox-vector-tile
    python3 docs/evidence/verify_tiles.py

Each tile's sha256 is printed too, so you can compare the committed bytes with
what your own deployment serves for the same z/x/y.
"""
import hashlib
import pathlib
import sys
from collections import Counter

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[2] / "vector-tile-gen" / "src"))
from vector_tile_gen.encode import decode_tile  # noqa: E402

HERE = pathlib.Path(__file__).resolve().parent / "tiles"

TILES = (
    ("z15  15/21074/14000", "westbay-z15-21074-14000.mvt"),
    ("z14  14/10537/7000", "westbay-z14-10537-7000.mvt"),
)


def census(counter: Counter) -> dict:
    """Most common first, so the printed block is stable between runs."""
    return dict(counter.most_common())


for name, filename in TILES:
    data = (HERE / filename).read_bytes()
    layers = decode_tile(data)["layers"]
    basemap = next((L for L in layers if L["name"] == "basemap"), {"features": []})
    feats = basemap["features"]

    print(f"\n=== West Bay, Doha — {name} ({len(data):,} bytes) ===")
    print(f"  sha256   : {hashlib.sha256(data).hexdigest()}")
    print("  layers   : " + ", ".join(f"{L['name']} {len(L['features'])}" for L in layers))
    print(f"  features : {len(feats)}  {census(Counter(f['properties'].get('kind', '?') for f in feats))}")

    bld = [f for f in feats if f["properties"].get("kind") == "building"]
    heights = [h for h in (f["properties"].get("height_m") for f in bld) if h]
    levels = [l for l in (f["properties"].get("building_levels") for f in bld) if l]
    print(f"  buildings: {len(bld)}")
    if heights:
        print(f"  with an explicit height_m: {len(heights)}")
        print(f"  tallest: {sorted(heights)[-6:]} m")
    if levels:
        print(f"  with building_levels     : {len(levels)}, up to {max(int(x) for x in levels)} floors")

    # V8: the last stretch of a wider way at a lane-count step is cut into
    # nested pieces carrying a fractional lane count in `lw`.
    roads = [f for f in feats if f["properties"].get("kind") == "road"]
    tapered = [f for f in roads if f["properties"].get("lw") is not None]
    print(f"  taper    : {len(tapered)} of {len(roads)} road features carry a fractional `lw`")

    # V8: the lane dividers are their own layer; nothing else reads it.
    lanes = next((L for L in layers if L["name"] == "lanes"), None)
    if lanes is None:
        print("  lanes    : none in this tile")
    else:
        lf = lanes["features"]
        counts = sorted({int(f["properties"]["n"]) for f in lf if f["properties"].get("n")})
        print(f"  lanes    : {len(lf)} dividers "
              f"{census(Counter(f['properties'].get('cls', '?') for f in lf))}, "
              f"marking {counts} lanes")

print("""
Note the honesty constraint that applies nationally: only 1,929 of 228,872
building instances (0.84%) carry an explicit height. West Bay is the dense
exception. This is a 3D basemap, not a height survey.

The `lanes` layer is a VISUAL layer only: pre-offset dividers trimmed back
from junctions, drawn by the style. It carries no routing or guidance meaning,
and it appears on the z15 tiles of this set, not on z13/z14.
""")
