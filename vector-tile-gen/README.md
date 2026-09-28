# vector-tile-gen

> **Owner squad:** D2 (Product Engineering)
> **Secondary:** D3 (Architecture & Standards)
> **Purpose:** `vector-tile-gen` is the **tile-generation bounded context**. It reads normalized features from `vector-map-store` and emits vector tiles conforming to the Tile schema (mvt/pbf) for `vector-tile-server` to serve.
> **Canonical standards:** vector-governance. **Cross-language truth:** vector-contracts.

Python (CPython 3.11+). A trivial `health()` entrypoint is committed; it runs once the Python toolchain is provisioned (adr-0006).

## Bounded context
`vector-tile-gen` is the **tile-generation bounded context**. It reads normalized features from `vector-map-store` and emits vector tiles conforming to the Tile schema (mvt/pbf) for `vector-tile-server` to serve.

## Dependency posture
- Consumes contract types from `vector-contracts` (Coordinate / BoundingBox / Tile / ErrorEnvelope).
- JS mirror: `vector-common`. Native bindings are generated from contracts when the tier installs (adr-0006).
- Fed by / feeds sibling product repos per `vector/registry` dependency edges.


## Health endpoint (committed; runs via provisioned toolchain)
`src/vector_tile_gen/__main__.py` exposes a health check. Run with the Python toolchain (provisioned in Session 4).



## Tests
`npm test` runs the Node test runner. Native tests run once the toolchain is provisioned.
