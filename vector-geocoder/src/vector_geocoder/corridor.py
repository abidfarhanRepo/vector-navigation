"""Corridor POI search: named places within `radius_m` of a route polyline.

"Find fuel / food on my way": given the route's LineString, return the
named index entries (the same store that powers /search and /reverse)
whose point sits within a corridor around it. Stdlib only, matching the
rest of this package.

Design notes:
- Distance is point-to-VERTEX haversine over the route geometry, sampled.
  Route vertices are dense enough (~10 m urban) that vertex distance
  upper-bounds true segment distance by a negligible margin; a full
  segment-crossproduct would triple the cost for no visible difference
  at corridor radii of 100-1000 m.
- The caller supplies the geometry; this module never talks to OSRM. The
  routing engine stays the single source of paths, the geocoder stays the
  single source of places.
"""

import math
from typing import Any, Dict, List, Sequence

from .kinds import poi_kind, resolve_kind

CorridorPoint = tuple  # (lon, lat)


def _haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    r = 6371000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dl = math.radians(lon2 - lon1)
    x = (math.sin(dphi / 2) ** 2
         + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2)
    return 2 * r * math.asin(min(1.0, math.sqrt(x)))


def _sample(line: Sequence[Sequence[float]], max_step_m: float = 50.0) -> List[CorridorPoint]:
    """Densify a polyline so vertex sampling cannot miss corridor hits.

    Long rural legs (motorway ramps can be 500 m+ between vertices) get
    intermediate points every ~max_step_m so a 300 m corridor has no holes.
    """
    out: List[CorridorPoint] = []
    pts = [(float(c[0]), float(c[1])) for c in line]
    if not pts:
        return out
    out.append(pts[0])
    for prev, cur in zip(pts, pts[1:]):
        d = _haversine_m(prev[1], prev[0], cur[1], cur[0])
        steps = int(d / max_step_m)
        for k in range(1, steps + 1):
            t = k / (steps + 1)
            out.append((prev[0] + (cur[0] - prev[0]) * t,
                        prev[1] + (cur[1] - prev[1]) * t))
        out.append(cur)
    return out


def corridor_hits(index, line: Sequence[Sequence[float]], radius_m: float = 400.0,
                  kinds: Sequence[str] = (), limit: int = 20) -> List[Dict[str, Any]]:
    """Named entries within ``radius_m`` of the polyline.

    ``index`` is a :class:`vector_geocoder.index.GeocodeIndex` — reused, not
    duplicated, so anything searchable also works along-route. ``kinds``
    filters on the feature kind (e.g. fuel, restaurant, cafe); empty means
    all kinds. Results carry ``detour_m`` (approximate: nearest sample
    vertex) and are sorted by it.
    """
    samples = _sample(line)
    if not samples or radius_m <= 0:
        return []
    want = {k.strip().casefold() for k in kinds if k.strip()}
    want_cats = {c for k in want for c in resolve_kind(k)}
    lats = [p[1] for p in samples]
    lons = [p[0] for p in samples]

    # Coarse bbox rejection before any haversine work: with ~8k POIs and
    # ~600 samples, skipping the bbox makes each request O(N*S).
    min_lat, max_lat = min(lats) - radius_m / 111_320.0, max(lats) + radius_m / 111_320.0
    coslat = math.cos(math.radians((min_lat + max_lat) / 2))
    min_lon = min(lons) - radius_m / (111_320.0 * max(coslat, 0.1))
    max_lon = max(lons) + radius_m / (111_320.0 * max(coslat, 0.1))

    hits = []
    for h in getattr(index, "_hits", []):
        if not (min_lat <= h.lat <= max_lat and min_lon <= h.lon <= max_lon):
            continue
        if want and poi_kind(h.raw) not in want_cats and (h.kind or "").casefold() not in want:
            # Category match first (roads carry none); user kinds map to the
            # Overture categories they cover via vector_geocoder.kinds.
            continue
        best = min(_haversine_m(h.lat, h.lon, p[1], p[0]) for p in samples)
        if best <= radius_m:
            f = h.to_geojson()
            f["properties"]["detour_m"] = round(best, 1)
            hits.append(f)
    hits.sort(key=lambda f: f["properties"]["detour_m"])
    return hits[:limit] if limit > 0 else hits
