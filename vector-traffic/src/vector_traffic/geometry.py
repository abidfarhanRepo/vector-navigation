"""Geometry helpers for vector-traffic (stdlib-only).

Great-circle and local planar helpers used by the map-matching and aggregation
engine. The haversine implementation follows the documented Vector Lesson
preference for a clear, numerically stable great-circle distance.

All functions operate on ``(lon, lat)`` coordinate tuples (degrees).
"""

import math
from typing import List, Sequence, Tuple

from vector_geo.haversine import haversine_meters, EARTH_RADIUS_M

Coord = Tuple[float, float]

# Canonical great-circle distance + Earth radius now come from vector_geo
# (single source of truth, ADR-0007). Alias kept for any external reference.
EARTH_R_M = EARTH_RADIUS_M
M_PER_DEG_LAT = 111320.0


def bearing_deg(a: Coord, b: Coord) -> float:
    """Initial bearing in degrees (0=N, 90=E) from ``a`` to ``b``."""
    lon1, lat1 = float(a[0]), float(a[1])
    lon2, lat2 = float(b[0]), float(b[1])
    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    dlam = math.radians(lon2 - lon1)
    y = math.sin(dlam) * math.cos(phi2)
    x = math.cos(phi1) * math.sin(phi2) - math.sin(phi1) * math.cos(phi2) * math.cos(dlam)
    return math.degrees(math.atan2(y, x)) % 360.0


def point_to_segment_distance_m(p: Coord, a: Coord, b: Coord) -> float:
    """Approximate perpendicular distance (meters) from point ``p`` to segment ``a``-``b``.

    Uses an equirectangular projection around the segment midpoint, scaling
    longitude by ``cos(lat)``. Deterministic.
    """
    mid_lat = (float(a[1]) + float(b[1])) / 2.0
    coslat = math.cos(math.radians(mid_lat))

    def proj(pt: Coord):
        return (float(pt[0]) * coslat * M_PER_DEG_LAT, float(pt[1]) * M_PER_DEG_LAT)

    px, py = proj(p)
    ax, ay = proj(a)
    bx, by = proj(b)
    dx = bx - ax
    dy = by - ay
    seg2 = dx * dx + dy * dy
    if seg2 == 0.0:
        return math.hypot(px - ax, py - ay)
    t = ((px - ax) * dx + (py - ay) * dy) / seg2
    t = max(0.0, min(1.0, t))
    cx = ax + t * dx
    cy = ay + t * dy
    return math.hypot(px - cx, py - cy)
