# ADR-bus-channel — Channel router with SLA-driven escalation

- **Status:** Accepted
- **Date:** 2026-07-12
- **Deciders:** d1-platform
- **Supersedes:** none
- **Superseded by:** none

## Context
Producers should not hard-code channels; routing belongs with the router (E2.C2). Blueprint §7 says
an unanswered message past its `sla_ms` auto-escalates. We need a single place that maps an envelope
to a channel by `intent`/`to`, starts an SLA timer per message, and raises `#escalations` on breach.

## Decision
`src/channel-router.js` (T3) provides `ChannelRouter`:
- `route(envelope)` (pure matrix): TASK/ASSIGN -> `#tasks`; REVIEW/CONTRACT -> `#contracts`;
  ESCALATE -> `#escalations`; NOTIFY -> `#broadcast`; default/fallback -> `#events`.
- `publish(envelope)` routes then calls `bus.publish(channel, envelope)` and starts an SLA timer of
  `envelope.sla_ms`. On breach it builds an escalation via `stampEscalation` (level defaults to L0)
  and publishes it to `#escalations`, also invoking `onEscalation(cb)` subscribers.
- `ack(messageId)` cancels the pending SLA timer. `router.subscribe(...)` wraps a consumer's `ack`
  so a normal ack transparently cancels the timer. The scheduler is injectable for deterministic tests.

## Consequences
- Routing is centralized and testable as a pure function.
- SLA breaches are automatic and carry full context (correlation_id, level, reason) so the receiving
  tier needs zero prior state (Blueprint §7).
- Consumers get SLA safety for free when they subscribe through the router.
- Escalation volume is bounded by message volume; COA/CTO can consume `#escalations` directly.

## Alternatives considered
- **Per-consumer SLA timers:** rejected — duplicates timers and splits responsibility; the router is
  the single owner of SLA semantics.
- **Push escalation to a dedicated DMA immediately:** rejected — Blueprint §7 defines the tiered
  L0..L4 routing, modelled in `stampEscalation`.

## References
- Blueprint §6 (channels), Blueprint §7 (escalation levels & auto-promotion), ADR-bus-envelope,
  ADR-bus-delivery.
