"""Shared routing/geo error types for the Vector Python tier."""

from typing import Optional


class RouteError(Exception):
    """Base class for all routing errors."""

    def __init__(self, message: str = "route error", *, cause: Optional[BaseException] = None):
        super().__init__(message)
        self.message = message
        self.cause = cause


class NoRouteError(RouteError):
    """Raised when no path exists between the requested origin and destination."""

    def __init__(self, source: str = "", target: str = ""):
        detail = ""
        if source or target:
            detail = f" (source={source}, target={target})"
        super().__init__("no route found" + detail)


class EndpointTooFarError(RouteError):
    """Raised when an endpoint is further from the road network than we will move it.

    Distinct from :class:`NoRouteError`: the graph may be perfectly connected and
    the roads perfectly fine -- the requested point is simply not near any of
    them. Conflating the two sends the reader hunting for a connectivity bug.
    """

    def __init__(self, lon: float, lat: float, distance_m: float, limit_m: float):
        self.lon = lon
        self.lat = lat
        self.distance_m = distance_m
        self.limit_m = limit_m
        super().__init__(
            f"endpoint {lat:.5f},{lon:.5f} is {distance_m:.0f} m from the nearest "
            f"road (limit {limit_m:.0f} m) - it is outside the routable area"
        )
