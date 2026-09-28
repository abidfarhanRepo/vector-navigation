# adr-0029 — Networked E2 Event Bus (operationalize E2 as a real fleet bus)

## Status

Accepted.

## Context

ADR-0005 deferred the E1–E9 agent-platform epics so the navigation product (M0 → M1 …) could be
built first; ADR-0017 lifted that deferral for E9 + E2, and ADRs 0018–0024 lifted it for the rest,
so all E1–E9 services are now built and gate-green. However, the E2 Communication & Event Backbone
(`vector-bus`) was realized as an **in-process** library/service core: each built E1/E3/E4/E5/E6/E7/E8
service consumed the bus by DI through an in-repo `LocalBus` double (its `src/bus-contract.js`). The
services therefore cannot actually communicate with one another across processes — they are isolated
instances. To run the platform as a **real fleet** (the horizon named in the Session 15/16/19
handoffs), the bus must be reachable over the network so every service talks to one shared backbone.

## Decision

Operationalize E2 by adding a **networked BusServer** inside `vector-bus` and **`NetworkBusClient`
drop-in DI adapters** in each consumer repo, without lifting any ADR-0005 deferral (all E-epics are
already built; this only OPERATIONALIZES E2).

- **BusServer** (HTTP + Server-Sent-Events, Node-stdlib-only) wraps the existing in-process
  `BusService` + `ChannelRouter` so the real engine (5-channel async pub/sub, envelope validation,
  SLA-aware routing, at-least-once delivery, dead-letter) is served over the network. Wire protocol:
  - `GET /healthz` — liveness/readiness (200 OK).
  - `POST /publish` with body `{channel?, message}` — publish an envelope; channels default to the
    router's intent→channel mapping; returns the delivery id(s).
  - `GET /subscribe?channel=&consumerId=` — SSE stream emitting `message` frames (delivered envelopes)
    and `deadletter` frames (failed/exhausted deliveries) for that consumer on that channel.
  - `POST /ack` with body `{deliveryId, consumerId, channel}` — acknowledge a delivery so it is removed
    from the consumer's outstanding set (at-least-once semantics preserved across reconnects).
- **`NetworkBusClient`** is a drop-in DI adapter added in each consumer repo
  (`vector-coa-fleet`, `vector-coa-runtime`, `vector-ci`, `vector-kg-ingest`, `vector-security`,
  `vector-observability`, `vector-agents`, `vector-docs`) matching that repo's own `src/bus-contract.js`
  signature (same method surface as the in-repo `LocalBus`), so E1/E3/E4/E5/E6/E7/E8 services keep
  their exact DI shape and stay CI-isolated while talking to the real networked bus at runtime. E9
  (`vector-registry`) is not a bus consumer, so it is unchanged.
- **Infra:** `docker_image.bus` + `docker_container.bus` build `vector-bus/docker` and run the
  `bus-server-svc` on the shared `docker_network.vector`, **port 8090**, with a Node-based healthcheck.
  This is additive — existing tile-server/routing/vision resources and the shared network are unchanged.

## Consequences

- The E1–E9 fleet can now run **networked**: every E1/E3/E4/E5/E6/E7/E8 service communicates over the
  single shared E2 bus instead of isolated in-repo `LocalBus` doubles.
- Each consumer repo stays **self-contained and CI-safe**: the `NetworkBusClient` is a drop-in adapter
  behind the existing `bus-contract.js` DI seam, so isolated-CI behavior (in-repo double when no server
  is reachable) is preserved unchanged.
- The existing engine guarantees are reused unchanged: **at-least-once** delivery, **idempotent**
  consume, and **dead-letter** are served over SSE + `/ack` exactly as the in-process `BusService` does.
- The change is **additive**: a new `bus-server-svc` service + infra container + per-repo adapter files;
  no existing repo loses its in-process path, and no ADR-0005 deferral is lifted.

## Alternatives considered

- **WebSocket library (e.g. `ws`).** Rejected: introduces a runtime dependency and violates the
  zero-dependency, self-contained constraint of `vector-bus`; raw WebSocket framing is more code than needed.
- **gRPC.** Rejected: requires protobuf tooling/codegen and a runtime dependency; overkill for a
  stdlib-only event bus over HTTP.
- **External broker (NATS / Redis).** Rejected: adds an operational dependency and a foreign substrate,
  breaking the "self-contained Node-stdlib" guarantee the fleet relies on for isolated CI.
- **Raw WebSocket without a library.** Rejected in favor of **SSE + POST** — SSE is a stdlib-friendly
  one-directional server→client stream over plain HTTP and pairs naturally with POST `/publish` and
  POST `/ack`, with zero extra client libraries and browser-friendly semantics.

This ADR does **NOT** lift ADR-0005: every E1–E9 epic was already built and gate-green. It only
OPERATIONALIZES the existing E2 bus by making it networked and wiring the fleet to it.

## References

- `adr/adr-0017-agent-platform-foundation.md` (E2 Event Bus foundation, lifts ADR-0005 for E9+E2)
- `docs/ORGANIZATIONAL_BLUEPRINT.md` §6 / §7 (channels, escalation levels, envelope spec)
- `docs/WBS.md` (E2 Communication & Event Backbone)
