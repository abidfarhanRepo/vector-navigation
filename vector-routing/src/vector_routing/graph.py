"""Re-export RoutingGraph from the shared vector_geo package.

The implementation lives in vendor/vector_geo (single source of truth); this
shim keeps the historical ``vector_routing.graph`` import surface stable.
"""

from vector_geo.graph import RoutingGraph  # noqa: F401
