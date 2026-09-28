# Runbook — vector-bus

> Operational guide for the E2 Communication & Event Backbone. Expand as the service matures.

## Purpose
In-process async message backbone: pub/sub over the 5 Blueprint §6 channels, with at-least-once
delivery, idempotent consume, dead-lettering, promise-based backpressure, and SLA-driven escalation.

## Ownership
See docs/OWNERSHIP.md (primary squad: d1-platform).

## Local setup
```bash
git clone <repo>
cd <repo>
npm install        # installs dev dependencies (yaml for the validator only)
npm run validate   # structure / yaml / json / markdown hygiene checks
npm test           # node --test (bus, envelope, channel-router)
```

## Channels
`#tasks` (work assignment), `#events` (immutable fact stream), `#contracts` (interface/dependency
declarations), `#escalations` (exceptions routed up), `#broadcast` (org-wide notices from COA/CTO).

## Dead-letter handling procedure
1. Subscribe to dead letters via `bus.onDeadLetter((rec) => { ... })` where `rec = { channel, message, attempts, error }`.
2. On dead-letter: log `rec.error` and the original envelope `rec.message`.
3. Triages: if the failure is transient, re-publish the original envelope (it will get a fresh id only if you create one; for true retries reuse the same `id` so idempotent consumers skip duplicates).
4. If the consumer is permanently broken, page the owning squad (d1-platform) — do NOT silently drop.

## SLA-breach escalation procedure
1. Publish through `ChannelRouter.publish(envelope)` (not raw `bus.publish`) so an SLA timer starts.
2. Consumers subscribe via `router.subscribe(...)`; calling `ack()` cancels the timer. If a message is
   not acked within `sla_ms`, the router raises an escalation to `#escalations` (level defaults to L0)
   and fires `onEscalation(cb)`.
3. The escalation envelope is itself a valid envelope: `intent: 'ESCALATE'`, `priority: 'P0'`,
   `correlation_id` copied, `payload.level`/`payload.reason` set, and `to` routed per level
   (L0/L1 -> source DMA, L2 -> `agent://coa`, L3/L4 -> `agent://cto`).

## How COA consumes `#escalations`
- COA (and CTO for L3/L4) subscribe to `#escalations` and treat each envelope as a self-contained
  incident: it carries full context in `payload` so no prior state is required (Blueprint §7).
- Escalation levels: L0 (30m) -> SLA, L1 (2h) -> source DMA, L2 (4h) -> COA, L3 (1d) -> CTO, L4 (3d) -> Board.
- Unanswered past SLA auto-promotes to the next level via a fresh `stampEscalation` at the next tier.

## Health & signals
- Validate passes in CI; `npm test` is green.
- Escalation rate on `#escalations` and dead-letter rate (`onDeadLetter` volume) are the primary
  health signals for the backbone.

## Incident response
1. Capture context in `#escalations`.
2. For security incidents, the relevant DMA holds command.
3. Produce a blameless post-mortem; store as a Lesson in the KG.
