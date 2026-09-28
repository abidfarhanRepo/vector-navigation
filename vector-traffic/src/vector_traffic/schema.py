"""PostGIS schema for the vector-traffic live-state store (ADR-0025 pattern)."""

SCHEMA_SQL = """
CREATE TABLE IF NOT EXISTS traffic_state (
    segment_id     TEXT PRIMARY KEY,
    geom           GEOMETRY NOT NULL,
    free_flow_kmh  DOUBLE PRECISION NOT NULL,
    mean_speed_kmh DOUBLE PRECISION,
    probe_count    INTEGER NOT NULL DEFAULT 0,
    congestion     TEXT NOT NULL DEFAULT 'unknown',
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS traffic_state_geom_idx
    ON traffic_state USING GIST (geom);

-- W42/W43: crowdsourced incidents, persisted so they survive restart.
CREATE TABLE IF NOT EXISTS incidents (
    id        TEXT PRIMARY KEY,
    geom      GEOMETRY(Point, 4326) NOT NULL,
    kind      TEXT NOT NULL DEFAULT 'hazard',
    note      TEXT NOT NULL DEFAULT '',
    created   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS incidents_geom_idx
    ON incidents USING GIST (geom);
"""
