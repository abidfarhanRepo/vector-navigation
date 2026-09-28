"""vector_geo — shared geospatial domain models for the Vector Python tier.

This package is the single source of truth for geometry, great-circle
distance, graph, shortest-path, and error types that were previously
copy-pasted across the Python engines (notably vector-routing and
vector-logistics carried byte-identical copies). It is stdlib-only so it can
be vendored/submoduled into any engine without adding runtime dependencies.

ADR-0007 intended vector-common to own shared domain models, but
vector-common is TypeScript; vector_geo is the Python counterpart.
"""

from .haversine import haversine_meters, haversine_meters_coord
from .graph import RoutingGraph
from .algorithms import dijkstra, astar, build_graph_from_features, Route
from .errors import RouteError, NoRouteError

__all__ = [
    "haversine_meters",
    "haversine_meters_coord",
    "RoutingGraph",
    "dijkstra",
    "astar",
    "build_graph_from_features",
    "Route",
    "RouteError",
    "NoRouteError",
]
