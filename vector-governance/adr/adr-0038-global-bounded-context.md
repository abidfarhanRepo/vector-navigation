# adr-0038 — Global basemap — vector-global bounded context + Python stack

## Status
Accepted.

## Context
The Vector roadmap (phase 9) calls for Global scaling: scaling the platform beyond the single Berlin sample to a worldwide, multi-region basemap. No engine exists yet to catalog global regions or serve region data by bbox.

## Decision
Introduce `vector-global` as the global-basemap bounded context for the phase-9 product vertical slice. It is a self-contained Python (CPython 3.11+) engine that:
- maintains a worldwide `RegionCatalog` of regions (id, name, country, bbox, tier free/pro/enterprise, zoom range, source_repo/source_service referencing existing capability services);
- serves region Polygons as GeoJSON filtered by bounding box and/or tier, with no sibling-repo imports (isolated-CI rule, adr-0003 mixed-by-layer).

## Consequences
- `vector-global` owns exactly: global region catalog, bbox/tier region query, and GeoJSON emission. It consumes contract shapes from `vector-contracts` and is fed by `vector-ingestion`/`vector-map-store` at runtime per registry edges.
- The repo is stdlib-only and self-contained, keeping it gate-green and consistent with the M2–M8 product engines.
- A live overlay (adr-0039) exposes this engine behind the M1 tile-server as a Global mode region picker.

## Alternatives considered
- Embedding the region catalog in the tile-server (Rust): rejected — breaks mixed-by-layer boundary (adr-0003) and self-contained constraint.
- Full planet tiling/serving now: deferred — this wave ships the worldwide region catalog and GeoJSON emission; full planet tile generation is a follow-up.

This ADR lifts nothing in adr-0005 (product slice, not E1–E9).
