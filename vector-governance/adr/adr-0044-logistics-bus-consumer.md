# ADR-0044 — Logistics bus consumer

- **Status:** Accepted
- **Date:** 2026-07-14
- **Deciders:** d2-product (vector-logistics), d1-platform (vector-bus), d3-architecture (governance), d7-docs (governance)
- **Supersedes:** none
- **Superseded by:** none

## Context

adr-0043 ("Routing bus consumer") turned `vector-routing` into a first-class E2
consumer and, in its Consequences section, explicitly anticipated the next vertical:
*"future live graph/traffic deltas and logistics multi-stop requests can be dispatched
as bus tasks without touching the HTTP surface."* This ADR realizes that anticipated
vertical.

Wave 24/adr-0043 established the pattern: designate an agent address on the bus,
publish a `…request` task on `#tasks` (intent `TASK`), compute via the existing engine,
and publish a `…result` event on `#events` (intent `NOTIFY`) with the request's
`correlation_id` echoed. The E2 Event Bus (`vector-bus`, adr-0029) is already
operational at `:8090` with a validated envelope, 5 channels
(`#tasks #events #contracts #escalations #broadcast`), and the `VECTOR_BUS_URL`-gated
`LocalBus`/`NetworkBusClient` adapter pattern.

The subject of this ADR is `vector-logistics`: a **new repo** (per adr-0043's
Consequences) that acts as an E2 bus consumer for **multi-stop logistics routing** —
given an ordered/unordered set of stops, compute a cost-optimal tour and return a
GeoJSON LineString + waypoint features plus total distance/duration. It mirrors the
routing consumer exactly but generalizes from two endpoints (from/to) to N stops.

## Decision

`vector-logistics` becomes an E2 Event Bus consumer. The contract is fixed below and
MUST be matched exactly by the `vector-logistics` implementation (no bus-core change):

**Bus / envelope.** E2 `vector-bus` at `:8090`. Every envelope carries the required
fields: `id, correlation_id, from, to, intent (TASK|ASSIGN|REVIEW|ESCALATE|NOTIFY|CONTRACT),
priority (P0|P1|P2|P3), sla_ms (number), timestamp (ISO8601), payload (object)`.
The five existing channels are reused: `#tasks #events #contracts #escalations #broadcast`.
No new channel is added and no `vector-bus` core edit is required.

**Logistics agent address.**
`LOGISTICS_AGENT_ADDRESS = "agent://logistics.vector-01"`.

**Logistics REQUEST** — published to channel `#tasks`:
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
    "reply_to": "<requester>"
  }
}
```
`stops` is an array of `[lat, lon]` pairs with `minItems: 2`. The full request/result
schema is formalized in
`vector-contracts/schemas/logistics-request.schema.json` and
`vector-contracts/schemas/logistics-result.schema.json`.

**Logistics consumer behavior.** The logistics service subscribes to `#tasks`, filters
messages where `to == "agent://logistics.vector-01"` **AND** `payload.kind == "logistics"`,
runs the tour solver (cost-matrix + nearest-neighbor seed + 2-opt improvement — a
TSP-lite), then publishes the RESULT envelope described below. All other messages are
ignored (client-side filter on `to` + `kind`).

**Logistics RESULT** — published to channel `#events`:
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
    "error": null
  }
}
```
On failure the result is `{ "ok": false, "geojson": null, "order": [],
"total_distance_km": 0, "total_duration_min": 0, "error": "<msg>" }`. The consumer MUST
echo the request's `correlation_id` so the requester can correlate the result to its
task. The result is published to the **explicit `#events` channel** (not routed via
`ChannelRouter`) so the requesting agent receives it on its `#events` subscription.

**Wiring is env-gated by `VECTOR_BUS_URL`.**
- **Unset** → an in-process `LocalBus` double is used: the HTTP `/logistics` endpoint
  stays available and local development behaves exactly as before (no bus dependency).
- **Set** → a `NetworkBusClient` is used: `urllib`-based publish to `:8090` plus a
  background SSE subscribe thread with ack, mirroring how the existing E1–E9 agent
  repos and `vector-routing` select the bus via `VECTOR_BUS_URL`.

**Reconnection handling.** The `NetworkBusClient` adapter MUST implement reconnection
with backoff on SSE drop: on connection loss it re-subscribes to `#tasks` and replays
any un-acked in-flight correlation ids, so transient bus/server restarts do not drop
logistics tasks.

**HTTP fallback.** `GET /logistics` is also served over HTTP on `:8088` (proxied via the
tile-server nginx `:8080` as the logistics-overlay endpoint), so the live viewer can
request a tour directly without the bus. The bus path and the HTTP path share the same
`logistics-svc` tour solver, so results are identical.

The decision to **reuse the existing envelope and the existing 5 channels** (no new
channel, no bus-core edit) minimizes blast radius; `agent://logistics.vector-01` is
designated as the logistics service address on the bus.

## Consequences

- A new repo `vector-logistics` (owner squad `d2-product`) is registered in
  `registry.yaml` and mirrored as `kg://repo/vector-logistics` in `kg/index.json`, with
  service nodes `kg://service/logistics-svc` and `kg://service/logistics-http-svc`.
- `vector-logistics → vector-bus` (consumes, runtime) and
  `vector-tile-server → vector-logistics` (runtime, proxies `/logistics` and publishes
  logistics tasks) dependency edges are recorded in both `registry.yaml` and the KG.
- The contract is formalized as adr-0044 (this record) and backed by two JSON-Schema
  files in `vector-contracts/schemas/`: `logistics-request.schema.json` and
  `logistics-result.schema.json`.
- No `vector-bus` core change is required — the existing envelope, intents, and 5
  channels are sufficient; the result is published to the explicit `#events` channel.
- `vector-logistics` gains a stdlib-only Python bus client dependency
  (`NetworkBusClient` over `urllib` + SSE, mirroring the TS `NetworkBusClient` and
  `vector-routing`s client) used only when `VECTOR_BUS_URL` is set; when unset it uses
  the in-process `LocalBus` double and the HTTP endpoint is unchanged, preserving
  local-dev behavior and the self-contained, zero-sibling-import constraint.
- The tour solver is a pragmatic TSP-lite: distance/time **cost-matrix** + **nearest-
  neighbor** seed tour + **2-opt** improvement. No external solver dependency; bounded
  runtime suitable for the `sla_ms` budget.
- Acceptance criteria = the verification gate (`node run-ci.mjs` in the workspace and
  `node vector-governance/scripts/validate-registry-kg.mjs`) is GREEN.

## Alternatives considered

- **Add a new `#logistics` channel plus a `vector-bus` core edit.** Rejected: it expands
  blast radius across all bus consumers and the bus server, and there is no functional
  need — the existing `#tasks`/`#events` channels model request/result cleanly (same
  rationale as adr-0043).
- **Reuse the routing service for multi-stop.** Rejected: routing models exactly two
  endpoints (from/to); multi-stop tour solving (N stops, return-to-start, fixed-order)
  is a distinct concern that warrants its own repo/agent address per the polyrepo
  convention (adr-0001) and adr-0043's anticipated vertical.
- **Use a heavyweight TSP solver (e.g. OR-Tools).** Rejected: adds a native/runtime
  dependency that breaks the self-contained Python constraint; the TSP-lite
  (nearest-neighbor + 2-opt) is sufficient for the expected stop counts and SLA.

## References

- adr-0029-networked-event-bus.md — Networked E2 Event Bus (HTTP+SSE, port 8090, `NetworkBusClient`).
- adr-0043-routing-bus-consumer.md — Routing bus consumer (the pattern this ADR extends; DECIDED_BY adr-0043).
- vector-contracts/schemas/logistics-request.schema.json — logistics request envelope schema.
- vector-contracts/schemas/logistics-result.schema.json — logistics result envelope schema.
- adr-0025 / adr-0026 / adr-0042 — M2 routing engine, route-overlay, navigation-overlay (sibling vertical).
