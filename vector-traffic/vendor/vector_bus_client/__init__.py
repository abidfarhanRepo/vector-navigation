"""Shared event-bus client for Vector Python engines.

Single source of truth (ADR-0007), vendored into consuming engines.
See ``client.py`` for the implementations.
"""

from .client import (
    AckFn,
    Handler,
    LocalBus,
    NetworkBusClient,
    create_bus,
)

__all__ = [
    "AckFn",
    "Handler",
    "LocalBus",
    "NetworkBusClient",
    "create_bus",
]
