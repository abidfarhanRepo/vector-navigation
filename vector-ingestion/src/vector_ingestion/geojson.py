"""GeoJSON ingestion for vector-ingestion.

Loads a GeoJSON FeatureCollection (or a bare Feature) and normalizes every
coordinate into a contract ``Coordinate`` shape (adr-0011): longitudes are
wrapped into [-180, 180) and latitudes are range-checked. Pure,
dependency-free. Geometry handled: Point, LineString, Polygon (and their
Multi* variants via generic recursion).
"""

from dataclasses import dataclass, field
from typing import Any, List, Optional, Tuple

from .normalize import normalize_lonlat

FeatureBBox = Tuple[float, float, float, float]  # (min_lon, min_lat, max_lon, max_lat)


@dataclass
class Feature:
    """A normalized GeoJSON feature in contract-friendly form."""

    id: str
    geometry_type: str
    coordinates: Any  # list of (lon, lat) tuples, possibly nested by geometry type
    properties: dict = field(default_factory=dict)
    bbox: Optional[FeatureBBox] = None

    def to_dict(self) -> dict:
        return {
            "id": self.id,
            "geometry_type": self.geometry_type,
            "coordinates": self.coordinates,
            "properties": self.properties,
            "bbox": list(self.bbox) if self.bbox is not None else None,
        }


def _normalize_coords(coords: Any) -> Any:
    """Recursively normalize a coordinate array, preserving structure."""
    if not coords:
        return coords
    if isinstance(coords[0], (int, float)):
        lon, lat = normalize_lonlat(float(coords[0]), float(coords[1]))
        return [lon, lat]
    return [_normalize_coords(c) for c in coords]


def _geometry_bbox(coords: Any) -> Optional[FeatureBBox]:
    """Compute the (min_lon, min_lat, max_lon, max_lat) of normalized coords."""
    lons: List[float] = []
    lats: List[float] = []

    def walk(c: Any) -> None:
        if isinstance(c[0], (int, float)):
            lons.append(c[0])
            lats.append(c[1])
        else:
            for sub in c:
                walk(sub)

    try:
        walk(coords)
    except (TypeError, IndexError):
        return None
    if not lons:
        return None
    return (min(lons), min(lats), max(lons), max(lats))


def load_geojson(path: str) -> List[Feature]:
    """Load and normalize features from a GeoJSON file path."""
    import json

    with open(path, "r", encoding="utf-8") as fh:
        data = json.load(fh)

    raw = data["features"] if data.get("type") == "FeatureCollection" else [data]
    features: List[Feature] = []
    for i, f in enumerate(raw):
        geom = f.get("geometry") or {}
        gtype = geom.get("type")
        raw_coords = geom.get("coordinates")
        coords = _normalize_coords(raw_coords)
        bbox = _geometry_bbox(coords) if coords else None
        fid = f.get("id") or (f.get("properties") or {}).get("id") or f"feature-{i}"
        features.append(
            Feature(
                id=str(fid),
                geometry_type=gtype,
                coordinates=coords,
                properties=dict(f.get("properties") or {}),
                bbox=bbox,
            )
        )
    return features
