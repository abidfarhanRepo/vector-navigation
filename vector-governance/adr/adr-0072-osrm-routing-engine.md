# ADR-0072 — OSRM contraction hierarchies as the routing engine, with the learned overlay re-timing the result

- **Status:** Accepted
- **Date:** 2026-08-07
- **Deciders:** D3 Architecture / D2 Product / D6 Data
- **Supersedes:** none
- **Superseded by:** none

## Context

The complaint was "your routing engine isn't good". It is a latency complaint,
and it is correct.

`vector-routing` runs plain Dijkstra/A* in pure Python over the full Qatar graph
(1 318 088 nodes / 2 498 986 directed edges). There is no contraction hierarchy,
no bidirectional search, no landmarks, no preprocessing of any kind. Measured on
ten fixed origin/destination pairs over that graph:

| engine | median | p90 |
| --- | --- | --- |
| Python A*, no overlays | **3 237 ms** | 6 905 ms |
| Python A*, learned search overlay | **3 825 ms** | 6 496 ms |

This is inherent to the approach. Every request re-explores the graph from
scratch, so there is no tuning that turns seconds into milliseconds — only
preprocessing does that.

Meanwhile `vector-osrm/data/` has held a **fully preprocessed OSRM dataset since
2026-07-22**, including `qatar.osrm.hsgr` (38 MB — the contraction hierarchy).
`osrm-contract` had already been run. Nothing was reading it.

### The complication found on arrival

A previous session (W49) had already pointed `vector-web` at OSRM: `_osrm_route()`
in `vector_web/__init__.py` serves `/route` and `/navigate` from OSRM and falls
back to the Python engine. It works, and it is fast.

It also **silently drops the learned overlay**. Its response properties carry no
`learned_coverage` and no `learned_segments`. `vector-web/static/index.html:849`
reads `p.learned_coverage` and posts it back to `/eta` on arrival, which is how
the ETA-error distribution is split learned-vs-unlearned — the falsifiable claim
in issue 07. With OSRM serving the default path, that field was always absent, so
**every completed navigation was recorded as unlearned**.

That failure is invisible. `learned_coverage: 0.0` does not read as a bug; it
reads as "we have not learned much here yet". The learned half of the split
simply never receives a sample, and the claim quietly becomes unfalsifiable
while every dashboard stays green.

So the real question is not "can we make routing fast" — W49 already showed we
can. It is **"can we make routing fast without deleting the product"**.

### The genuine architectural tension

OSRM contraction hierarchies **bake edge weights at contract time**. A shortcut
edge in `.hsgr` summarizes an entire path under the weights current when it was
built. Per-request edge weights therefore cannot influence a CH search. That is
not a defect; it is the mechanism that makes CH fast.

The project's differentiator is learned per-segment speeds (ADR-0066) that
reweight routes. Those weights are per-request in two ways that matter:

1. They vary by **time-of-week band** — `/route?band=N`, 8 bands (ADR-0067).
2. They are toggleable per request — `/route?learned=0` — the one-flag rollback.

## Decision

**A tiered engine, with the learned overlay applied as a post-hoc re-timing of
the CH-selected path.** Option (c) of the three considered.

### 1. Engine selection (`serve.py::_route_with_fallback`)

| request | engine | why |
| --- | --- | --- |
| `traffic=1` | Python | The congestion overlay reroutes *around* jammed edges. That is a change of path, which CH cannot do per request. |
| `engine=python` | Python | Explicit escape hatch for a learned-**weighted search**. |
| everything else | **OSRM CH** | The default, and the overwhelming majority of traffic. |

OSRM is an **accelerator, never a dependency**. Any `RouteError` — server down,
timeout, unreachable — falls back to the Python engine. A slow route beats no
route, and the pre-integration behaviour is always still reachable. `NoRouteError`
is *not* caught, because "these two points are genuinely unconnected" is an
answer, not a failure, and retrying it on a slower engine wastes seconds to
produce the same 404.

### 2. The learned overlay re-times, it does not re-shape

`RoutingService.route_fast` takes OSRM's geometry and corrects its duration:

```
duration = osrm_duration + Σ over learned segments ( d/v_learned − d/v_baseline )
```

A **correction**, not a replacement. OSRM's duration includes turn penalties and
`car.lua` profile detail the GeoJSON graph does not model. Recomputing every
segment from graph maxspeed would discard all of that in order to apply the
overlay to the ~1% of segments that actually have a profile. Unlearned segments
keep OSRM's own, better estimate untouched.

`navigate_fast` goes further: it snaps the OSRM geometry onto graph node keys and
hands it to `Router.assemble_from_keys` — **the same code path `navigate()` uses**,
extracted for this purpose. Steps, ETA and `learned_coverage` are therefore
produced by one implementation regardless of which engine found the path. This is
the specific fix for the W49 telemetry loss: there is no second serializer that
can forget a field.

### 3. Coordinate precision is a joint, and joints fail silently

OSRM stores coordinates as fixed point at 1e-6; `RoutingGraph` keys at 1e-7. So
graph node `51.5420989` comes back from OSRM as `51.542099` and the exact key
lookup **misses**. Left alone this yields 0% learned coverage — the exact
invisible failure described above, reintroduced by arithmetic.

`RoutingService._snap_key` therefore tries the exact key first and falls back to
the grid-accelerated `nearest_node`. Pinned by
`test_six_decimal_osrm_coordinates_still_snap`.

## Alternatives considered

### (a) MLD + periodic re-customization — **rejected**

The standard answer for traffic updates (`osrm-partition` + `osrm-customize` +
`--segment-speed-file`), and it fits the evolution cycle's schedule. Rejected on
four grounds, the first of which is decisive:

1. **It cannot serve the existing API.** `/route?band=N` selects one of 8
   time-of-week bands *per request*. A customized MLD dataset carries exactly
   **one** weight set. Serving arbitrary per-request bands would need 8
   customized datasets and a per-query switch between them, which `osrm-routed`
   does not have. MLD would force us to break a contract we are required to keep.
2. **The join does not exist.** `--segment-speed-file` is keyed by
   `from_osm_node_id,to_osm_node_id`. Learned facts are keyed by **coordinate
   geometry**, deliberately — ADR-0003 keeps routing and learning from having to
   agree on IDs. Bridging them means building and maintaining a coord→OSM-node-id
   table from the 182 MB `qatar.osm`, a new silent-failure surface of its own.
3. **The data on disk is CH.** `.hsgr` is present; `.partition`, `.cells` and
   `.mldgr` are not. MLD means re-running the pipeline.
4. Re-customization latency (minutes) is *acceptable* against the promotion
   schedule — this is the one point in MLD's favour, and points 1 and 2 sink it
   regardless.

### (b) CH for the default route, Python for all learned queries — **rejected**

Simple and honest, but it makes the differentiator a slow opt-in. A learned query
costs ~3.8 s, so nothing in the product would call it by default, the `/eta`
split would stop receiving learned samples, and issue 07 would become
unfalsifiable — the same outcome as W49, reached deliberately instead of by
accident.

### (c) CH for the path, learned overlay for the ETA — **chosen**

The insight that decides it: **issue 07's falsifiable claim is about ETA error,
not path choice.** The metric the project actually measures — predicted vs
observed duration, split by learned coverage — is fully preserved under CH,
because durations are computed after the path is chosen. What is lost is
learned-*weighted path selection*, which is the part CH genuinely cannot do, and
which remains reachable at `engine=python`.

This is also what `/navigate` has done since Wave 26c: it searches the base graph
and applies learned speeds as an ETA hook, because re-wrapping every relaxation
made the search explode. The fast path generalizes an existing, already-accepted
trade-off rather than inventing a new one.

## Consequences

**Gained** (measured, ten fixed pairs, full Qatar graph):

| | before | after | |
| --- | --- | --- | --- |
| `/route` median | 3 237 ms | **10.8 ms** | ~300× |

**Kept:** the learned overlay still changes quoted durations and still reports
`learned_coverage`, so the `/eta` split keeps receiving learned samples. The
Python engine is untouched and remains the fallback, the traffic-aware engine,
and the learned-weighted-search engine.

**Lost, and stated plainly:** on the fast path the learned overlay **cannot route
around a road it has learned is slow** — it can only report that the chosen road
will take longer. A road learned to be slow will still be selected by CH if
`car.lua` thinks it is quick. Recovering that requires `engine=python` and ~3.8 s,
or re-contracting with learned weights baked in (a batch job, not a request-time
capability).

**Operational:** `/learned` now reports `osrm_engine`, and `/route` and
`/navigate` set `X-Vector-Route-Engine: osrm|python`. Both exist because "routes
are fast" and "routes are still learned" are separate facts that used to be
un-observable together — which is precisely how W49's regression survived.
