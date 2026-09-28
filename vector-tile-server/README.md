# vector-tile-server

> **Owner squad:** D2 (Product Engineering)
> **Secondary:** D3 (Architecture & Standards)
> **Purpose:** `vector-tile-server` is the **serving engine** for generated vector tiles. A Rust (axum) HTTP service exposing a health endpoint and the tile API; it proves the engine-language toolchain (adr-0009).
> **Canonical standards:** vector-governance. **Cross-language truth:** vector-contracts.

Rust (stable). A trivial `GET /healthz` endpoint is committed; it compiles/runs once the Rust toolchain is provisioned (adr-0006). The engine-language choice is locked by adr-0009.

## Bounded context
`vector-tile-server` is the **serving engine** for generated vector tiles. A Rust (axum) HTTP service exposing a health endpoint and the tile API; it proves the engine-language toolchain (adr-0009).

## Dependency posture
- Consumes contract types from `vector-contracts` (Coordinate / BoundingBox / Tile / ErrorEnvelope).
- JS mirror: `vector-common`. Native bindings are generated from contracts when the tier installs (adr-0006).


## Health endpoint (committed; runs via provisioned toolchain)

`src/main.rs` serves `GET /healthz` returning `200 ok` via axum. Compile/run with the Rust toolchain (provisioned in Session 4).


## Tests
`npm test` runs the Node test runner. Native tests run once the toolchain is provisioned.
