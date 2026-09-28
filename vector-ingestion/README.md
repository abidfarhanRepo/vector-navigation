# vector-ingestion

> **Owner squad:** D6 (Data & ML Engineering)
> **Secondary:** D3 (Architecture & Standards)
> **Purpose:** `vector-ingestion` is the **ingestion bounded context** (DDD) for Vector. It pulls raw geographic/map source data and normalizes it into the contract shapes (Coordinate, BoundingBox, Tile) owned by `vector-contracts`, ready for `vector-map-store` to persist.
> **Canonical standards:** vector-governance. **Cross-language truth:** vector-contracts.

Python (CPython 3.11+). A trivial `health()` entrypoint is committed; it runs once the Python toolchain is provisioned (adr-0006).

## Bounded context
`vector-ingestion` is the **ingestion bounded context** (DDD) for Vector. It pulls raw geographic/map source data and normalizes it into the contract shapes (Coordinate, BoundingBox, Tile) owned by `vector-contracts`, ready for `vector-map-store` to persist.

## Dependency posture
- Consumes contract types from `vector-contracts` (Coordinate / BoundingBox / Tile / ErrorEnvelope).
- JS mirror: `vector-common`. Native bindings are generated from contracts when the tier installs (adr-0006).
- Fed by / feeds sibling product repos per `vector/registry` dependency edges.


## Health endpoint (committed; runs via provisioned toolchain)
`src/vector_ingestion/__main__.py` exposes a health check. Run with the Python toolchain (provisioned in Session 4).



## Tests
`npm test` runs the Node test runner. Native tests run once the toolchain is provisioned.
