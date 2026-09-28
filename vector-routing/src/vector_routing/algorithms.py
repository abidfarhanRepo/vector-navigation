"""Re-export shortest-path algorithms from the shared vector_geo package.

The implementation lives in vendor/vector_geo (single source of truth); this
shim keeps the historical ``vector_routing.algorithms`` import surface stable.
"""

from vector_geo.algorithms import (  # noqa: F401
    astar,
    build_graph_from_features,
    dijkstra,
    Route,
)
