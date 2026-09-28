# Architecture Overview — vector-routing

## Bounded context
`vector-routing` is the **Navigation/Routing bounded context** for the M2 product vertical slice. It builds a routing graph from GeoJSON/normalized features and serves shortest-path routes via Dijkstra / A* to the M2 product surface.

## Ownership & dependency posture
- **Primary squad:** D2 (Product Engineering). **Secondary:** D6 (Data & Analytics).
- **Upstream contracts:** consumes language-neutral types from `vector-contracts`
  (Coordinate / BoundingBox / GeoJSON normalized shapes). Fed at runtime by
  `vector-ingestion` (GeoJSON) and `vector-map-store` (normalized features) per the registry edges.
- **Runtime posture:** Python (CPython 3.11+). Toolchain provisioned in Session 4 (adr-0006, Python 3.11 via uv); health entrypoint now runs.

## Modules & dependencies
- `haversine` — great-circle distance between (lon,lat) coordinate pairs.
- `graph` — `RoutingGraph`: nodes keyed by rounded (lon,lat), directed/undirected edges with weights.
- `algorithms` — `dijkstra`, `astar`, `build_graph_from_features`, `Route`.
- `router` — `Router`: nearest-node snapping and route assembly.
- `service` — `RoutingService` facade. `foot_route(origin, destination,
  profile="general")` is the 4B.4 profile gate: the only wired profile is
  ``general``; anything else raises `RouteError` naming the valid set. It
  stamps the result with `walking_profile` and `contract_version`
  (`FOOT_CONTRACT_VERSION`). `foot_status()` publishes the `contract`
  declaration (ETA decision, profiles, maneuver vocabulary) beside the cost
  model and salience constants.
- `foot_graph` — the pedestrian graph: `FootRouter` over the same machinery, with
  component-aware pair snapping (V7.4 4A). `walk()` reports the snapped/requested
  straight-line distances and a snap-to-snap `detour_ratio` (never < 1.0). Since
  4A.4 the foot-graph edges carry the promoted raw pedestrian tags
  (`footway`/`crossing`/`surface`/`incline`/`step_count`/...), and `walk()`
  reports `crossing_m` + per-segment `footway`/`crossing`/`lit` facts. Since
  4B.1 `walk()` also emits the structured maneuver FACTS (`pedestrian_maneuvers`)
  — depart / cross / stairs / turn / transition / arrive, each sourced to graph
  geometry or promoted OSM data, no instruction prose. Since 4B.3 `walk()`
  routes by the `pedestrian_cost` model (default ``general`` profile), exposes
  the weighted `cost` summary and `segment_cost_s` array, and `FootRouter`
  accepts `cost_model=` (None = the default general profile;
  `PedestrianCostModel.legacy()` = the exact pre-4B.3 router).
- `pedestrian_maneuvers` — V7.4 4B.1: the fact layer. `CrossingCatalog` loads the
  preserved `highway=crossing` artifact and answers "what kind of crossing is
  here?"; `build_pedestrian_maneuvers(keys, graph, crossings, road_graph)` turns a
  walked path into facts. A cross fact requires `footway=crossing`/`crossing=*`
  edge props (never a plain road intersection); its type comes from the way tag
  first, the artifact second; its crossed road comes from a road graph sharing
  the crossing's nodes. Optional enrichment: without the catalog or road graph
  the facts are complete but type/road-less.
- `pedestrian_plan` — V7.4 4B.2: the interpretation/salience layer.
  `build_pedestrian_plan(facts)` is a pure function of the 4B.1 facts — no
  geometry, no OSM semantics — producing a sparse, ordered planning plan
  (depart / cross / stairs / turn_* / continue / arrive). Dense or wiggling
  bearing noise is merged or cancelled by deterministic spacing, continuity
  and salience rules; crossings and stairs always survive; transitions become
  maneuvers only on a real walkway<->road change; crossing provenance and the
  crossed-road attribution ride through untouched. `walk()` emits
  `maneuver_plan` beside the `maneuvers` fact stream.
- `pedestrian_cost` — V7.4 4B.3: the deterministic pedestrian edge-cost
  model. `PedestrianCostModel.edge_weight()` turns one edge's promoted
  attributes into a search cost in SECONDS: pace (the existing
  `speeds.walk_speed_ms`, stairs at 0.5 m/s) times the documented toggles
  (Tobler incline multiplier; preference profiles for surface/lit/width/
  sidewalk — all OFF in the default ``general`` profile) plus a sourced,
  type-aware crossing expected-delay. `lines()` returns the chained per-factor
  breakdown that sums EXACTLY to the total (no comfort score); `legacy()`
  reproduces the pre-4B.3 router byte-for-byte. `FootRouter` consumes it in
  `_edge_weight` (route choice) while `_edge_duration_s` stays pure pace time
  (the driving model's cost/duration split, mirrored). `walk()` emits
  `cost` (weighted cost, pace, penalty, per-factor seconds, attribute
  exposure: stairs/step_count, incline, crossing wait, poor surface, unlit,
  narrow width, no sidewalk) and `segment_cost_s` (one per geometry pair);
  `/footz` publishes the full `cost_model` configuration.
- The **public /foot contract** (V7.4 4B.4): the wire is stabilized so the
  Android client has an explicit, versionable representation. `/foot`
  accepts `profile=general` (the ONLY wired profile; absent = general; any
  other name is a loud 400 naming the valid set, never silently general)
  and returns ``contract_version`` and ``walking_profile`` beside every
  pre-4B.3 field. The ETA/cost decision is explicit and backward-
  compatible: ``duration_s`` keeps its meaning (pure walking pace time,
  equal to ``cost.pace_s``), and the cost-inclusive reading
  (``cost.cost_s``), the penalty split (``cost.penalty_s``) and the expected
  crossing delay (``cost.crossing.wait_s``) are reported separately — the
  decision is declared machine-readably on `/footz` under ``contract.eta``.
  ``cost.selection_factors`` separates what affected route selection (the
  live factor set) from what was merely observed (the exposure blocks,
  measured regardless of profile flags). 4B.1/4B.2 facts and plans ride
  through with their provenance (crossed road `None` stays `None`;
  crossing type `way`/`catalog`/`catalog_node`/`None` preserved).
- `barriers` — the barrier catalog (`<region>_barriers.geojson`) and the graph
  surgery it drives: nodes at locked/private gates are severed from the walking
  graph at load, so no walk passes, starts at, or ends at one (V7.4 4A).
- `signals` / `camera_catalog` — the perpendicular along-route projections of
  the baked signal and speed-camera catalogs (V7 Stage 5 / V7.3).
- Ingestion artifacts: `<region>_crossings.geojson` (V7.4 4A.4) preserves the
  `highway=crossing`/`kerb` NODE facts (crossing type, kerb, tactile paving) as
  location points; `pedestrian_maneuvers.CrossingCatalog` is its consumer, and
  4B.2 turns the facts it feeds into crossing-type phrasing.
- No circular dependencies; keep the bounded context strictly within this repo's owned paths (Blueprint §5).

## Design rules (Bible §3)
- Explicit validation at boundaries; fail loud (Bible §16 ER1).
- Self-contained: no direct sibling import; reimplements a minimal GeoJSON parser (isolated-CI rule).
- Add a benchmark under `vector-playground` before any perf optimization (Bible §5 P1).

