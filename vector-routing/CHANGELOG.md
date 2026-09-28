# CHANGELOG — vector-routing

## 2026-09-19 — Speed cameras are typed, deduped by identity, and cheap (V7)

**Added**

- `camera_catalog.classify_camera()` and the closed type vocabulary
  (`speed` / `average_speed` / `red_light` / `combined` / `unknown`) — the
  camera TYPE the source states, read from `highway=speed_camera` and
  `enforcement=*` and nothing else. Anything the vocabulary does not cover is
  `unknown`, which the client never announces.
- `CameraCatalog.type_census()` and `RoutingService.cameras_status()`'s `types`
  key — the per-type count of the claims a deployment can actually defend. The
  service logs it on load.
- A per-route segment grid in `cameras_on_path`: ~111 m cells sized to the snap
  gate, so a camera within the gate provably shares a cell with its segment.
  **17.7× faster** (17.9 ms → 1.0 ms per route at 418 vertices × 133 cameras),
  proven identical to the flat scan on every measured route and on a dense
  fixture that keeps the old scan as the oracle.
- `type` on the wire entry, so a client can present the source-stated kind
  without re-reading the bake.

**Changed**

- Deduplication is now by **source identity only**. The 10 m proximity merge is
  gone: the closest distinct pair in Qatar is 11.5 m apart, two installations at
  one gantry, and the old test compared *along-route* distance alone — so it was
  collapsing cameras 15–31 m apart on opposite carriageways. Seven real cameras
  on the frozen route set were being deleted.
- Projection requires the perpendicular foot to lie **on** the segment, matching
  the client's `CameraMatcher.earliestPass`. Clamping `t` let a camera past a
  segment's end be claimed at the endpoint; on real routes that decided 14 of 94
  claims (14.9%).
- `CameraCatalog.from_feature_collection` is total: a wrong-shaped document is
  an empty catalog rather than an exception into service startup, a malformed
  entry is skipped alone instead of emptying the catalog, and a camera with no
  source identity is skipped rather than invented as `camera-<n>`.

## 2026-09-14 — The pedestrian graph and /foot (V7 Phase 2)

**Added**

- `vector_routing/foot_graph.py` — a SECOND `RoutingGraph` built from the ways
  `vector_ingestion.classify.is_pedestrian_routable` marks walkable, and a
  `FootRouter` over it. Qatar: 1,108,269 nodes / 2,377,926 edges, including the
  40,437 ways that are walkable and not drivable and that the car bake has always
  (correctly) discarded. Same ingestion, same `astar`, same snapping and error
  contract — `FootRouter` overrides only the edge cost and the edge duration.
- `speeds.walk_speed_ms()` — 1.35 m/s flat, 0.5 m/s over `highway=steps`. Stairs
  are penalised, never banned: banning them does not lengthen a route, it
  disconnects the graph and answers "no route" for a footbridge.
- `GET /foot?from=LAT,LON&to=LAT,LON` → GeoJSON with `distance_m`, `duration_s`,
  `steps_m`, `snap`/`snap_max_m` and `walk_speed_ms`. Metres, not kilometres,
  because a walk is metres. No traffic and no learned layer: both are statements
  about vehicles.
- `GET /footz` — is a pedestrian graph loaded, and how big is it. "Nothing baked"
  and "baked but empty" are indistinguishable from a failed walk without it.
- `RoutingService.load_foot_graph()` / `set_foot_graph()` / `foot_route()` /
  `foot_status()`, and `serve.default_foot_graph_path()` which finds
  `<region>_foot.geojson` beside the `<region>_roads.geojson` it is already
  given — so the deployment picks the walking graph up with NO docker-compose
  change. Absent at every source, `/foot` answers 503 with the reason and every
  other endpoint behaves exactly as before.
- `tests/data/msheireb.geojson` — 710 features of real converted OSM around
  Msheireb Downtown Doha, so the real-data cases run on data nobody invented.

**Changed**

- `Router` now decides its edge cost and edge duration through `_edge_weight` /
  `_edge_duration_s` instead of module-level calls. No behaviour change for
  cars — the 324 pre-existing tests pass unchanged — and it is what lets the
  walking router reuse this class rather than copy it.

**Measured**

- 24 real Doha destination→parking pairs served from `/foot`: 24 routed,
  24 within ±20 % of `distance_m / 1.35`. Evidence and the two open UX
  questions it exposed: `.scratch/vector-product/V7-PHASE2-FOOT-ACCEPTANCE.md`.

## 2026-08-04 — Learned speed profiles wired into /route and /navigate (Session 52, issue 07)

**Added**

- `RoutingService` learned-overlay support: `load_learned_facts()` (reads
  `learned_speed.json` over the filesystem — routing never imports the learning
  repo, adr-0003), `set_learned_overlay()`, `set_learned_enabled()` as the
  one-flag rollback, and `record_eta()`.
- `/route` and `/navigate` apply learned profiles, with `?learned=0` to opt out
  per request and `?hour=` to pin the bucket. Composition with live traffic is
  `LearnedSpeedView(OverlayView(base, traffic), learned)` — factors multiply,
  learned sets the baseline and live traffic scales it (adr-0066 §2).
- `GET /learned` — overlay size *and* how many buckets actually resolved onto this
  graph. "Loaded but inert" is a different failure from "nothing loaded", and only
  that number distinguishes them.
- `POST /eta` (validated) and `GET /eta` — predicted-vs-observed travel time as a
  distribution split by learned coverage. Without this the whole effort is
  unfalsifiable: there is no way to tell a system that learns from one that merely
  has a learning pipeline.
- `navigate()` now reports `learned_coverage` / `learned_segments`, passed through
  to the GeoJSON so the client can post it back with the observed duration.
- `scripts/bench_learned_latency.py` — reproducible latency measurement, because
  issue 07 asks for a number rather than a claim.

**Fixed / changed**

- **The learned factor now uses the edge's own maxspeed as its baseline**, not a
  fixed 30 km/h constant. This makes the arithmetic exact rather than merely
  directional: the router derives both its search cost and its ETA as
  `length / baseline`, so scaling the length by `baseline / learned` turns both
  into `length / learned` with no other change. With a fixed baseline, a learned
  100 km/h motorway would be routed correctly and then mis-timed — the harder bug
  to notice.
- **`Router.navigate` gained a `speed_for_edge` hook.** Per-segment duration was
  derived from `props` maxspeed and ignored the search graph entirely, so a
  learned overlay would have changed *which* road was chosen while still quoting
  the OSM travel time for it. Since issue 07's claim is about ETA accuracy, that
  gap would have made the claim untestable.

**Performance**

The first working implementation cost **+242%** on `/route` over the full Qatar
graph (1.32 M nodes / 2.50 M directed edges): 2.34 s → 7.98 s — the same
per-relaxation blow-up as the Wave 26c traffic overlay, and exactly what issue 07
forbids. Fixed by not doing per-edge work at all: `CompiledLearnedOverlay` resolves
the whole overlay to graph node keys once per load, folding in each edge's
baseline, and the hot path becomes one dict lookup keyed by the source node — so a
node with nothing learned skips the inner work entirely.

| configuration | median | vs baseline |
|---|---|---|
| `/navigate` no learned layer | 2258 ms | — |
| `/navigate` learned ETA hook | 2399 ms | +6.3% |
| `/route` no learned layer | 2256 ms | — |
| `/route` learned search overlay | 2567 ms | **+13.8%** |

The reference `LearnedSpeedView` is kept as the readable implementation, and a test
asserts both produce identical weights edge-for-edge.

Tests: 124 → 150. Includes a test pinning `hour_of_week` to agree bucket-for-bucket
with `vector-learning`'s copy — the two repos never import each other, and a
one-hour drift would apply Monday-morning profiles to Sunday-night traffic.
