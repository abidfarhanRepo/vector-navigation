# ADR-0047 — Logistics Time-Window VRP (VRPTW)

- **Status:** Accepted
- **Date:** 2026-07-15
- **Deciders:** d2-product (vector-logistics), d1-platform (vector-bus), d3-architecture (governance), d7-docs (governance)
- **Supersedes:** none
- **Superseded by:** none
- **Decided by:** adr-0046 (and adr-0045 / adr-0044 / adr-0043)

## Context

adr-0046 ("Logistics capacitated VRP (CVRP)") extended `vector-logistics` **in place** from
multi-vehicle VRP-lite (adr-0045) to Capacitated VRP (CVRP) — honoring two optional, additive
request fields (`capacities` and `demands`) and extending each result route with `load`/`capacity`.
That contract is **time-unaware**: every stop is served the instant the tour reaches it, so a route
may arrive at a stop far outside the window when the customer can actually receive the delivery. Real
logistics workloads have **time windows** — each stop is open for service only between `[open, close]`
epoch seconds, and each service takes a `service_time` seconds — and the schedule must report whether
the committed tour meets those windows.

Wave 33 extends the existing `vector-logistics` vertical **in place** from Capacitated VRP (CVRP) to
**Time-Window VRP (VRPTW)** — adding three optional, additive request fields (`time_windows`,
`service_times`, `start_time`) and three result fields (`routes[].arrivals`, `routes[].violations`,
top-level `window_violations` + echoed `start_time`). It is an extension of the existing repo/agent,
not a new repo: it reuses the same agent address, the same 5 bus channels, the same envelope, and the
same HTTP surface established by adr-0043 / adr-0044 / adr-0045 / adr-0046. No `vector-bus` core
change is permitted (hard constraint carried from adr-0043 / adr-0044 / adr-0045 / adr-0046).

## Decision

`vector-logistics` is extended in place to support Time-Window VRP (VRPTW). The contract is fixed
below and MUST be matched exactly by the `vector-logistics` implementation (no bus-core change):

**Bus / envelope.** E2 `vector-bus` at `:8090`. Every envelope carries the required fields: `id,
correlation_id, from, to, intent (TASK|ASSIGN|REVIEW|ESCALATE|NOTIFY|CONTRACT), priority (P0|P1|P2|P3),
sla_ms (number), timestamp (ISO8601), payload (object)`. The five existing channels are reused:
`#tasks #events #contracts #escalations #broadcast`. No new channel is added and no `vector-bus` core
edit is required.

**Logistics agent address (unchanged).**
`LOGISTICS_AGENT_ADDRESS = "agent://logistics.vector-01"`. No new agent address is introduced; the
VRPTW capability is served by the same address, on the same `#tasks`/`#events` channels, with the same
envelope as adr-0044 / adr-0045 / adr-0046.

**Logistics REQUEST (extended)** — published to channel `#tasks`:
```json
{
  "intent": "TASK",
  "to": "agent://logistics.vector-01",
  "from": "<requester>",
  "priority": "P2",
  "sla_ms": 8000,
  "payload": {
    "kind": "logistics",
    "stops": [[lat, lon], ...],
    "return_to_start": true,
    "fixed_order": false,
    "profile": "car",
    "vehicles": 1,
    "depots": [[lat, lon], ...],
    "balanced": false,
    "capacities": [<number>, ...],
    "demands": [<number>, ...],
    "time_windows": [[open, close], ...],
    "service_times": [<number>, ...],
    "start_time": <number>,
    "reply_to": "<requester>"
  }
}
```
The request is extended with three **optional** VRPTW fields (additive, backward compatible with
adr-0044 / adr-0045 / adr-0046):
- `time_windows` — per-stop `[open, close]` epoch-seconds pairs (`length` must equal `stops.length`;
  each `open <= close`). A stop with no constraint is modeled as `[0, +Inf)`; absent OR empty `[]` →
  no time windows (legacy time-unaware behavior identical to adr-0046).
- `service_times` — per-stop service duration in seconds (`length` must equal `stops.length`; each
  `>= 0`). Absent OR empty `[]` → zero service time at every stop.
- `start_time` — tour start epoch seconds (`number >= 0`). Absent → `0` (epoch origin).

The full request/result schema is formalized in
`vector-contracts/schemas/logistics-request.schema.json` and
`vector-contracts/schemas/logistics-result.schema.json`.

**Logistics consumer behavior.** The logistics service subscribes to `#tasks`, filters messages where
`to == "agent://logistics.vector-01"` **AND** `payload.kind == "logistics"`, runs the CVRP+VRPTW
solver, then publishes the RESULT envelope described below. All other messages are ignored
(client-side filter on `to` + `kind`). When `time_windows`/`service_times` are absent/empty the solver
behaves **byte-for-byte identically to adr-0046** (capacity-aware sweep / nearest-depot CVRP, no time
schedule); the VRPTW time logic engages only when `time_windows` is present and non-empty. The solver
is a **self-contained stdlib-only Python VRP** (extending the adr-0046 solver):
- The distance-optimal assignment + per-vehicle tour (capacity-aware sweep / nearest-depot assignment
  + cost-matrix A\* + nearest-neighbor + 2-opt) is **unchanged** — VRPTW does NOT re-optimize the
  insertion order for windows in this wave.
- **Time schedule (VRPTW-lite, evaluate-and-report).** On the already-committed tour, each vehicle
  starts at `start_time` at its depot, then visits stops in the existing `order`. Travel time between
  consecutive stops is derived from the cost matrix (same matrix as the distance-optimal tour, so
  schedule and geometry stay consistent). At each stop the vehicle **arrives** at a computed epoch
  second; if it arrives **before** `open` it **waits** until `open` (no violation), then performs
  `service_time`; if it arrives **after** `close` it records a **window violation** (counted in
  `violations`/`window_violations`) and proceeds (no re-insertion). `arrivals[i]` for a stop equals its
  arrival epoch second (after any wait); the depot's `arrivals` entry is `null` (the route origin).
- **Per-vehicle load/capacity** — unchanged from adr-0046 (`load`, `capacity`).
**No external solver dependency** (no OR-Tools or similar), and **no sibling imports**.

**Infeasibility / validation.** The solver validates inputs up front and returns a canonical
`ok:false` result (no exception escape), extending the adr-0046 validation set:
- `"time_windows length must equal number of stops"` — `time_windows` present and
  `len(time_windows) != len(stops)`.
- `"service_times length must equal number of stops"` — `service_times` present and
  `len(service_times) != len(stops)`.
- `"time_windows must be [open, close] pairs"` — any `time_windows` entry is not a 2-element pair.
- `"time window open must be <= close"` — any `time_windows` entry has `open > close`.
- `"service_times must be non-negative"` — any `service_times` value `< 0`.
- (Carried from adr-0046: `"capacities length must be 1 or equal to vehicles"`,
  `"demands length must equal number of stops"`, `"demand must be non-negative"`,
  `"insufficient vehicle capacity for demands"`.)

**Single-vehicle, no-capacity, and no-time-window behavior is unchanged.** When `vehicles == 1`, or
when `capacities`/`demands` are absent/empty, or when `time_windows`/`service_times` are absent/empty,
the service behaves exactly as adr-0046's CVRP (or adr-0045's multi-vehicle VRP-lite, or adr-0044's
single-vehicle TSP-lite) — identical order, geojson, totals, `routes`/`vehicles` fields, and the new
`arrivals`/`violations`/`window_violations`/`start_time` fields default to their empty/none values. The
VRPTW logic only changes behavior when `time_windows` is present and non-empty.

**Logistics RESULT (extended)** — published to channel `#events`:
```json
{
  "intent": "NOTIFY",
  "from": "agent://logistics.vector-01",
  "to": "<reply_to>",
  "correlation_id": "<request correlation_id>",
  "priority": "P2",
  "sla_ms": 8000,
  "payload": {
    "kind": "logistics_result",
    "ok": true,
    "geojson": "<FeatureCollection: LineString [lon,lat] + Point features>",
    "order": [int, ...],
    "total_distance_km": <number>,
    "total_duration_min": <number>,
    "routes": [
      { "vehicle": <int>, "order": [int, ...], "distance_km": <number>, "duration_min": <number>, "load": <number>, "capacity": <number|null>, "arrivals": [<int|null>, ...], "violations": <int> }
    ],
    "vehicles": <int>,
    "window_violations": <int>,
    "start_time": <number>,
    "error": null
  }
}
```
The result is extended with the VRPTW fields (additive, backward compatible with adr-0046):
- `routes[].arrivals` — per-stop arrival epoch seconds, **parallel to** `routes[].order` (same index
  space; `arrivals[j]` is the arrival time at the stop `order[j]`); the depot entry is `null`.
- `routes[].violations` — number of stops on that vehicle's route whose arrival (after wait) fell
  after `close` (window violations).
- top-level `window_violations` — sum of all `routes[].violations` (the total window violations across
  the fleet).
- top-level `start_time` — the request `start_time` echoed back (default `0` when absent).

On failure the result is `{ "ok": false, "geojson": null, "order": [],
"total_distance_km": 0, "total_duration_min": 0, "routes": [], "vehicles": 0,
"window_violations": 0, "start_time": 0, "error": "<canonical msg>" }`. The consumer MUST echo the
request's `correlation_id` so the requester can correlate the result to its task. The result is
published to the **explicit `#events` channel** (not routed via `ChannelRouter`) so the requesting
agent receives it on its `#events` subscription.

The decision to **reuse the existing envelope, the existing 5 channels, the existing agent address, and
the existing `/logistics` HTTP surface** (no new channel, no new agent, no new repo, no new nginx
route, no bus-core edit) minimizes blast radius and keeps the VRPTW capability fully backward
compatible with adr-0044 / adr-0045 / adr-0046. This wave is deliberately **VRPTW-lite**: it evaluates
and reports the time schedule on the distance-optimal tour but does **not** yet re-optimize the tour
insertion order for windows (window-optimizing insertion is explicit future work).

## Consequences

- `vector-logistics` is extended **in place** — no new repo, no new service, and no new agent address
  are registered. The registry `vector-logistics` repo `purpose` already mentions Capacitated VRP
  (adr-0046); VRPTW is a further additive extension of the same repo/agent/service, so
  **`registry.yaml`, `kg/index.json`, and `vector-registry/data/registry.json` are left UNCHANGED** —
  no new nodes or edges are added, and the governance validator therefore stays GREEN (0 errors /
  0 warnings). The existing `kg://adr/0046` node + edges remain the only governance graph entries for
  the logistics capability (this ADR is its in-place extension).
- The Viewer/logistics panel needs **no new route**: VRPTW reuses the existing `/logistics` endpoint
  (and the same `agent://logistics.vector-01` bus path). The viewer logistics panel gains the VRPTW
  inputs (per-stop `time_windows` + `service_times` and a `start_time`) and renders the new
  `routes[].arrivals` + `routes[].violations` + top-level `window_violations` read-only results
  alongside the existing load/capacity display.
- The contract is formalized as adr-0047 (this record) and backed by the extension of the two
  JSON-Schema files in `vector-contracts/schemas/`:
  `logistics-request.schema.json` (optional `time_windows`/`service_times`/`start_time`) and
  `logistics-result.schema.json` (optional `routes[].arrivals`/`routes[].violations`
  + top-level `window_violations`/`start_time`), plus the corresponding DTO examples and ajv tests.
- No `vector-bus` core change is required — the existing envelope, intents, and 5 channels are
  sufficient; the result is published to the explicit `#events` channel.
- The VRPTW solver remains a **self-contained stdlib-only Python** engine (extending adr-0046's
  capacity-aware sweep / nearest-depot assignment + per-vehicle cost-matrix A\* + nearest-neighbor +
  2-opt, now also evaluating the wait-if-early / violation-if-late time schedule and emitting per-route
  `arrivals`/`violations` + top-level `window_violations`/`start_time`), preserving the
  zero-sibling-import, no-external-solver constraint of adr-0025 / adr-0044 / adr-0046; bounded runtime
  suitable for the `sla_ms` budget.
- **Backward compatibility is guaranteed**: viewers/consumers that read only the top-level
  `order`/`geojson`/`total_distance_km`/`total_duration_min` keep working unchanged; `vehicles == 1`
  (or `capacities`/`demands` omitted, or `time_windows`/`service_times` omitted) yields behavior
  identical to adr-0046's CVRP. The added `time_windows`/`service_times`/`start_time`/`arrivals`/
  `violations`/`window_violations` fields are strictly additive. When none of the time fields are
  supplied, the result is **byte-for-byte identical to CVRP** (adr-0046).
- **VRPTW-lite scope (honest):** this wave evaluates and reports the time schedule on the existing
  distance-optimal tour; it does **not** yet re-order stops to minimize window violations (window-
  optimizing insertion is future work, see Alternatives). The schedule is therefore a faithful
  evaluation of the committed tour under the given windows, not a window-optimized solution.
- **Acceptance = the verification gate** (`node run-ci.mjs --repo vector-logistics` and
  `node run-ci.mjs --repo vector-contracts` in the workspace,
  `node vector-governance/scripts/validate-registry-kg.mjs`, and
  `node vector-infra/scripts/validate.mjs`) is GREEN.
- **Honest environment caveat:** in the current environment Docker + Python are unavailable (no real
  Python interpreter; the MSVC/Store stub cannot run the `vector-logistics` suite), so the authoritative
  Python unit gate (`node run-ci.mjs --repo vector-logistics` executing the containerized
  `unittest discover`) is **DEFERRED to a Docker-up Code-Agent run**. The Node-side
  `vector-contracts` ajv/DTO gate (`node --test` on the schema + DTO-example assertions) **IS runnable
  locally** and is the part of the gate that can be executed here. The governance validator is expected
  to report 0 errors / 0 warnings with **no registry/KG change** (this ADR adds no edges/nodes).

## Alternatives considered

- **Spin up a new `vector-vrptw` repo + a new agent address.** Rejected: VRPTW is a natural
  generalization of CVRP (VRP ⊇ CVRP ⊇ VRPTW), so it belongs in the same repo/agent. A new repo would
  fork the contract and duplicate the bus client, the HTTP surface, and the viewer panel for no
  functional gain (it is an extension, not a new vertical).
- **Add a new bus channel (e.g. `#logistics-vrptw`).** Rejected: it expands blast radius across all bus
  consumers and the bus server, and there is no functional need — the existing `#tasks`/`#events`
  channels already model request/result cleanly (same rationale as adr-0043 / adr-0044 / adr-0045 /
  adr-0046).
- **Make `time_windows`/`service_times` required.** Rejected: it would break every existing caller
  (single-vehicle, multi-vehicle, and CVRP) and discard the legacy time-unaware behavior; the
  additive-optional design keeps the no-time-windows path byte-for-byte identical to adr-0046.
- **Full window-optimizing insertion solver.** Rejected for this wave: it would replace the proven
  distance-optimal tour with a window-aware re-insertion/reorder (a substantially larger solver change
  and a new optimization objective). This wave deliberately ships **VRPTW-lite** — evaluate-and-report
  the time schedule on the existing tour — as an in-place, low-blast-radius extension; window-optimizing
  insertion is explicit future work.

## References

- adr-0046-logistics-cvrp.md — Logistics capacitated VRP (CVRP) (the contract this ADR extends;
  DECIDED_BY adr-0046).
- adr-0045-logistics-vrp-multivehicle.md — Logistics multi-vehicle VRP-lite (the contract this ADR
  ultimately extends; DECIDED_BY adr-0045).
- adr-0044-logistics-bus-consumer.md — Logistics bus consumer (the single-vehicle contract this ADR
  ultimately extends; DECIDED_BY adr-0044).
- adr-0043-routing-bus-consumer.md — Routing bus consumer (the pattern language reused: agent address,
  5 channels, envelope reuse, correlation_id echo, VECTOR_BUS_URL gating, no bus-core change; DECIDED_BY
  adr-0043).
- vector-contracts/schemas/logistics-request.schema.json — logistics request envelope schema (extended
  with `time_windows`/`service_times`/`start_time`).
- vector-contracts/schemas/logistics-result.schema.json — logistics result envelope schema (extended
  with `routes[].arrivals`/`routes[].violations` + top-level `window_violations`/`start_time`).
