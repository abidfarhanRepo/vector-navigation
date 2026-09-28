# vector-geo

Shared geospatial domain models for the **Vector Python tier** — the single
source of truth for geometry, great-circle distance, the routing graph,
shortest-path algorithms, and error types that were previously copy-pasted
across the Python engines (notably `vector-routing` and `vector-logistics`
carried byte-identical copies).

It is **stdlib-only** so it can be vendored into any engine without adding
runtime dependencies.

## How engines consume it

Each consuming engine commits a generated mirror under `vendor/vector_geo/`
(this keeps every repo self-contained for the per-repo CI gate, which checks
out repos in isolation). After editing this repo, re-sync with:

```bash
python3 scripts/sync_vendor.py /path/to/consuming/repo
```

A CI check (`scripts/check_vendor.py`, vendored into each consumer) fails the
build if the mirror diverges from this source — so there is exactly one
canonical implementation.

## Modules

- `haversine` — `haversine_meters`, `haversine_meters_coord`
- `graph` — `RoutingGraph` (node/edge store, nearest-node, serialize)
- `algorithms` — `dijkstra`, `astar`, `build_graph_from_features`, `Route`
- `errors` — `RouteError`, `NoRouteError`

## ADR

ADR-0007 intended `vector-common` to own shared domain models, but
`vector-common` is TypeScript. `vector-geo` is the Python counterpart,
fulfilling that intent for the Python engines.
