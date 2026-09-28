"""Re-export routing error types from the shared vector_geo package.

The implementation lives in vendor/vector_geo (single source of truth); this
shim keeps the historical ``vector_routing.errors`` import surface stable.
"""

from vector_geo.errors import EndpointTooFarError, NoRouteError, RouteError  # noqa: F401
