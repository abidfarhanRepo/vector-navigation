"""Fill zoom gaps in the baked tile set for the Doha metro area.

The existing bake covers z8-16 but has holes at low zooms around Doha
center (z8-12 partial grids). This re-bakes ONLY the missing tiles at
z8-12 for a generous Doha bounding box, merging into the existing tile
tree on disk. Uses the same one-pass builder as the original bake.
"""
import math
import os
import subprocess
import sys

ROOT = "/home/armstice/Development/Vector"
TILES = os.environ.get("TILE_DIR", "/home/armstice/Development/Vector/_dev/tiles_fill")
GEO = f"{ROOT}/vector-osrm/data/qatar.geojson"

# Doha metro bbox (generous): lon 51.35..51.75, lat 25.15..25.45
LON0, LAT1, LON1, LAT0 = 51.30, 25.50, 51.80, 25.10

def tile_xy(lat, lon, z):
    n = 2 ** z
    x = int((lon + 180) / 360 * n)
    y = int((1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * n)
    return x, y

missing_by_zoom = {}
for z in range(8, 13):
    x0, y0 = tile_xy(LAT1, LON0, z)   # NW corner
    x1, y1 = tile_xy(LAT0, LON1, z)   # SE corner
    missing = []
    for x in range(x0, x1 + 1):
        for y in range(y0, y1 + 1):
            if not os.path.isfile(f"{TILES}/{z}/{x}/{y}.mvt"):
                missing.append((x, y))
    missing_by_zoom[z] = missing
    print(f"z{z}: {len(missing)} missing of {(x1-x0+1)*(y1-y0+1)}")

total = sum(len(v) for v in missing_by_zoom.values())
print("total missing:", total)
if total == 0:
    print("NOTHING TO BAKE")
    sys.exit(0)
