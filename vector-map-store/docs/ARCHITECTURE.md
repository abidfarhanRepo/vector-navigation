# Architecture Overview — vector-map-store

## Bounded context
`vector-map-store` is the **persistence bounded context** for normalized map features. It stores contract Coordinate/BoundingBox shapes and serves feature reads to `vector-tile-gen`.

## Ownership & dependency posture
- **Primary squad:** D2 (Product Engineering). **Secondary:** D1 (Platform & Infrastructure).
- **Upstream contracts:** consumes language-neutral types from `vector-contracts`
  (Coordinate / BoundingBox / Tile / ErrorEnvelope schemas). The JS mirror lives in
  `vector-common`; Python/Rust bindings are generated from contracts when their tiers install (adr-0006).
- **Runtime posture:** Python (CPython 3.11+). Toolchain provisioned in Session 4 (adr-0006, Python 3.11 via uv); health entrypoint now runs.

## Modules & dependencies
See README.md for the module/package layout. No circular dependencies; keep the bounded
context strictly within this repo's owned paths (Blueprint §5).

## Design rules (Bible §3)
- Explicit validation at boundaries; fail loud (Bible §16 ER1).
- No duplicated shared types — import from `vector-common` / generate from `vector-contracts`.
- Add a benchmark under `vector-playground` before any perf optimization (Bible §5 P1).
