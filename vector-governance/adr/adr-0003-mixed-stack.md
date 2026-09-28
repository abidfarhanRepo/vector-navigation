# ADR-0003 — Mixed-by-layer technology stack

- **Status:** Accepted
- **Date:** 2026-07-11
- **Deciders:** CTO / D3 Architecture
- **Supersedes:** none
- **Superseded by:** none

## Context
Vector spans research/pipelines, performance-critical engines, web clients, and shared libraries.
No single language is optimal across all layers.

## Decision
Choose the stack **per layer**:
- Research & data pipelines: **Python** (GDAL, PostGIS, PyTorch, etc.).
- Routing / tile / serving engines: **Rust or Go** (performance-first).
- Clients: web app (vector-web, MapLibre + single-origin proxy) is the primary client surface.
- Shared tier utilities & lightweight scaffolding: **Node.js / TypeScript** (available now).

Cross-language types live in `vector-contracts` (language-neutral), not in any one repo.

## Consequences
- Best tool per layer; engines stay fast; research moves fast.
- Some duplication risk across language boundaries, contained by contracts in `vector-contracts`.

## Alternatives considered
- **Single language (e.g., Rust everywhere):** maximal reuse but slow research iteration; rejected.
- **Single language (Python everywhere):** fast prototyping but poor engine performance; rejected.

## References
- Vector_System_Architecture.md §3, §9; Bible §5 P1.
