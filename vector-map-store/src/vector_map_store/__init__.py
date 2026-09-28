"""vector-map-store — feature persistence (in-memory or PostGIS backend)."""

from .geometry import bbox_contains
from .store import (
    FeatureStore,
    FeatureBBox,
    MemoryFeatureStore,
    PostGISFeatureStore,
    StoredFeature,
    get_store,
)

__all__ = [
    "bbox_contains",
    "FeatureStore",
    "FeatureBBox",
    "MemoryFeatureStore",
    "PostGISFeatureStore",
    "StoredFeature",
    "get_store",
]
