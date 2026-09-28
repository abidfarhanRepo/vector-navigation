# vector-map-store

> **Owner squad:** D2 (Product Engineering)
> **Secondary:** D1 (Platform & Infrastructure)
> **Purpose:** `vector-map-store` is the **persistence bounded context** for normalized map features. It stores contract Coordinate/BoundingBox shapes and serves feature reads to `vector-tile-gen`.
> **Canonical standards:** vector-governance. **Cross-language truth:** vector-contracts.

Python (CPython 3.11+). A trivial `health()` entrypoint is committed; it runs once the Python toolchain is provisioned (adr-0006).

## Bounded context
`vector-map-store` is the **persistence bounded context** for normalized map features. It stores contract Coordinate/BoundingBox shapes and serves feature reads to `vector-tile-gen`.

## Dependency posture
- Consumes contract types from `vector-contracts` (Coordinate / BoundingBox / Tile / ErrorEnvelope).
- JS mirror: `vector-common`. Native bindings are generated from contracts when the tier installs (adr-0006).
- Fed by / feeds sibling product repos per `vector/registry` dependency edges.


## Health endpoint (committed; runs via provisioned toolchain)
`src/vector_map_store/__main__.py` exposes a health check. Run with the Python toolchain (provisioned in Session 4).



## Tests
`npm test` runs the Node test runner. Native tests run once the toolchain is provisioned.
