"""Tile pipeline for vector-tile-gen.

Reads normalized features (the contract shape produced by vector-ingestion and
stored by vector-map-store) and emits a single MVT tile (adr-0013). The caller
is responsible for feature extraction/storage; this module only selects the
features intersecting a tile and encodes them. Pure, dependency-free.
"""

from typing import Any, List, Tuple

from .encode import EXTENT_DEFAULT, encode_tile, tile_bbox

FeatureBBox = Tuple[float, float, float, float]


def _intersects(a: FeatureBBox, b: FeatureBBox) -> bool:
    return not (a[2] < b[0] or b[2] < a[0] or a[3] < b[1] or b[3] < a[1])


def _as_feature_dict(feat: Any, z: int, x: int, y: int) -> dict:
    return {
        "id": feat.id,
        "geometry_type": feat.geometry_type,
        "coordinates": feat.coordinates,
        "properties": getattr(feat, "properties", {}) or {},
        "_z": z,
        "_x": x,
        "_y": y,
    }


def generate_tile(features: List[Any], z: int, x: int, y: int,
                  extent: int = EXTENT_DEFAULT,
                  layer_name: str = "vector") -> bytes:
    """Select ``features`` intersecting tile (z, x, y) and encode an MVT tile."""
    tbox = tile_bbox(z, x, y)
    selected = [f for f in features if f.bbox and _intersects(f.bbox, tbox)]
    feature_dicts = [_as_feature_dict(f, z, x, y) for f in selected]
    return encode_tile([(layer_name, feature_dicts)], extent=extent)
