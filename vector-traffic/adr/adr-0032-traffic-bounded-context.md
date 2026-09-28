# ADR-0032 — Crowdsourced traffic — vector-traffic bounded context + Python stack

- **Status:** Accepted
- **Date:** 2026-07-13
- **Deciders:** CTO / D2 (Product Engineering)
- **Supersedes:** none
- **Superseded by:** none

## Context
Product-first sequencing (adr-0005) advances the M6 product vertical slice. This repo is one bounded
context in the mixed-by-layer stack (adr-0003): research/pipelines in Python, engines in Rust/Go. The
next product vertical slice after Road Reconstruction (roadmap phase 5) is **Crowdsourced traffic**
(roadmap phase 6). The engine needs a self-contained layer that can estimate per-segment speed and
congestion from crowdsourced GPS probe points over a road network, and emit them as GeoJSON — all
runnable in the isolated per-repo CI (no sibling imports). We must fix the stack and the context
boundary now.

## Decision
Build `vector-traffic` in **Python** (CPython 3.11+), owning exactly: estimation of per-segment speed
and congestion from crowdsourced GPS probe points over a road network (map-matching probes to the
nearest road segment, then aggregating mean speed into congestion buckets), a `TrafficModel` facade,
and `health()` + `main()` demos. A trivial `health()` entrypoint and an in-code `main()` demo are
committed. The repo is **self-contained** — it reimplements minimal GeoJSON I/O and consumes contract
Coordinate/BoundingBox / GeoJSON normalized shapes from `vector-contracts` without importing sibling
repos (isolated-CI rule). Native run is available via the provisioned Python toolchain (adr-0006).

## Consequences
- Clear bounded context; no type duplication and no sibling import (isolated-CI rule satisfied).
- Estimation is deterministic: nearest-segment map-matching with radius rejection and deterministic
  tie-break, then mean-speed aggregation into free/light/moderate/heavy/jammed congestion buckets.
- Minimal GeoJSON I/O is intentionally reimplemented rather than imported (per the isolated-CI rule).

## Alternatives considered
- **Rust engine:** deferred — reserved for hot engines (adr-0003); this M6 slice values self-containment and speed of delivery over raw throughput.
- **Node/TS:** possible but Python is the agreed data/pipeline language per adr-0003 and keeps parity with the ingestion/contracts toolchain.

## References
- adr-0003 (mixed-by-layer), adr-0005 (product-first), adr-0006 (runtime install), adr-0030 (reconstruction bounded context), vector-contracts.
