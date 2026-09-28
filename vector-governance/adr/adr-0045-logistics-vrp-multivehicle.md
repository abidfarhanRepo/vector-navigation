# ADR-0045 — Logistics multi-vehicle VRP-lite

- **Status:** Accepted
- **Date:** 2026-07-14
- **Deciders:** d2-product (vector-logistics), d1-platform (vector-bus), d3-architecture (governance), d7-docs (governance)
- **Supersedes:** none
- **Superseded by:** none
- **Decided by:** adr-0044 (and adr-0043)

## Context

adr-0044 ("Logistics bus consumer") made `vector-logistics` a first-class E2 bus
consumer for **single-vehicle multi-stop routing** — given an ordered/unordered set of
stops, it computes a cost-optimal tour (TSP-lite: cost-matrix + nearest-neighbor seed +
2-opt improvement) and publishes a GeoJSON LineString + waypoint features plus total
distance/duration on `#events`. That vertical models exactly one vehicle visiting every
stop.

Real logistics workloads are rarely single-vehicle: a fleet of vehicles, each with a
start depot and a balanced workload target, must jointly serve a set of stops. This is the
classic **Vehicle Routing Problem (VRP)**. Wave 29 extends the existing `vector-logistics`
vertical **in place** from single-vehicle TSP-lite to **multi-vehicle VRP-lite**
(fleet size, depots, balanced/nearest-depot assignment, and one route per vehicle).

This is an **extension of the existing repo/agent**, not a new repo: it reuses the same
agent address, the same 5 bus channels, and the same envelope established by adr-0043 /
adr-0044. The E2 Event Bus (`vector-bus`, adr-0029) is already operational at `:8090`
with a validated envelope, 5 channels (`#tasks #events #contracts #escalations
#broadcast`), and the `VECTOR_BUS_URL`-gated `LocalBus`/`NetworkBusClient` adapter
pattern. No `vector-bus` core change is permitted (hard constraint carried from
adr-0043 / adr-0044).

## Decision

`vector-logistics` is extended in place to support multi-vehicle VRP-lite. The contract
is fixed below and MUST be matched exactly by the `vector-logistics` implementation
(no bus-core change):

**Bus / envelope.** E2 `vector-bus` at `:8090`. Every envelope carries the required
fields: `id, correlation_id, from, to, intent (TASK|ASSIGN|REVIEW|ESCALATE|NOTIFY|CONTRACT),
priority (P0|P1|P2|P3), sla_ms (number), timestamp (ISO8601), payload (object)`.
The five existing channels are reused: `#tasks #events #contracts #escalations #broadcast`.
No new channel is added and no `vector-bus` core edit is required.

**Logistics agent address (unchanged).**
`LOGISTICS_AGENT_ADDRESS = "agent://logistics.vector-01"`. No new agent address is
introduced; the multi-vehicle capability is served by the same address, on the same
`#tasks`/`#events` channels, with the same envelope as adr-0044.

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
    "reply_to": "<requester>"
  }
}
```
The request is extended with three **optional** fields (all additive, all backward
compatible with adr-0044):
- `vehicles` — integer `≥ 1`, default `1` (fleet size).
- `depots` — `[[lat, lon], ...]` start locations, `length == vehicles` (one depot per vehicle).
- `balanced` — boolean, default `false`. When `true`, stops are assigned to vehicles by a
  **sweep** assignment (balanced workload); when `false`, each stop is assigned to its
  **nearest depot** (unbalanced).

The full request/result schema is formalized in
`vector-contracts/schemas/logistics-request.schema.json` and
`vector-contracts/schemas/logistics-result.schema.json`.

**Logistics consumer behavior.** The logistics service subscribes to `#tasks`, filters
messages where `to == "agent://logistics.vector-01"` **AND** `payload.kind == "logistics"`,
runs the fleet solver, then publishes the RESULT envelope described below. All other
messages are ignored (client-side filter on `to` + `kind`). The solver is a
**self-contained stdlib-only Python VRP-lite**:
- **Assignment** — `balanced == true` uses a **sweep** assignment (stops sorted by polar
  angle around the fleet centroid, partitioned evenly across vehicles for balanced
  workloads); `balanced == false` assigns each stop to its **nearest depot** (unbalanced).
- **Per-vehicle tour** — each vehicle's assigned stops are solved as a TSP-lite by reusing
  the existing **cost-matrix A\*** (distance/time matrix) + **nearest-neighbor** seed +
  **2-opt** improvement already used by adr-0044.
**No external solver dependency** (no OR-Tools or similar), and **no sibling imports**.

**Single-vehicle behavior is unchanged.** When `vehicles == 1` (including the adr-0044
default when `vehicles`/`depots`/`balanced` are omitted), the service behaves exactly
as the single-vehicle TSP-lite of adr-0044 — identical order, geojson, and totals. The
multi-vehicle logic only engages for `vehicles > 1`.

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
      { "vehicle": <int>, "order": [int, ...], "distance_km": <number>, "duration_min": <number> }
    ],
    "vehicles": <int>,
    "error": null
  }
}
```
The result is extended with two **optional** fields (additive, backward compatible):
- `routes` — array of `{ vehicle:int, order:int[], distance_km:number, duration_min:number }`
  (one entry per vehicle route).
- `vehicles` — integer count of vehicles actually assigned stops.

On failure the result is `{ "ok": false, "geojson": null, "order": [],
"total_distance_km": 0, "total_duration_min": 0, "routes": [], "vehicles": 0,
"error": "<msg>" }`. The consumer MUST echo the request's `correlation_id` so the
requester can correlate the result to its task. The result is published to the
**explicit `#events` channel** (not routed via `ChannelRouter`) so the requesting agent
receives it on its `#events` subscription.

The decision to **reuse the existing envelope, the existing 5 channels, and the existing
agent address** (no new channel, no new agent, no bus-core edit) minimizes blast radius
and keeps the multi-vehicle capability fully backward compatible with adr-0044.

## Consequences

- `vector-logistics` is extended **in place** — no new repo and no new service are
  registered. The `registry.yaml` `vector-logistics` repo `purpose` is updated to
  mention multi-vehicle VRP-lite (fleet size, depots, balanced/nearest-depot assignment,
  per-vehicle routes), and `kg://adr/0045` is added to `kg/index.json`
  (`DECIDED_BY kg://adr/0044`, `DECIDED_BY kg://adr/0043`, `IMPLEMENTS kg://repo/vector-logistics`).
- The Viewer/logistics panel and the tile-server nginx need **no new route**: VRP reuses
  the existing `/logistics` endpoint (and the same `agent://logistics.vector-01` bus
  path). The viewer logistics panel gains optional inputs for fleet size (`vehicles`),
  depots, and the balanced/unbalanced (`balanced`) toggle.
- The contract is formalized as adr-0045 (this record) and backed by the extension of
  the two JSON-Schema files in `vector-contracts/schemas/`:
  `logistics-request.schema.json` (optional `vehicles`/`depots`/`balanced`) and
  `logistics-result.schema.json` (optional `routes`/`vehicles`).
- No `vector-bus` core change is required — the existing envelope, intents, and 5
  channels are sufficient; the result is published to the explicit `#events` channel.
- The VRP-lite solver remains a **self-contained stdlib-only Python** engine
  (sweep / nearest-depot assignment + per-vehicle cost-matrix A\* + nearest-neighbor +
  2-opt), preserving the zero-sibling-import, no-external-solver constraint of adr-0044;
  bounded runtime suitable for the `sla_ms` budget.
- **Larger payloads and more compute** for multi-vehicle requests (one tour per vehicle,
  plus an assignment step); the `sla_ms` budget remains adequate for expected fleet/stop
  counts.
- **Backward compatibility is guaranteed**: viewers/consumers that read only the
  top-level `order`/`geojson`/`total_distance_km`/`total_duration_min` keep working
  unchanged; `vehicles == 1` (or the fields omitted) yields behavior identical to adr-0044's
  single-vehicle TSP-lite.
- This ADR **extends adr-0044** for the multi-vehicle capability (`DECIDED_BY adr-0044`,
  and `DECIDED_BY adr-0043` for the bus-consumer pattern); adr-0044's single-vehicle
  contract remains valid as the `vehicles == 1` case.
- Acceptance criteria = the verification gate (`node run-ci.mjs` in the workspace and
  `node vector-governance/scripts/validate-registry-kg.mjs`) is GREEN.

## Alternatives considered

- **Spin up a new `vector-vrp` repo + a new agent address.** Rejected: multi-vehicle is
  a natural generalization of the existing multi-stop tour (VRP ⊇ TSP), so it belongs in
  the same repo/agent. A new repo would fork the contract and duplicate the bus client,
  the HTTP surface, and the viewer panel for no functional gain (it is an extension, not
  a new vertical).
- **Add a new `#logistics-vrp` channel plus a `vector-bus` core edit.** Rejected: it
  expands blast radius across all bus consumers and the bus server, and there is no
  functional need — the existing `#tasks`/`#events` channels already model
  request/result cleanly (same rationale as adr-0043 / adr-0044).
- **Make `vehicles`/`depots`/`balanced` required.** Rejected: it would break every
  existing single-vehicle caller; the additive-optional design keeps `vehicles == 1`
  behavior identical to adr-0044.
- **Use a heavyweight VRP solver (e.g. OR-Tools).** Rejected: adds a native/runtime
  dependency that breaks the self-contained Python constraint of adr-0025 / adr-0044; the
  VRP-lite (sweep / nearest-depot assignment + cost-matrix A\* + nearest-neighbor + 2-opt)
  is sufficient for the expected fleet/stop counts and SLA.

## References

- adr-0044-logistics-bus-consumer.md — Logistics bus consumer (the single-vehicle
  contract this ADR extends; DECIDED_BY adr-0044).
- adr-0043-routing-bus-consumer.md — Routing bus consumer (the pattern language reused:
  agent address, 5 channels, envelope reuse, correlation_id echo, VECTOR_BUS_URL gating,
  no bus-core change; DECIDED_BY adr-0043).
- adr-0029-networked-event-bus.md — Networked E2 Event Bus (HTTP+SSE, port 8090, `NetworkBusClient`).
- adr-0025 / adr-0026 / adr-0042 — M2 routing engine, route-overlay, navigation-overlay (sibling vertical, stdlib-only constraint).
- vector-contracts/schemas/logistics-request.schema.json — logistics request envelope schema (extended with `vehicles`/`depots`/`balanced`).
- vector-contracts/schemas/logistics-result.schema.json — logistics result envelope schema (extended with `routes`/`vehicles`).
