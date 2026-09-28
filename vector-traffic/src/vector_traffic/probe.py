"""Probe (crowdsourced GPS sample) model + normalization for vector-traffic."""

from dataclasses import dataclass
from typing import List, Optional

from .errors import InputError


@dataclass
class Probe:
    lon: float
    lat: float
    speed_kmh: Optional[float] = None
    timestamp: Optional[float] = None
    heading_deg: Optional[float] = None


def _as_float(value, what: str) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        raise InputError(f"{what} must be numeric") from None


def normalize_probe(d: dict) -> Probe:
    """Build a ``Probe`` from a dict.

    Accepts coordinates from ``d['lon'], d['lat']`` OR ``d['coordinates']``
    (GeoJSON ``[lon, lat]``) OR nested ``d['geometry']['coordinates']``.
    Speed is read from ``d['speed']`` / ``d['speed_kmh']``. Raises ``InputError``
    when lon/lat are missing or non-numeric.
    """
    lon = None
    lat = None
    if "lon" in d and "lat" in d:
        lon, lat = d["lon"], d["lat"]
    elif isinstance(d.get("coordinates"), (list, tuple)) and len(d["coordinates"]) >= 2:
        lon, lat = d["coordinates"][0], d["coordinates"][1]
    elif isinstance(d.get("geometry"), dict):
        gc = d["geometry"].get("coordinates")
        if isinstance(gc, (list, tuple)) and len(gc) >= 2:
            lon, lat = gc[0], gc[1]

    if lon is None or lat is None:
        raise InputError("probe missing lon/lat coordinates")
    lon = _as_float(lon, "lon")
    lat = _as_float(lat, "lat")

    speed = None
    if d.get("speed") is not None:
        speed = _as_float(d["speed"], "speed")
    elif d.get("speed_kmh") is not None:
        speed = _as_float(d["speed_kmh"], "speed_kmh")

    timestamp = d.get("timestamp")
    if timestamp is not None:
        timestamp = _as_float(timestamp, "timestamp")
    heading = d.get("heading_deg")
    if heading is not None:
        heading = _as_float(heading, "heading_deg")

    return Probe(lon=lon, lat=lat, speed_kmh=speed, timestamp=timestamp, heading_deg=heading)


def load_probes(geojson: dict) -> List[Probe]:
    """Load probes from a GeoJSON ``FeatureCollection`` of ``Point`` features."""
    features = geojson.get("features", [])
    out: List[Probe] = []
    for f in features:
        geom = f.get("geometry") or {}
        props = f.get("properties") or {}
        coords = geom.get("coordinates") if geom.get("type") == "Point" else None
        rec = {
            "geometry": {"type": "Point", "coordinates": coords},
            "speed": props.get("speed"),
            "speed_kmh": props.get("speed_kmh"),
            "timestamp": props.get("timestamp"),
            "heading_deg": props.get("heading_deg"),
        }
        out.append(normalize_probe(rec))
    return out
