"""Self-hosted geocoder for Vector (no third-party geocoding service).

Indexes real OSM-derived place labels and named roads from a basemap
GeoJSON and answers prefix/substring queries with GeoJSON Point features.
This keeps address/POI search fully self-hosted (no Nominatim/Google).
"""

from .index import GeocodeIndex, GeocodeHit
from .serve import GeocodeService

__all__ = ["GeocodeIndex", "GeocodeHit", "GeocodeService"]
