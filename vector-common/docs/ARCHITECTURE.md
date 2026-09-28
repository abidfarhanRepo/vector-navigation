# Architecture Overview — vector-common

## Bounded context
`vector-common` is the **shared kernel** (DDD) for the Node/TS tier. It owns primitive domain types
so every service speaks the same language at boundaries (ADR-0007, Bible §7 D6).

## Why a shared kernel (not per-repo types)
- Eliminates duplicated geometry/coordinate/id/error logic -> fewer boundary bugs.
- One place to fix a coordinate bug; consumers inherit the fix.
- Aligns with contracts in `vector-contracts`; JS mirrors the neutral schemas.

## Modules & dependencies
```
src/index.js  -> re-exports all
units.js      -> no deps
coords.js     -> units.js
geometry.js   -> coords.js, units.js
ids.js        -> node:crypto
errors.js     -> ids.js (EntityId available for correlation)
validation.js -> coords.js, geometry.js, errors.js
```
No circular dependencies. No runtime third-party deps.

## Design rules (Bible §3)
- Explicit constructors validate inputs (fail loud, Bible §16 ER1).
- No dead code; each export is used or will be by a consumer.
- `VectorError` carries a `VEC-####` code so APIs return a typed envelope.

## Evolution
- As engines move to Rust/Go, do NOT reimplement these in JS-only silos; generate from
  `vector-contracts` where possible.
- Add a benchmark under `vector-playground` before any perf optimization (Bible §5 P1).
