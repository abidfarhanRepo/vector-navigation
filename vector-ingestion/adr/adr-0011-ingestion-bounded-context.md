# ADR-0011 — vector-ingestion bounded context + Python stack

- **Status:** Accepted
- **Date:** 2026-07-11
- **Deciders:** CTO / D6 (Data & ML Engineering)
- **Supersedes:** none
- **Superseded by:** none

## Context
Product-first sequencing (adr-0005) starts the M1 product skeletons. This repo is one bounded context in
the mixed-by-layer stack (adr-0003): research/pipelines in Python, engines in Rust/Go. We must fix the
stack and the context boundary now.

## Decision
Build `vector-ingestion` in **Python** (CPython 3.11+), owning exactly: normalizes raw geographic source data into contract Coordinate/BoundingBox/Tile shapes. A trivial health entrypoint is
committed. Native run is **deferred** until the Python toolchain is provisioned (adr-0006); CI uses the
Node-scaffold-runtime pattern so the repo is valid and auditable from day one.

## Consequences
- Clear bounded context; no type duplication (import from contracts / vector-common).
- Native execution not proven in S0 CI; documented deviation, not a throwaway.
- Toolchain install later is mechanical (pyproject + health entrypoint already present).

## Alternatives considered
- **Rust/Go:** reserved for engines (adr-0003); this context is a pipeline/store, not a hot engine.
- **Node/TS:** possible but Python is the agreed data/pipeline language per adr-0003.

## References
- adr-0003 (mixed-by-layer), adr-0005 (product-first), adr-0006 (runtime install), vector-contracts.
