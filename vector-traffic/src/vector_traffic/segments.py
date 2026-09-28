"""Road segment model + normalization for vector-traffic."""

from dataclasses import dataclass
from typing import List, Tuple

from .errors import InputError


@dataclass
class RoadSegment:
    id: str
    geometry: List[Tuple[float, float]]
    free_flow_kmh: float


def _as_float(value, what: str) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        raise InputError(f"{what} must be numeric") from None


def normalize_segment(feature: dict, index: int = 0) -> RoadSegment:
    """Build a ``RoadSegment`` from a GeoJSON ``LineString`` feature.

    ``id`` comes from ``properties['id']`` or the feature index (stringified).
    ``free_flow_kmh`` comes from ``properties['free_flow_kmh']`` or
    ``properties['maxspeed']`` or defaults to ``50.0``.
    """
    geom = feature.get("geometry") or {}
    if geom.get("type") != "LineString":
        raise InputError("segment geometry must be a LineString")
    coords = geom.get("coordinates") or []
    geometry: List[Tuple[float, float]] = [(float(c[0]), float(c[1])) for c in coords]
    if len(geometry) < 2:
        raise InputError("segment geometry must have at least 2 points")

    props = feature.get("properties") or {}
    seg_id = props.get("id")
    seg_id = str(index) if seg_id is None else str(seg_id)

    free_flow = props.get("free_flow_kmh")
    if free_flow is None:
        free_flow = props.get("maxspeed")
    if free_flow is None:
        free_flow = 50.0
    free_flow = _as_float(free_flow, "free_flow_kmh")
    if free_flow <= 0:
        raise InputError("free_flow_kmh must be positive")

    return RoadSegment(id=seg_id, geometry=geometry, free_flow_kmh=free_flow)


def load_segments(geojson: dict) -> List[RoadSegment]:
    """Load segments from a GeoJSON ``FeatureCollection`` of ``LineString`` features."""
    features = geojson.get("features", [])
    return [normalize_segment(f, i) for i, f in enumerate(features)]
