# ADR-0046 — Logistics capacitated VRP (CVRP)

- **Status:** Accepted
- **Date:** 2026-07-14
- **Deciders:** d2-product (vector-logistics), d1-platform (vector-bus), d3-architecture (governance), d7-docs (governance)
- **Supersedes:** none
- **Superseded by:** none
- **Decided by:** adr-0045 (and adr-0044 / adr-0043)

## Context

adr-0045 ("Logistics multi-vehicle VRP-lite") extended `vector-logistics` **in place** from
single-vehicle TSP-lite (adr-0044) to multi-vehicle VRP-lite — adding the optional `vehicles`,
`depots`, and `balanced` request fields and the optional `routes`/`vehicles` result fields, with the
multi-vehicle logic engaging only for `vehicles > 1`. That contract is capacity-unaware: every vehicle
is treated as having unlimited capacity, so a route may accumulate more demand than any real vehicle can
carry. Real logistics workloads are **capacitated** — each vehicle has a finite capacity and each stop
has a demand, and the assignment must never exceed a vehicle's capacity.

Wave 30 extends the existing `vector-logistics` vertical **in place** from multi-vehicle VRP-lite to
**Capacitated VRP (CVRP)** — honoring two optional, additive request fields the contract + viewer
already advertise (`capacities` and `demands`) and extending each result route with `load`/`capacity`.
It is an extension of the existing repo/agent, not a new repo: it reuses the same agent address, the
same 5 bus channels, the same envelope, and the same HTTP surface established by adr-0043 / adr-0044 /
adr-0045. No `vector-bus` core change is permitted (hard constraint carried from adr-0043 / adr-0044 /
adr-0045).

## Decision

`vector-logistics` is extended in place to support Capacitated VRP (CVRP). The contract is fixed below
and MUST be matched exactly by the `vector-logistics` implementation (no bus-core change):

**Bus / envelope.** E2 `vector-bus` at `:8090`. Every envelope carries the required fields: `id,
correlation_id, from, to, intent (TASK|ASSIGN|REVIEW|ESCALATE|NOTIFY|CONTRACT), priority (P0|P1|P2|P3),
sla_ms (number), timestamp (ISO8601), payload (object)`. The five existing channels are reused:
`#tasks #events #contracts #escalations #broadcast`. No new channel is added and no `vector-bus` core
edit is required.

**Logistics agent address (unchanged).**
`LOGISTICS_AGENT_ADDRESS = "agent://logistics.vector-01"`. No new agent address is introduced; the
CVRP capability is served by the same address, on the same `#tasks`/`#events` channels, with the same
envelope as adr-0044 / adr-0045.

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
    "reply_to": "<requester>"
  }
}
```
The request is extended with two **optional** CVRP fields (additive, backward compatible with
adr-0044 / adr-0045):
- `capacities` — per-vehicle capacity list (`number ≥ 0`). `length == 1` broadcasts the single
  capacity to all vehicles; `length == vehicles` is used as-is; absent OR empty `[]` → unlimited
  capacity (legacy behavior identical to adr-0045).
- `demands` — per-stop demand list aligned to `stops` (`length` must equal `stops.length`; each
  `≥ 0`). The depot contributes `0` to a route's load (it is a start point, not a delivery). Absent →
  all-zero demands (no capacity constraint).

The full request/result schema is formalized in
`vector-contracts/schemas/logistics-request.schema.json` and
`vector-contracts/schemas/logistics-result.schema.json`.

**Logistics consumer behavior.** The logistics service subscribes to `#tasks`, filters messages where
`to == "agent://logistics.vector-01"` **AND** `payload.kind == "logistics"`, runs the CVRP solver, then
publishes the RESULT envelope described below. All other messages are ignored (client-side filter on
`to` + `kind`). When `capacities` is absent/empty the solver behaves **byte-for-byte identically to
adr-0045** (multi-vehicle VRP-lite: `balanced` → sweep, `balanced==false` → nearest-depot; no capacity
check); the CVRP capacity logic engages only when `capacities` is present and non-empty. The solver is a
**self-contained stdlib-only Python CVRP**:
- **Assignment** — `balanced == true` uses a capacity-aware **sweep** assignment (stops sorted by
  polar angle around the fleet centroid, then greedily filled into vehicles in angle order while each
  vehicle's accumulated demand stays within its capacity). `balanced == false` assigns each stop to its
  **nearest feasible depot** (largest-demand-first), skipping any depot whose remaining capacity cannot
  hold the stop's demand.
- **Per-vehicle tour** — each vehicle's assigned stops are solved as a TSP-lite by reusing the existing
  **cost-matrix A\*** (distance/time matrix) + **nearest-neighbor** seed + **2-opt** improvement already
  used by adr-0044 / adr-0045.
- **Per-vehicle load/capacity** — after assignment, each route records `load` (sum of its stops'
  demands) and `capacity` (the vehicle's capacity, or `null`/`unlimited` when capacities is absent).
**No external solver dependency** (no OR-Tools or similar), and **no sibling imports**.

**Infeasibility / validation.** The solver validates inputs up front and returns a canonical
`ok:false` result (no exception escape):
- `"capacities length must be 1 or equal to vehicles"` — `capacities` present and `len(capacities)` is
  neither `1` nor `vehicles`.
- `"demands length must equal number of stops"` — `demands` present and `len(demands) != len(stops)`.
- `"demand must be non-negative"` — any demand value `< 0`.
- `"insufficient vehicle capacity for demands"` — the total demand cannot be served within the supplied
  capacities (cannot be split across vehicles), or no feasible depot exists for a stop.

**Single-vehicle & no-capacity behavior is unchanged.** When `vehicles == 1` (including the adr-0044
default when `vehicles`/`depots`/`balanced`/`capacities`/`demands` are omitted), or when `capacities`
is absent/empty, the service behaves exactly as the multi-vehicle VRP-lite (or single-vehicle TSP-lite)
of adr-0044 / adr-0045 — identical order, geojson, totals, and `routes`/`vehicles` fields. The CVRP
logic only changes behavior when `capacities` is present and non-empty.

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
      { "vehicle": <int>, "order": [int, ...], "distance_km": <number>, "duration_min": <number>, "load": <number>, "capacity": <number|null> }
    ],
    "vehicles": <int>,
    "error": null
  }
}
```
The result is extended with two **optional** CVRP fields on each `routes[]` entry (additive, backward
compatible with adr-0045):
- `load` — number; the sum of `demands` carried by that vehicle's route (`0` when demands absent).
- `capacity` — number or `null`; the vehicle's capacity (`null` when `capacities` is absent/empty, i.e.
  unlimited, legacy behavior).

On failure the result is `{ "ok": false, "geojson": null, "order": [],
"total_distance_km": 0, "total_duration_min": 0, "routes": [], "vehicles": 0,
"error": "<canonical msg>" }`. The consumer MUST echo the request's `correlation_id` so the requester
can correlate the result to its task. The result is published to the **explicit `#events` channel** (not
routed via `ChannelRouter`) so the requesting agent receives it on its `#events` subscription.

The decision to **reuse the existing envelope, the existing 5 channels, the existing agent address, and
the existing `/logistics` HTTP surface** (no new channel, no new agent, no new repo, no new nginx
route, no bus-core edit) minimizes blast radius and keeps the CVRP capability fully backward compatible
with adr-0044 / adr-0045.

## Consequences

- `vector-logistics` is extended **in place** — no new repo, no new service, and no new agent address
  are registered (the registry `vector-logistics` repo `purpose` is updated to mention Capacitated VRP:
  optional per-vehicle `capacities` + per-stop `demands`, capacity-aware sweep / nearest-depot, and
  per-vehicle `load`; `vehicles == 1` & no-capacities unchanged, adr-0046). `kg://adr/0046` is added to
  `kg/index.json` (`IMPLEMENTS kg://repo/vector-logistics`, `DECIDED_BY kg://adr/0045`,
  `DECIDED_BY kg://adr/0044`).
- The Viewer/logistics panel and the tile-server nginx need **no new route**: CVRP reuses the existing
  `/logistics` endpoint (and the same `agent://logistics.vector-01` bus path). The viewer logistics
  panel already advertises optional `capacities`/`demands` inputs; the result `routes[].load`/`capacity`
  are read-only additions.
- The contract is formalized as adr-0046 (this record) and backed by the extension of the two
  JSON-Schema files in `vector-contracts/schemas/`:
  `logistics-request.schema.json` (optional `capacities`/`demands`) and
  `logistics-result.schema.json` (optional `routes[].load`/`routes[].capacity`).
- No `vector-bus` core change is required — the existing envelope, intents, and 5 channels are
  sufficient; the result is published to the explicit `#events` channel.
- The CVRP solver remains a **self-contained stdlib-only Python** engine (capacity-aware sweep /
  nearest-depot assignment + per-vehicle cost-matrix A\* + nearest-neighbor + 2-opt, emitting per-route
  `load`/`capacity`), preserving the zero-sibling-import, no-external-solver constraint of adr-0025 /
  adr-0044; bounded runtime suitable for the `sla_ms` budget.
- **Backward compatibility is guaranteed**: viewers/consumers that read only the top-level
  `order`/`geojson`/`total_distance_km`/`total_duration_min` keep working unchanged; `vehicles == 1`
  (or `capacities` omitted) yields behavior identical to adr-0045's multi-vehicle VRP-lite (and, for
  `vehicles == 1` with no `capacities`, identical to adr-0044's single-vehicle TSP-lite). The added
  `capacities`/`demands`/`load`/`capacity` fields are strictly additive.
- This ADR **extends adr-0045** for the capacitated capability (`DECIDED_BY adr-0045`, `DECIDED_BY
  adr-0044`, and `DECIDED_BY adr-0043` for the bus-consumer pattern); adr-0045's multi-vehicle
  contract remains valid as the no-`capacities` case.
- Acceptance criteria = the verification gate (`node run-ci.mjs --repo vector-logistics` and
  `node run-ci.mjs --repo vector-contracts` in the workspace, `node vector-governance/scripts/validate-registry-kg.mjs`,
  and `node vector-infra/scripts/validate.mjs`) is GREEN.

## Alternatives considered

- **Spin up a new `vector-cvrp` repo + a new agent address.** Rejected: CVRP is a natural
  generalization of the existing multi-vehicle VRP-lite (VRP ⊇ CVRP), so it belongs in the same
  repo/agent. A new repo would fork the contract and duplicate the bus client, the HTTP surface, and
  the viewer panel for no functional gain (it is an extension, not a new vertical).
- **Add a new `#logistics-cvrp` channel plus a `vector-bus` core edit.** Rejected: it expands blast
  radius across all bus consumers and the bus server, and there is no functional need — the existing
  `#tasks`/`#events` channels already model request/result cleanly (same rationale as adr-0043 /
  adr-0044 / adr-0045).
- **Make `capacities`/`demands` required.** Rejected: it would break every existing caller (single-
  vehicle and multi-vehicle VRP-lite) and discard the legacy unlimited-capacity behavior; the additive-
  optional design keeps the no-`capacities` path byte-for-byte identical to adr-0045.
- **Use a heavyweight VRP solver (e.g. OR-Tools).** Rejected: adds a native/runtime dependency that
  breaks the self-contained Python constraint of adr-0025 / adr-0044; the CVRP (capacity-aware sweep /
  nearest-depot assignment + cost-matrix A\* + nearest-neighbor + 2-opt, with per-route `load`/
  `capacity`) is sufficient for the expected fleet/stop counts and SLA.

## References

- adr-0045-logistics-vrp-multivehicle.md — Logistics multi-vehicle VRP-lite (the contract this ADR
  extends; DECIDED_BY adr-0045).
- adr-0044-logistics-bus-consumer.md — Logistics bus consumer (the single-vehicle contract this ADR
  ultimately extends; DECIDED_BY adr-0044).
- adr-0043-routing-bus-consumer.md — Routing bus consumer (the pattern language reused: agent address,
  5 channels, envelope reuse, correlation_id echo, VECTOR_BUS_URL gating, no bus-core change; DECIDED_BY
  adr-0043).
- adr-0029-networked-event-bus.md — Networked E2 Event Bus (HTTP+SSE, port 8090, `NetworkBusClient`).
- adr-0025 / adr-0026 / adr-0042 — M2 routing engine, route-overlay, navigation-overlay (sibling
  vertical, stdlib-only constraint).
- vector-contracts/schemas/logistics-request.schema.json — logistics request envelope schema (extended
  with `capacities`/`demands`).
- vector-contracts/schemas/logistics-result.schema.json — logistics result envelope schema (extended
  with `routes[].load`/`routes[].capacity`).
