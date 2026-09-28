# ADR-0066 — Promotion thresholds, overlay composition, and tile cache invalidation

- **Status:** Accepted
- **Date:** 2026-08-04
- **Deciders:** D3 Architecture / D6 Data / D5 Security & Compliance
- **Supersedes:** none
- **Superseded by:** none

## Context

ADR-0065 fixed the *ingest* side of the self-evolving loop: what may be collected,
for how long, and the K=5 floor a fact must clear before it exists at all. It
deliberately said nothing about the *output* side — when a fact that legitimately
exists is allowed to change what a user sees.

That question turns out to carry most of the risk in the effort, for three
reasons that only became visible once the loop was wired end to end (Session 52):

1. **The consequences of a wrong promotion are wildly asymmetric.** A wrong
   learned speed makes a route slower than necessary. A wrong learned *road*
   draws a street that does not exist onto the basemap every user sees, and a
   driver may follow it. These cannot share one confidence threshold.
2. **Two overlays now modify routing weights** — live traffic (Wave 26c) and
   learned speed profiles (issue 07). With no stated composition rule, whichever
   was applied last would silently win, and the resulting ETA would be
   unattributable to either input.
3. **A verified tile write is not a visible tile change.** Session 50's
   "streets vanish" incident was masked for hours by a stale service worker. The
   same masking applies to a *correct* promotion: tiles are served with
   `max-age=86400`, and MapLibre caches decoded tiles for the life of the page.
   A learned road could be promoted, verified, written to disk, and remain
   invisible — with every server-side check passing.

## Decision

### 1. Per-type promotion thresholds

A fact is promoted into a user-visible surface only above the threshold for its
type. Thresholds live in `vector-learning`'s `DEFAULT_PROMOTION_THRESHOLDS` and
this ADR is their authority.

| Fact type | Threshold | Consumer | Why this number |
|---|---|---|---|
| `speed_profile` | **0.60** | routing weights (issue 07) | Worst case is a slower route. The overlay clamps its own factor to [0.25, 10], so no single bad aggregate can dominate the search, and an unlearned segment keeps the OSM class default. Cheap to be wrong, so the bar is low enough for coverage to actually grow. |
| `turn_restriction` | 0.75 | routing (reserved) | A wrong restriction removes a legal manoeuvre — annoying and detectable, not dangerous. |
| `geometry_correction` | 0.85 | tiles (issue 08) | Moves an existing road. Bounded error: the road is real, its line is wrong. |
| `poi_candidate` | 0.85 | geocoder (issue 09) | Highest *privacy* risk, lowest safety risk. Defended structurally rather than by threshold: endpoint truncation (ADR-0065) plus a K floor counting distinct trips, so one person's home is unlearnable by construction. |
| `road_candidate` | **0.90** | tiles (issue 08) | Asserts a road that OSM does not have. **The two extremes of this table are the decision**: 0.60 for speed, 0.90 for geometry. A wrong speed is a slower route; a wrong road is a user driving into a wall. |

Facts decay with time since `last_confirmed`, so a threshold is a *standing*
requirement, not a one-time gate: a road closed last month falls back below its
bar without any sweeper job, and `contradict()` lowers confidence rather than
deleting, so the map can un-learn.

### 2. Overlay composition: learned sets the baseline, traffic scales it

```
LearnedSpeedView( OverlayView( base_graph, traffic ), learned )
```

- **Learned speed** is what a road is *typically* like at this hour of the week.
  It replaces the *baseline* assumption — the OSM `maxspeed` the router would
  otherwise have used.
- **Live traffic** is what the road is like *right now*. It scales that baseline.
- The two factors **multiply**. Neither overwrites the other: a road that is
  typically slow *and* currently jammed is penalised twice, which is correct.
- Where they disagree about the baseline, live traffic is authoritative for the
  current request, because it is measuring the present.

The learned factor is expressed as `edge_maxspeed / observed_speed`, per edge.
Using a fixed baseline instead would price a learned motorway wrong by the ratio
of its real limit to that constant — the route would be chosen correctly and then
mis-timed, which is the harder failure to notice.

**Rollback is one flag.** `VECTOR_LEARNED_ENABLED=0` (or
`set_learned_enabled(False)`) restores the pre-learning router exactly; the base
graph is never mutated.

### 3. Tile cache invalidation: version the tile set, not the headers

Every bake and re-bake increments an epoch in `<tiles_dir>/VERSION.json`, **after**
the verified tiles are on disk. The tile server exposes it at
`GET /tiles/version` with `Cache-Control: no-store`, and the client puts it in
the tile URL as `?v=<epoch>`.

A new epoch is a new URL, so the browser cache, any intermediary CDN, and
MapLibre's own tile cache all miss and refetch. Consequences of this shape:

- Tiles keep `max-age=86400`, which is correct — a tile *is* immutable for its
  epoch. Caching is not weakened to gain freshness.
- It works through intermediaries we do not control, which header games do not.
- `GET /tiles/version` is the one response in the system that must never be
  cached. It is the response that tells a client its tiles are stale, so
  `no-store` is load-bearing — including **through the `vector-web` proxy**,
  which must forward the backend's `Cache-Control` rather than replacing it.
  An edge that strips it leaves every server-side check passing and the map
  never updating.
- The client also re-checks the epoch on an interval and on tab focus, so a
  long-lived tab picks up a re-bake without a reload.

### 4. Verify before publish, on both bake paths

Every tile — full bake and selective re-bake alike — is encoded to memory and
classified with Session 50's rule (empty, or exactly one MVT layer named
`basemap`) before it may reach disk. Failing tiles are not written, are reported
as `rejected`, and make the bake exit non-zero with the offending fact keys
written out so the promotion can be reverted in the fact store.

Learned geometry is separated at the **source** level (its own GeoJSON overlay,
merged at bake) and the **feature** level (`learned=true`, plus `provisional`
below 0.95 confidence), *not* as a second MVT layer — a second layer is precisely
the invisible-layer bug the verifier exists to catch. The style paints learned
roads amber and dashes them while provisional, so a wrong promotion is obvious
rather than invisible.

## Consequences

- **Positive:** the riskiest promotion in the system needs the most evidence, and
  the reasoning is recorded rather than being a magic number in a dict.
- **Positive:** ETA is attributable. Learned and live inputs compose by a stated
  rule, and `EtaErrorLog` splits its distribution by learned coverage, which
  makes "learning works" falsifiable instead of assumed.
- **Positive:** a promoted road is visible, and a withdrawn one disappears, within
  one epoch check rather than one cache lifetime.
- **Negative:** the epoch is a whole-tile-set version, so a one-road re-bake
  invalidates every client's cached tiles. Per-tile versioning would be finer but
  needs a manifest the client fetches, which is a bigger surface than the problem
  currently warrants. Revisit if re-bakes become frequent.
- **Negative:** `speed_profile` at 0.60 will promote some aggregates that later
  decay below the bar. That is deliberate — the alternative is a coverage curve
  that never moves, and issue 10 treats a **zero** reject rate as a warning that
  thresholds are too loose.
- **Operational:** the K=5 floor and the 72 h TTL together bound what can ever be
  learned. Evidence is bucketed per `(segment, hour_of_week)`, and that bucket
  recurs weekly, so it can only reach K from five distinct trips **inside one
  hour**. Busy roads learn; quiet roads do not. This is the design working as
  intended — no evidence, no learned fact, nothing individually identifiable —
  but it is the binding constraint on the coverage metric and must not be
  mistaken for a bug in the pipeline.

## Alternatives considered

- **One global promotion threshold** — rejected: it forces a single answer to
  "how sure must we be?" for outcomes ranging from a slower route to a
  non-existent road.
- **Learned speed overwrites live traffic (or vice versa)** — rejected: they
  measure different things (typical vs now). Overwriting discards a real signal
  and makes the resulting ETA unattributable.
- **Short `max-age` on tiles instead of a versioned URL** — rejected: it pays a
  permanent bandwidth and latency cost on every tile, forever, to handle a rare
  event, and still cannot reach MapLibre's in-page cache.
- **Cache-busting on every request (`?t=<now>`)** — rejected: defeats caching
  entirely, which for a 56 MB tile set is a serious regression.
- **Purging the service-worker cache after a re-bake** — insufficient on its own.
  The service worker already treats `/tiles` as network-only; the stale copies
  live in the HTTP cache and in MapLibre, neither of which a service worker can
  evict.
- **A second MVT layer for learned geometry** — rejected: it reintroduces the
  exact Session 50 "streets vanish" failure the tile verifier exists to catch.

## References

- ADR-0065 — privacy gate (K, TTL, truncation, accuracy floor; the ingest side)
- ADR-0003 — repo decoupling (why promotion is file-based, not in-process)
- ADR-0027 — overlay-view search cost (the Wave 26c precedent for the +242%
  measurement that motivated the compiled overlay)
- Session 50 — tile-integrity verifier and the stale-`sw.js` masking failure
- Issues `07`, `08`, `09`, `10`: `.scratch/vector-evolve/issues/`
- Measurement: `vector-routing/scripts/bench_learned_latency.py`
