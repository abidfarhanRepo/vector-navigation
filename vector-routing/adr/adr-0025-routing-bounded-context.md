# ADR-0025 — vector-routing bounded context + Python stack

- **Status:** Accepted
- **Date:** 2026-07-13
- **Deciders:** CTO / D2 (Product Engineering)
- **Supersedes:** none
- **Superseded by:** none

## Context
Product-first sequencing (adr-0005) advances the M2 product vertical slice. This repo is one bounded
context in the mixed-by-layer stack (adr-0003): research/pipelines in Python, engines in Rust/Go. The
Navigation/Routing engine needs a self-contained graph build + shortest-path layer that can be run in
the isolated per-repo CI (no sibling imports). We must fix the stack and the context boundary now.

## Decision
Build `vector-routing` in **Python** (CPython 3.11+), owning exactly: graph build from GeoJSON/normalized
features, shortest-path routing (Dijkstra / A*), and a `RoutingService` facade. A trivial `health()`
entrypoint and an in-code `main()` demo are committed. The repo is **self-contained** — it reimplements a
minimal GeoJSON parser and consumes contract Coordinate/BoundingBox / GeoJSON normalized shapes from
`vector-contracts` without importing sibling repos (isolated-CI rule). Native run is available via the
provisioned Python toolchain (adr-0006).

## Consequences
- Clear bounded context; no type duplication and no sibling import (isolated-CI rule satisfied).
- Routing is correct-by-construction (Dijkstra/A* with admissible haversine heuristic).
- Minimal GeoJSON parser is intentionally duplicated rather than imported (per the isolated-CI rule).

## Alternatives considered
- **Rust engine:** deferred — reserved for hot engines (adr-0003); this M2 slice values self-containment and speed of delivery over raw throughput.
- **Node/TS:** possible but Python is the agreed data/pipeline language per adr-0003 and keeps parity with the ingestion/contracts toolchain.

## References
- adr-0003 (mixed-by-layer), adr-0005 (product-first), adr-0006 (runtime install), vector-contracts.

