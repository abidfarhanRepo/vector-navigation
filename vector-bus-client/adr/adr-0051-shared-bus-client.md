# ADR-0051: Shared bus-client package (vector-bus-client)

- **Status:** Accepted
- **Date:** 2026-07-16
- **Deciders:** Vector architecture & security
- **Supersedes/Relates:** ADR-0003 (no sibling imports across repos), ADR-0007
  (single source of truth / vendoring), ADR-0043 (routing is a bus consumer),
  ADR-0049 (bus auth), Wave 26c (traffic publishes, routing consumes
  congestion).

## Context

Wave 26c gave the traffic and routing engines bus-aware logic: traffic
publishes `TRAFFIC_CONGESTION` envelopes; routing consumes them via
`start_traffic_consumer`. But neither engine had a **real** bus client wired
into its HTTP `main`/`make_server`:

- `vector-routing` carried a *duplicated* `bus_client.py` (LocalBus +
  NetworkBusClient) — a violation of ADR-0007 (single source of truth). It
  wired the bus in `main` for the navigate consumer but never for traffic.
- `vector-traffic` had **no** bus client at all, so its `TrafficService(bus=)`
  injection point was never exercised by the live server.

The bus client is genuine shared infrastructure (not engine-specific logic), so
it belongs in one canonical package vendored into consumers — exactly the
ADR-0007 pattern already used for `vector_auth` and `vector_geo`.

## Decision

Extract the bus client into a **new shared repo `vector-bus-client`**
(`src/vector_bus_client/`, stdlib-only, zero sibling imports). It exposes
`LocalBus`, `NetworkBusClient`, and `create_bus(url=None)` (LocalBus when
`url` is falsy, NetworkBusClient otherwise — matching the existing routing
behavior). It is vendored into consuming engines via `scripts/sync_vendor.py`,
with a `scripts/check_vendor.py` drift guard.

Wiring of the engines' live servers (Wave 26d):

- **vector-routing**: delete the duplicated `src/vector_routing/bus_client.py`;
  `main` imports `create_bus` from `vector_bus_client` and now also starts
  `service.start_traffic_consumer()` (so the router consumes live congestion),
  in addition to the existing navigate consumer. `make_server` returns the bus
  for testability.
- **vector-traffic**: add `vendor/vector_bus_client`; `main` builds the bus via
  `create_bus(VECTOR_BUS_URL)` and passes it to `TrafficService`; after the
  service is created it calls `service.start_traffic_consumer()` so traffic
  **publishes** congestion onto the bus. `make_server` returns the bus.

No live publish/subscribe occurs in CI: the gate runs with no `VECTOR_BUS_URL`,
so both engines use the in-memory `LocalBus` (publish is a no-op for routing's
consumer, and traffic's `start_traffic_consumer` is a no-op without a bus). The
cross-engine flow is verified by a **live integration proof** (bus server +
traffic + routing on a real `VECTOR_BUS_URL`) run manually/before commit.

## Consequences

**Positive**
- Single source of truth for the bus client (ADR-0007); removes the duplicated
  `bus_client.py` from routing.
- Both engines now genuinely participate in the bus at runtime: traffic
  publishes congestion, routing consumes it and reroutes — closing Wave 26d
  (the "composition root" gap).
- Dev-anonymous fallback preserved: no `VECTOR_BUS_URL` ⇒ LocalBus ⇒ zero
  behavior change for local dev / the gate.

**Negative / trade-offs**
- Adds a vendored copy per engine (justified by ADR-0007 isolation requirement).
- The live cross-engine flow depends on a running `vector-bus` server; the
  per-repo gate cannot exercise it (isolation), so it is covered by a separate
  live proof rather than the gate.

## Validation
- `vector-bus-client`: unit tests for LocalBus delivery/ack/channel-isolation
  and `create_bus` URL selection (3 tests).
- Both engines: existing + new bus tests green; full `act`+Docker gate 30/30.
- Live proof (manual, pre-commit): start `vector-bus` (dev mode), run traffic
  + routing with `VECTOR_BUS_URL`, confirm a `TRAFFIC_CONGESTION` envelope
  published by traffic is received by routing's `#broadcast` consumer and
  reflected in a rerouted `navigate`.
