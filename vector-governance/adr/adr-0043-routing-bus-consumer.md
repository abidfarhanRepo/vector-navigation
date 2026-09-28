# ADR-0043 — Routing bus consumer

- **Status:** Accepted
- **Date:** 2026-07-14
- **Deciders:** d2-product (vector-routing), d1-platform (vector-bus), d3-architecture (governance), d7-docs (governance)
- **Supersedes:** none
- **Superseded by:** none

## Context

The M2 Navigation/Routing engine (`vector-routing`, adr-0025) is today a **purely
synchronous HTTP calculator**: it builds a routing graph from GeoJSON way features in
memory and answers `GET /route` (adr-0026) and `GET /navigate` (adr-0042) over
HTTP. Every caller — the M1 tile-server viewer, the global overlay, etc. — reaches it
via direct HTTP. There is no event-driven path, no correlation id, and no way for a
fleet agent to dispatch a navigation task and receive a structured result on a bus.

Meanwhile the E2 Event Bus (`vector-bus`, adr-0029) is already operational at
`:8090`, with a validated envelope, 5 channels (`#tasks #events #contracts
#escalations #broadcast`), and a `NetworkBusClient` adapter pattern that the E1–E9
agent repos (e.g. `vector-coa-fleet/src/index.js`, `vector-kg-ingest/src/index.js`)
already use to select between a local in-process bus and the networked bus via the
`VECTOR_BUS_URL` env var.

Wave 24 (Session 32) delivered turn-by-turn ETA navigation to the deployed viewer.
The natural next step — and the subject of this ADR — is to make `vector-routing` a
**first-class E2 consumer**: able to receive a navigation *task* on the bus, compute
the route via its existing `RoutingService`, and publish a structured
`navigation_result` event back on the bus. This turns routing from a stateless
request/response HTTP endpoint into a stateful, event-driven service without
changing the engine's core algorithms.

## Decision

`vector-routing` becomes an E2 Event Bus consumer. The contract is fixed below and
MUST be matched exactly by the `vector-routing` implementation (no bus-core change):

**Bus / envelope.** E2 `vector-bus` at `:8090`. Every envelope carries the required
fields: `id, correlation_id, from, to, intent (TASK|ASSIGN|REVIEW|ESCALATE|NOTIFY|CONTRACT),
priority (P0|P1|P2|P3), sla_ms (number), timestamp (ISO8601), payload (object)`.
Five existing channels are reused: `#tasks #events #contracts #escalations #broadcast`.
No new channel is added and no `vector-bus` core edit is required.

**Routing agent address.**
`ROUTING_AGENT_ADDRESS = "agent://routing.vector-01"`.

**Navigation REQUEST** — published to channel `#tasks`:
```json
{
  "intent": "TASK",
  "to": "agent://routing.vector-01",
  "from": "<requester>",
  "priority": "P2",
  "sla_ms": 5000,
  "payload": {
    "kind": "navigate",
    "from_ll": [lat, lon],
    "to_ll": [lat, lon],
    "via": [[lat, lon], ...] | null,
    "profile": "car",
    "reply_to": "<requester>"
  }
}
```

**Routing consumer behavior.** The routing service subscribes to `#tasks`, filters
messages where `to == "agent://routing.vector-01"` **AND** `payload.kind == "navigate"`,
runs `RoutingService.navigate(...)`, then publishes the RESULT envelope described
below. All other messages are ignored.

**Navigation RESULT** — published to channel `#events`:
```json
{
  "intent": "NOTIFY",
  "from": "agent://routing.vector-01",
  "to": "<reply_to>",
  "correlation_id": "<request correlation_id>",
  "priority": "P2",
  "sla_ms": 5000,
  "payload": {
    "kind": "navigation_result",
    "ok": true,
    "geojson": "<navigate_to_geojson output>",
    "error": null
  }
}
```
On failure the result is `{ "ok": false, "geojson": null, "error": "<msg>" }`. The
consumer MUST echo the request's `correlation_id` so the requester can correlate the
result to its task.

**Wiring is env-gated by `VECTOR_BUS_URL`.**
- **Unset** → an in-process `LocalBus` double is used: existing HTTP endpoints remain
  unchanged and local development behaves exactly as before (no bus dependency).
- **Set** → a `NetworkBusClient` is used: `urllib`-based publish to `:8090` plus a
  background SSE subscribe thread with ack, mirroring how the existing E1–E9 agent
  repos select the bus via `VECTOR_BUS_URL`.

The decision to **reuse the existing envelope and the existing 5 channels** (no new
channel, no bus-core edit) minimizes blast radius; `agent://routing.vector-01` is
designated as the routing service address on the bus.

## Consequences

- Routing becomes an event-driven E2 consumer: future live graph/traffic deltas and
  logistics multi-stop requests can be dispatched as bus tasks without touching the
  HTTP surface.
- No `vector-bus` core change is required — the existing envelope, intents, and 5
  channels are sufficient.
- `vector-routing` gains a stdlib-only Python bus client dependency
  (`NetworkBusClient` over `urllib` + SSE, mirroring the TS `NetworkBusClient`) used
  only when `VECTOR_BUS_URL` is set; when unset, it uses the in-process `LocalBus`
  double and the HTTP endpoints are unchanged, preserving local-dev behavior and the
  self-contained, zero-sibling-import constraint of adr-0025.
- The `vector-routing → vector-bus` runtime dependency edge is recorded in the
  registry (both `registry.yaml` and `registry.json`) and in the knowledge graph.

## Alternatives considered

- **Add a new `#navigation` channel plus a `vector-bus` core edit.** Rejected: it
  expands blast radius across all bus consumers and the bus server, and there is no
  functional need — the existing `#tasks`/`#events` channels model request/result
  cleanly.
- **Use WebSocket instead of HTTP+SSE pub/sub.** Rejected per adr-0029, which
  standardized the bus on HTTP publish + SSE subscribe + ack; introducing a WebSocket
  transport would fork the bus contract.

## References

- adr-0029-networked-event-bus.md — Networked E2 Event Bus (HTTP+SSE, port 8090, `NetworkBusClient`).
- adr-navigate.md — `vector-routing` local `/navigate` contract (Wave 24, Session 32).
- Wave 24 / SESSION_LOG Session 32 — turn-by-turn ETA navigation deployment.
- adr-0025 / adr-0026 / adr-0042 — M2 routing engine, route-overlay, navigation-overlay.
