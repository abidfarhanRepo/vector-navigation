"""Feature persistence for vector-map-store.

Two interchangeable backends behind one interface:

* ``MemoryFeatureStore`` — the original dependency-free S0 stand-in
  (in-memory + optional GeoJSON file persistence). Used when no
  ``VECTOR_PG_DSN`` is configured (default, including CI isolation).
* ``PostGISFeatureStore`` — the production target (Architecture §6): a
  Vector-owned PostGIS authoring store with a spatial ``features`` table,
  GIST index, and envelope-based bbox queries.

``FeatureStore(dsn=...)`` is a factory: pass a DSN (or set ``VECTOR_PG_DSN``)
to get the PostGIS backend, otherwise the in-memory backend. ``get_store()``
reads the environment for convenience.

The interface (insert / all / count / query_bbox / save / load) is identical
across backends; ``test_postgis.py`` asserts behavioral parity when a live
PostGIS is available (skipped otherwise).
"""

import json
import os
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Tuple

from .geometry import bbox_contains

FeatureBBox = Tuple[float, float, float, float]  # (min_lon, min_lat, max_lon, max_lat)
DEFAULT_DSN_ENV = "VECTOR_PG_DSN"


@dataclass
class StoredFeature:
    id: str
    geometry_type: str
    coordinates: Any
    properties: dict = field(default_factory=dict)
    bbox: Optional[FeatureBBox] = None

    def to_dict(self) -> dict:
        return {
            "id": self.id,
            "geometry_type": self.geometry_type,
            "coordinates": self.coordinates,
            "properties": self.properties,
            "bbox": list(self.bbox) if self.bbox is not None else None,
        }


def _feature_from_dict(d: Dict[str, Any]) -> StoredFeature:
    bbox = d.get("bbox")
    return StoredFeature(
        id=d["id"],
        geometry_type=d["geometry_type"],
        coordinates=d["coordinates"],
        properties=d.get("properties", {}),
        bbox=tuple(bbox) if bbox else None,
    )


class MemoryFeatureStore:
    """In-memory feature store with bbox queries and GeoJSON persistence."""

    def __init__(self) -> None:
        self._features: List[StoredFeature] = []

    def insert(self, feature: Any) -> None:
        self._features.append(
            StoredFeature(
                id=feature.id,
                geometry_type=feature.geometry_type,
                coordinates=feature.coordinates,
                properties=dict(getattr(feature, "properties", {}) or {}),
                bbox=tuple(feature.bbox) if feature.bbox else None,
            )
        )

    def all(self) -> List[StoredFeature]:
        return list(self._features)

    def count(self) -> int:
        return len(self._features)

    def query_bbox(self, bbox: FeatureBBox) -> List[StoredFeature]:
        results: List[StoredFeature] = []
        for f in self._features:
            if not f.bbox:
                continue
            min_lon, min_lat, max_lon, max_lat = f.bbox
            if (
                bbox_contains(bbox, min_lon, min_lat)
                and bbox_contains(bbox, max_lon, max_lat)
            ):
                results.append(f)
        return results

    def save(self, path: str) -> None:
        with open(path, "w", encoding="utf-8") as fh:
            json.dump({"features": [f.to_dict() for f in self._features]}, fh)

    def load(self, path: str) -> None:
        with open(path, "r", encoding="utf-8") as fh:
            data = json.load(fh)
        self._features = [_feature_from_dict(d) for d in data.get("features", [])]


class PostGISFeatureStore:
    """Production PostGIS authoring store (Architecture §6).

    Lazy-imports ``psycopg`` so simply importing this module never requires the
    driver to be installed (keeps the in-memory path dependency-free for CI and
    for consumers like vector-tile-gen that only need the memory backend).
    """

    def __init__(self, dsn: str) -> None:
        try:
            import psycopg  # noqa: F401  (imported lazily; must be installed for this backend)
        except ImportError as exc:  # pragma: no cover - defensive
            raise RuntimeError(
                "PostGISFeatureStore requires 'psycopg' (pip install -r requirements.txt)"
            ) from exc
        self._dsn = dsn
        self._conn = psycopg.connect(dsn, autocommit=True, row_factory=psycopg.rows.dict_row)
        self._ensure_schema()

    # -- schema ---------------------------------------------------------------
    def _ensure_schema(self) -> None:
        from .schema import SCHEMA_SQL

        with self._conn.cursor() as cur:
            cur.execute(SCHEMA_SQL)

    # -- write ----------------------------------------------------------------
    def insert(self, feature: Any) -> None:
        geom_json = json.dumps(
            {"type": feature.geometry_type, "coordinates": feature.coordinates}
        )
        props = json.dumps(dict(getattr(feature, "properties", {}) or {}))
        with self._conn.cursor() as cur:
            cur.execute(
                """
                INSERT INTO vector_features (id, geometry_type, geom, properties)
                VALUES (%s, %s, ST_SetSRID(ST_GeomFromGeoJSON(%s), 4326), %s)
                ON CONFLICT (id) DO UPDATE
                  SET geometry_type = EXCLUDED.geometry_type,
                      geom = EXCLUDED.geom,
                      properties = EXCLUDED.properties
                """,
                (feature.id, feature.geometry_type, geom_json, props),
            )

    def _row_to_feature(self, row) -> StoredFeature:
        geom_type = row["geometry_type"]
        props = row["properties"] or {}
        xmin, ymin, xmax, ymax = row["xmin"], row["ymin"], row["xmax"], row["ymax"]
        if xmin is None or ymin is None or xmax is None or ymax is None:
            bbox = None
        else:
            bbox = (float(xmin), float(ymin), float(xmax), float(ymax))
        coords = row["coordinates"]["coordinates"]
        return StoredFeature(
            id=row["id"], geometry_type=geom_type, coordinates=coords, properties=props, bbox=bbox
        )

    def all(self) -> List[StoredFeature]:
        with self._conn.cursor() as cur:
            cur.execute(
                """
                SELECT id, geometry_type,
                       ST_AsGeoJSON(geom)::jsonb AS coordinates,
                       properties,
                       ST_XMin(geom) AS xmin, ST_YMin(geom) AS ymin,
                       ST_XMax(geom) AS xmax, ST_YMax(geom) AS ymax
                FROM vector_features
                """
            )
            return [self._row_to_feature(r) for r in cur.fetchall()]

    def count(self) -> int:
        with self._conn.cursor() as cur:
            cur.execute("SELECT count(*) AS count FROM vector_features")
            return cur.fetchone()["count"]

    def query_bbox(self, bbox: FeatureBBox) -> List[StoredFeature]:
        min_lon, min_lat, max_lon, max_lat = bbox
        with self._conn.cursor() as cur:
            cur.execute(
                """
                SELECT id, geometry_type,
                       ST_AsGeoJSON(geom)::jsonb AS coordinates,
                       properties,
                       ST_XMin(geom) AS xmin, ST_YMin(geom) AS ymin,
                       ST_XMax(geom) AS xmax, ST_YMax(geom) AS ymax
                FROM vector_features
                WHERE ST_Within(geom, ST_MakeEnvelope(%s, %s, %s, %s, 4326))
                """,
                (min_lon, min_lat, max_lon, max_lat),
            )
            return [self._row_to_feature(r) for r in cur.fetchall()]

    def save(self, path: str) -> None:
        """Dump all features to a GeoJSON file (parity with the memory backend)."""
        features = [f.to_dict() for f in self.all()]
        with open(path, "w", encoding="utf-8") as fh:
            json.dump({"features": features}, fh)

    def load(self, path: str) -> None:
        """Bulk-load features from a GeoJSON file into PostGIS."""
        with open(path, "r", encoding="utf-8") as fh:
            data = json.load(fh)
        for d in data.get("features", []):
            self.insert(_feature_from_dict(d))

    def close(self) -> None:
        self._conn.close()


def FeatureStore(dsn: Optional[str] = None) -> Any:
    """Factory: PostGIS backend when a DSN is available, else in-memory.

    Resolution order: explicit ``dsn`` arg, then the ``VECTOR_PG_DSN`` env var.
    """
    resolved = dsn or os.environ.get(DEFAULT_DSN_ENV)
    if resolved:
        return PostGISFeatureStore(resolved)
    return MemoryFeatureStore()


def get_store() -> Any:
    """Build a store from the environment (convenience for service entrypoints)."""
    return FeatureStore()
