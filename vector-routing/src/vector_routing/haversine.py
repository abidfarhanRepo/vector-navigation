"""Re-export haversine helpers from the shared vector_geo package.

The implementation lives in vendor/vector_geo (single source of truth); this
shim keeps the historical ``vector_routing.haversine`` import surface stable.
"""

from vector_geo.haversine import (  # noqa: F401
    haversine_meters,
    haversine_meters_coord,
)
