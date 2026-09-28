# ADR-0025 — PostGIS authoring store for vector-map-store

- Status: Accepted
- Supersedes: (none — replaces the S0 in-memory stand-in described in `vector-map-store/src/vector_map_store/store.py`)
- Superseded by: (none)

## Context

`vector-map-store` was an S0 stand-in: an in-memory `FeatureStore` with optional
GeoJSON-file persistence (Architecture §6). The production target is a
Vector-owned **PostGIS** authoring store with spatial queries. Until Wave 23 the
project had **zero** database usage anywhere — `map-store` held all features in
process memory, so restarts lost data and there was no spatial index for
scale. This blocked the "production-ready" bar for persistence (Pillar 4).

The CI gate checks out each repo in isolation (no external services), so any
persistence layer must keep the gate green **without** a live database.

## Decision

Adopt **PostGIS** (PostgreSQL + PostGIS extension) as the authoring store,
accessed via **psycopg3**. Ship it behind the **existing `FeatureStore`
interface** through a factory:

- `FeatureStore(dsn=None)` returns `PostGISFeatureStore` when a DSN (or the
  `VECTOR_PG_DSN` env var) is set, otherwise `MemoryFeatureStore` (the former
  in-memory logic, renamed).
- `PostGISFeatureStore` stores `geom GEOMETRY`, `properties JSONB`, a GIST index
  on `geom`, and a GIN index on `properties`; `query_bbox` uses
  `ST_Within(geom, ST_MakeEnvelope(...))`.
- psycopg is **lazy-imported** inside `PostGISFeatureStore`, so importing the
  module and using the in-memory path require **no** driver — consumers like
  `vector-tile-gen` keep working dependency-free.
- Real, reproducible infra ships with the repo: `schema.sql`, `schema.py`,
  `scripts/migrate.py`, `requirements.txt` (psycopg), and `docker-compose.yml`
  (postgis/postgis:16-3.4).
- A **live integration test** (`tests/test_postgis.py`) asserts behavioral
  parity with the in-memory store, but is **skipped unless `VECTOR_PG_DSN` is
  set** — so isolated CI stays green while local/dev (with `docker compose up`)
  proves the real backend.

## Consequences

- Positive: real spatial persistence, durable across restarts, GIST-indexed
  bbox queries, parity-verified against a live PostGIS 16 in this wave.
- Positive: gate stays green (in-memory default + skipped integration test);
  no new dependency for non-persistence consumers.
- Positive: reproducible local PostGIS via compose + idempotent migration.
- Negative: production deployments must run PostGIS and `pip install -r
  requirements.txt`; the in-memory path remains a dev convenience only.
- Negative: only `vector-map-store` is wired to PostGIS this wave; other engines
  (ingestion, traffic, etc.) still use in-memory/stand-in stores — later waves
  extend this pattern.

## Alternatives considered

- **SQLite + SpatiaLite**: zero-service, but not the Architecture §6 target and
  weaker spatial tooling at scale; rejected in favor of the stated PostGIS goal.
- **Pure in-memory + file**: the status quo; rejected (no durability, no index).
- **Submodule/shared DB client lib**: overkill for one store; the factory +
  lazy import keeps it simple and isolation-safe.

## Status

Accepted (Wave 23). Implemented in `vector-map-store`; verified against live
PostGIS 16 (5/5 parity tests) and the isolated gate (16 tests, 5 skipped).
