# Architecture Overview — vector-tile-server

## Bounded context
`vector-tile-server` is the **serving engine** for generated vector tiles. A Rust (axum) HTTP service exposing a health endpoint and the tile API; it proves the engine-language toolchain (adr-0009).

## Ownership & dependency posture
- **Primary squad:** D2 (Product Engineering). **Secondary:** D3 (Architecture & Standards).
- **Upstream contracts:** consumes language-neutral types from `vector-contracts`
  (Coordinate / BoundingBox / Tile / ErrorEnvelope schemas). The JS mirror lives in
  `vector-common`; Python/Rust bindings are generated from contracts when their tiers install (adr-0006).
- **Runtime posture:** Rust (stable). Engine source committed; native compile/run now enabled by Session 4 toolchain provisioning (adr-0006, Rust 1.97 via rustup); engine-language choice locked by adr-0009.

## Modules & dependencies
See README.md for the module/package layout. No circular dependencies; keep the bounded
context strictly within this repo's owned paths (Blueprint §5).

## Design rules (Bible §3)
- Explicit validation at boundaries; fail loud (Bible §16 ER1).
- No duplicated shared types — import from `vector-common` / generate from `vector-contracts`.
- Add a benchmark under `vector-playground` before any perf optimization (Bible §5 P1).
