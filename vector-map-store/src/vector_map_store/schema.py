"""PostGIS schema for the vector-map-store authoring store (Architecture §6).

Shipped both as this Python string (applied idempotently by PostGISFeatureStore
and migrate.py) and as schema.sql for manual/compose bootstrapping.
"""

SCHEMA_SQL = """
CREATE TABLE IF NOT EXISTS vector_features (
    id            TEXT PRIMARY KEY,
    geometry_type TEXT NOT NULL,
    geom          GEOMETRY NOT NULL,
    properties    JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Spatial index for bbox / containment queries.
CREATE INDEX IF NOT EXISTS vector_features_geom_idx
    ON vector_features USING GIST (geom);

-- GIN index for property lookups.
CREATE INDEX IF NOT EXISTS vector_features_props_idx
    ON vector_features USING GIN (properties);
"""
