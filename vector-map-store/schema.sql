-- PostGIS schema for the vector-map-store authoring store (Architecture §6).
-- Idempotent: safe to run on every boot (CREATE ... IF NOT EXISTS).

CREATE TABLE IF NOT EXISTS vector_features (
    id            TEXT PRIMARY KEY,
    geometry_type TEXT NOT NULL,
    geom          GEOMETRY NOT NULL,
    properties    JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS vector_features_geom_idx
    ON vector_features USING GIST (geom);

CREATE INDEX IF NOT EXISTS vector_features_props_idx
    ON vector_features USING GIN (properties);
