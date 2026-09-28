# ADR-bus-delivery — Bus delivery semantics

- **Status:** Accepted
- **Date:** 2026-07-12
- **Deciders:** d1-platform
- **Supersedes:** none
- **Superseded by:** none

## Context
Epic E2 (Communication & Event Backbone) needs an in-process async pub/sub primitive that
guarantees messages are not lost, never double-applied by a well-behaved consumer, and can shed
load safely when a channel is saturated. Blueprint §6 defines the channel taxonomy; Bible §4 S5
requires malformed input to be rejected at the boundary.

## Decision
`BusService` (src/bus.js) implements:
- Exactly the 5 channels `#tasks #events #contracts #escalations #broadcast`; unknown channels throw.
- **At-least-once** delivery: a consumer `handler(message, ack)` must call `ack()`. If the handler
  throws or fails to `ack()` within `deliveryTimeout`, the message is redelivered with bounded
  retries using exponential backoff + jitter (`maxRetries`, `baseBackoffMs`, `maxBackoffMs`, `jitter`).
  After `maxRetries` the message is routed to a **dead-letter** handler registered via `onDeadLetter(cb)`.
- **Idempotent consume**: each consumer keeps a bounded `Set` of processed message `id`s
  (`consumerId -> Set`); a redelivery of an already-processed id is a no-op.
- **Backpressure**: a per-channel in-flight queue is tracked. When it exceeds `maxQueue` (default 1000),
  `publish` returns a Promise that resolves once the queue drains below `highWaterMark`
  (promise-based backpressure). A `hardCap` option additionally throws `BackpressureError` for
  callers that prefer fail-fast.

Delivery timers are provided through an injectable `scheduler` so tests can use tiny windows.

## Consequences
- Producers never lose messages; consumers must be idempotent (which they are, by design).
- A misbehaving consumer that never acks will eventually dead-letter rather than block the channel.
- Promise-based backpressure keeps `publish` non-blocking while still applying pressure upstream.
- Slight memory cost for the per-consumer processed-id set (bounded by `maxProcessed`).

## Alternatives considered
- **Fire-and-forget / at-most-once:** simpler but loses messages on handler failure — rejected.
- **Kafka/RabbitMQ:** heavyweight, external dependency, violates the zero-runtime-dependency rule.
- **Throw-on-backpressure only:** rejected in favor of promise-based backpressure as the default,
  keeping `hardCap` as an opt-in fail-fast path.

## References
- Blueprint §6 (channels), Bible §4 S5 (boundary rejection), Bible §6 T4 (deterministic tests).
