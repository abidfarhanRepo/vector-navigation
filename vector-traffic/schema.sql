-- PostGIS schema for the vector-traffic live-state store (ADR-0025 pattern).
-- Idempotent.

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
