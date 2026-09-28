"""Great-circle distance helpers shared across Vector Python engines.

All coordinates are (longitude, latitude) tuples in decimal degrees unless noted.
"""

import math
from typing import Dict, Tuple, Union

EARTH_RADIUS_M = 6371008.8


def haversine_meters(a: Tuple[float, float], b: Tuple[float, float]) -> float:
    """Return the great-circle distance in meters between two (lon, lat) points.

    Uses the standard haversine formula with mean Earth radius 6371008.8 m.
    """
    lon1, lat1 = float(a[0]), float(a[1])
    lon2, lat2 = float(b[0]), float(b[1])
    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlmb = math.radians(lon2 - lon1)
    h = math.sin(dphi / 2.0) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(dlmb / 2.0) ** 2
    return 2.0 * EARTH_RADIUS_M * math.asin(math.sqrt(h))


def _as_coord(c: Union[Tuple[float, float], Dict[str, float]]) -> Tuple[float, float]:
    if isinstance(c, dict):
        return (float(c["lon"]), float(c["lat"]))
    return (float(c[0]), float(c[1]))


def haversine_meters_coord(
    a: Union[Tuple[float, float], Dict[str, float]],
    b: Union[Tuple[float, float], Dict[str, float]],
) -> float:
    """Convenience wrapper accepting either (lon, lat) tuples or dicts.

    Accepted dict shapes: ``{"lon": .., "lat": ..}`` or
    ``{"type": "Coordinate", "lon": .., "lat": ..}``.
    """
    return haversine_meters(_as_coord(a), _as_coord(b))
