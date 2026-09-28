"""Minimal Web-Mercator tile math for vector-tile-gen.

Pure, dependency-free domain helper committed ahead of the tile render
pipeline (adr-0013).
"""

import math


def lonlat_to_tile(z: int, lon: float, lat: float):
    """Return the (x, y) slippy-map tile for a zoom/lon/lat."""
    n = 2 ** z
    x = int((lon + 180.0) / 360.0 * n)
    lat_rad = math.radians(lat)
    y = int((1.0 - math.asinh(math.tan(lat_rad)) / math.pi) / 2.0 * n)
    x = max(0, min(n - 1, x))
    y = max(0, min(n - 1, y))
    return (x, y)


if __name__ == "__main__":
    print(lonlat_to_tile(0, 0.0, 0.0))
