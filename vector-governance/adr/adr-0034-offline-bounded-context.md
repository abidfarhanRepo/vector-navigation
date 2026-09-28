# adr-0034 — Offline maps — vector-offline-maps bounded context + Python stack

## Status
Accepted.

## Context
The Vector roadmap (phase 7) calls for Offline maps: pre-packaged map regions a user can download for use without a network connection. No engine exists yet to plan or estimate these offline packages.

## Decision
Introduce `vector-offline-maps` as the Offline maps bounded context for the M7 product vertical slice. It is a self-contained Python (CPython 3.11+) engine that:
- models an offline package as a bounding box over a zoom range and computes the tile count and estimated download size (MB) using the Slippy Map tiling scheme;
- maintains a registry of pre-packaged "region" footprints (bundled sample data) with status (ready/building/expired), zoom range, tile count, and size;
- exposes the result as GeoJSON `Polygon` features (contract Coordinate/BoundingBox/GeoJSON shapes), with no sibling-repo imports (isolated-CI rule, adr-0003 mixed-by-layer).

## Consequences
- `vector-offline-maps` owns exactly: offline package estimation (tile math + size), the region registry, and GeoJSON emission. It consumes contract shapes from `vector-contracts` and is fed by `vector-ingestion`/`vector-map-store` at runtime per registry edges.
- The repo is stdlib-only and self-contained, keeping it gate-green and consistent with the M2–M5 product engines.
- A live overlay (adr-0035) exposes this engine behind the M1 tile-server so users can draw offline coverage on the map.

## Alternatives considered
- Embedding offline packaging in the tile-server (Rust): rejected — breaks mixed-by-layer boundary (adr-0003) and self-contained constraint.
- Full MBTiles generation now: deferred — this wave ships the planner/estimator; physical tile bundling is a follow-up.

This ADR lifts nothing in adr-0005 (product slice, not E1–E9).
