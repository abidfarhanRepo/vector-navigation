"""Drop source vertices a tile cannot distinguish, before encoding.

An MVT tile is an integer grid: `extent` units across, 4096 by default. Two
source vertices closer together than one unit project to the same tile
coordinate, so no renderer can tell them apart — but they are both still
encoded, both still transferred, and both still parsed on the device.

At street zoom that is mild waste. At country zoom it is the difference between
a usable tile and an unusable one:

| zoom | one tile spans | one unit of 4096 is |
|---|---|---|
| z6  | ~567 km  | ~138 m |
| z8  | ~142 km  | ~35 m  |
| z10 | ~35 km   | ~9 m   |
| z14 | ~2.2 km  | ~0.5 m |

OSM road geometry carries vertices every 10–100 m through curves, so at z6 a
motorway's entire shape collapses onto a handful of distinct grid points while
every original vertex is still paid for. Qatar's motorway and trunk network is
2,501 ways; unsimplified they are far too heavy for the 7 tiles that cover the
country at z6, and the feature cap that bounds tile weight then truncates the
road network itself.

**This is not lossy in any way a viewer can observe.** The vertices removed are
exactly the ones that would have been drawn on top of their predecessor. That
is why there is no tolerance parameter to tune: the tile's own resolution is
the tolerance, it is already known, and choosing anything coarser would be a
visible change rather than a free one.

What IS a judgement call, and is made deliberately here: a feature too small to
occupy a single SCREEN pixel at this zoom is dropped rather than emitted. Two
thresholds express that, and the second is the one that matters:

* a geometry that thins to fewer distinct grid points than its type needs — a
  line under one grid unit, a ring smaller than one grid cell — cannot be drawn
  at all;
* a polygon whose projected bounding box is under ``MIN_POLYGON_UNITS`` grid
  units on both axes is under one screen pixel. A tile's 4096 extent units are
  rendered across roughly 512 logical pixels, so 8 units is 1 px. Measured on
  Qatar: parks are 10,612 of the 14,026 features visible at z8, almost all of
  them traffic islands and verges, and they were most of the tile payload while
  being individually invisible.

Lines are NOT area-filtered. A road is meaningful at any zoom it survives the
class tiers at — that is what the tiers are for — and a motorway slip road that
happens to be short is not the same kind of thing as a 20 m2 verge.

Coordinates of the SURVIVING vertices are returned unchanged. This module
decides which vertices to keep; quantising them is the encoder's job
(``encode_tile`` delegates to ``mapbox-vector-tile``, which does its own), and
doing it in both places would round twice.
"""

import math
from typing import Any, List, Optional, Tuple

from .encode import EXTENT_DEFAULT

#: Smallest projected bounding-box side, in tile grid units, at which a polygon
#: is kept. 4096 extent units render across ~512 logical pixels, so 8 units is
#: one pixel. Raising it would start discarding shapes a viewer can see; the
#: threshold is derived from the render size rather than tuned against a
#: dataset, which is why it is a named constant and not an argument.
MIN_POLYGON_UNITS = 8

# Minimum number of DISTINCT grid points a geometry needs to render at all.
_MIN_POINTS = {
    "Point": 1,
    "MultiPoint": 1,
    "LineString": 2,
    "MultiLineString": 2,
    "Polygon": 3,
    "MultiPolygon": 3,
}


def _grid(lon: float, lat: float, z: int, x: int, y: int, extent: int) -> Tuple[int, int]:
    """Which integer tile cell (lon, lat) falls in.

    Deliberately the same Web-Mercator projection ``encode.lonlat_to_local``
    uses, but written out rather than imported so that a change to the
    encoder's rounding cannot silently change which vertices this module keeps.
    Both are the standard spherical Mercator forward transform; there is no
    freedom in it to disagree about.
    """
    n = 2 ** z
    x_merc = (lon + 180.0) / 360.0
    sinlat = math.sin(math.radians(lat))
    # Clamp before the log: a latitude at the pole is infinite in Mercator, and
    # a single bad vertex must not take the whole bake down.
    sinlat = max(-0.9999999, min(0.9999999, sinlat))
    y_merc = 0.5 - math.log((1.0 + sinlat) / (1.0 - sinlat)) / (4.0 * math.pi)
    return (
        int(round((x_merc - x / n) * n * extent)),
        int(round((y_merc - y / n) * n * extent)),
    )


def _thin(points: List[Any], z: int, x: int, y: int, extent: int) -> List[Any]:
    """Keep the first vertex of every run that shares one grid cell."""
    out: List[Any] = []
    last: Optional[Tuple[int, int]] = None
    for pt in points:
        cell = _grid(pt[0], pt[1], z, x, y, extent)
        if cell != last:
            out.append(pt)
            last = cell
    return out


def _thin_ring(ring: List[Any], z: int, x: int, y: int, extent: int) -> Optional[List[Any]]:
    """Thin a polygon ring, keeping it closed.

    The closing vertex repeats the first, so it is removed before thinning and
    restored after: leaving it in place would make the first and last cells
    compare equal to nothing (they are not adjacent) and then re-adding a
    closure over a thinned ring is the only way to guarantee the result is
    still a ring.
    """
    body = ring[:-1] if len(ring) > 1 and ring[0] == ring[-1] else list(ring)
    kept = _thin(body, z, x, y, extent)
    # A closed ring's first and last vertices are adjacent on the grid too, so
    # a run that wraps the seam must also collapse.
    if len(kept) > 1 and _grid(kept[0][0], kept[0][1], z, x, y, extent) == \
            _grid(kept[-1][0], kept[-1][1], z, x, y, extent):
        kept = kept[:-1]
    if len(kept) < 3:
        return None
    # Under one screen pixel on both axes: nothing a viewer at this zoom can
    # see, and most of a country-scale tile's bytes when kept.
    cells = [_grid(pt[0], pt[1], z, x, y, extent) for pt in kept]
    xs = [c[0] for c in cells]
    ys = [c[1] for c in cells]
    if (max(xs) - min(xs)) < MIN_POLYGON_UNITS and \
            (max(ys) - min(ys)) < MIN_POLYGON_UNITS:
        return None
    return kept + [kept[0]]


def simplify_geometry(geometry_type: str, coordinates: Any, z: int, x: int, y: int,
                      extent: int = EXTENT_DEFAULT) -> Optional[Any]:
    """Coordinates with tile-indistinguishable vertices removed.

    Returns ``None`` when the geometry cannot render at this zoom at all, which
    the caller should treat as "drop this feature". Points are always returned
    unchanged: a point has no redundant vertices, and dropping one because it
    shares a cell with another feature's point would be a different decision
    (label collision) made in the wrong place.
    """
    need = _MIN_POINTS.get(geometry_type)
    if need is None:
        return coordinates          # unknown type: pass through untouched
    if geometry_type in ("Point", "MultiPoint"):
        return coordinates

    if geometry_type == "LineString":
        kept = _thin(coordinates, z, x, y, extent)
        return kept if len(kept) >= 2 else None

    if geometry_type == "MultiLineString":
        parts = [_thin(line, z, x, y, extent) for line in coordinates]
        parts = [p for p in parts if len(p) >= 2]
        return parts or None

    if geometry_type == "Polygon":
        rings = [_thin_ring(r, z, x, y, extent) for r in coordinates]
        # The outer ring is ring 0. If it collapses the polygon is gone, holes
        # and all; a surviving hole with no shell is not a shape.
        if not rings or rings[0] is None:
            return None
        return [r for r in rings if r is not None]

    if geometry_type == "MultiPolygon":
        polys = []
        for poly in coordinates:
            rings = [_thin_ring(r, z, x, y, extent) for r in poly]
            if not rings or rings[0] is None:
                continue
            polys.append([r for r in rings if r is not None])
        return polys or None

    return coordinates
