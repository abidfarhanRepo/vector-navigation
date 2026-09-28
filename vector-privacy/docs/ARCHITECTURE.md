# Architecture Overview — vector-privacy

## Bounded context
`vector-privacy` is the **privacy-gate bounded context** (issue 01). It owns the
single definition of the privacy rules applied to location data before it
enters any store: accuracy floor, endpoint truncation, temporal coarsening,
coordinate precision, and per-trip pseudonym rotation.

## Ownership & dependency posture
- **Primary squad:** D5 (Security & Compliance). **Secondary:** D3 (Architecture & Standards).
- **Runtime posture:** Python (CPython 3.11+), stdlib-only, zero dependencies.
  Vendored into `vector-web` and `vector-learning` (vendor/ convention,
  adr-0007) so ingest and aggregation cannot drift apart.

## Modules & dependencies
- `gate` — `apply_gate(kind, points, *, now_ms)`: the single pure, total
  function that enforces every rule and reports why points were dropped.
  `mint_pseudonym()`: opaque per-trip pseudonym (rotates on trip end).
  `haversine_m`: distance primitive used by endpoint truncation.
- No I/O, no global state, no circular dependencies. The store (issue 02) and
  the aggregation stage (issue 05) consume this module; this module consumes
  nothing.

## Design rules (Bible §3)
- Pure and total: never raises on malformed input; drops are reported as
  counters so issue 10 can prove the layer is doing work.
- The client is never trusted to have applied the rules; the server re-applies
  all of them at the ingest boundary (S1).
