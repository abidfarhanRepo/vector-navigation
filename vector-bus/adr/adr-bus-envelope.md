# ADR-bus-envelope — Envelope validation at the publish boundary

- **Status:** Accepted
- **Date:** 2026-07-12
- **Deciders:** d1-platform
- **Supersedes:** none
- **Superseded by:** none

## Context
Every message on the backbone carries the standard envelope (Blueprint §6):
`id, correlation_id, from, to, intent, priority, sla_ms, timestamp` plus a `payload`.
A malformed envelope corrupts routing, SLA timers, and escalation logic downstream, so bad messages
must be stopped at the edge (Bible §4 S5 — reject malformed at the boundary).

## Decision
`src/envelope.js` (T2) provides:
- `validate(envelope)` -> `{ valid, errors[] }` returning explicit, per-field errors for every
  violation: missing required field, wrong type, invalid `intent` (enum), invalid `priority` (enum),
  non-numeric `sla_ms`, empty `timestamp`.
- `createEnvelope(partial)` fills defaults: `id` (node:crypto.randomUUID), `correlation_id`
  (defaults to `id`), `timestamp` (ISO now), plus intent/priority/sla_ms/payload defaults.
- `stampEscalation(envelope, { level, reason })` returns a NEW escalation envelope (input untouched)
  with `intent: 'ESCALATE'`, `priority: 'P0'`, `correlation_id` copied, and `to` routed per level
  (L0/L1 -> source DMA modelled as the original `from`; L2 -> `agent://coa`; L3/L4 -> `agent://cto`).
  `payload.level` and `payload.reason` are set.
- `BusService.publish` calls `validate` and throws on invalid input, so the boundary is enforced
  for all 5 channels.

## Consequences
- Bad messages never enter the bus; failures are loud and local to the producer.
- `createEnvelope` makes well-formed envelopes the path of least resistance.
- Escalations are themselves valid envelopes and can be re-published/validated.
- `validate` is pure and unit-testable without the bus (fuzz-style coverage in test/envelope.test.js).

## Alternatives considered
- **Validate only at the consumer:** rejected — a bad message would still occupy queues and timers.
- **External schema lib (ajv):** rejected to honor the zero-runtime-dependency constraint; the
  envelope is small enough for an explicit hand-rolled validator.

## References
- Blueprint §6 (envelope), Blueprint §7 (escalation levels), Bible §4 S5.
