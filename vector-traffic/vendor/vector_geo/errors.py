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
