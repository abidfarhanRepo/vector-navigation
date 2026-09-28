"""Traffic errors for vector-traffic."""

from typing import Optional


class TrafficError(Exception):
    """Base class for all traffic errors."""

    def __init__(self, message: str = "traffic error", *, cause: Optional[BaseException] = None):
        super().__init__(message)
        self.message = message
        self.cause = cause


class InputError(TrafficError):
    """Raised when input features/coordinates are malformed."""


class MapMatchError(TrafficError):
    """Raised when a probe cannot be map-matched within the match radius."""
