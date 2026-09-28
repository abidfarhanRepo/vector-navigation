# ADR-0019 — E1 Agent Orchestration Core: lift the ADR-0005 deferral for E1

- **Status:** Accepted
- **Date:** 2026-07-12
- **Deciders:** CTO / D3-architecture + D1-platform
- **Supersedes:** — (partially lifts ADR-0005 for E1 only)
- **Superseded by:** none

## Context

ADR-0005 (product-first sequencing) defers the agent-platform epics E1–E9 so the navigation
product is built first. ADR-0017 lifted that deferral for **E9 (Registry)** and **E2 (Event Bus)**,
and ADR-0018 lifted it for **E3 (Knowledge Graph Platform)**.

With E9 (`vector-registry`) and E2 (`vector-bus`) built and tested, the next epic in the foundation
order — **E1 (Agent Orchestration Core)** — is unblocked for everything except the Review Router
task, which depends on E4 (Engineering Gates). E4 is lifted in the same wave via ADR-0020, so the
Review Router can consume E4 gates by dependency injection without re-introducing the full E1/E4
deferral.

## Decision

Lift the ADR-0005 deferral for **E1 (Agent Orchestration Core)** specifically, following the same
pattern as ADR-0017/0018. E1 is implemented as two real, tested polyrepos:

- **`vector-coa-fleet`** — Fleet Control Plane.
  - `coa-fleet-svc`: scheduler, priority queue, autoscaler. Consumes E2 Bus by DI.
- **`vector-coa-runtime`** — Orchestrator Runtime.
  - `coa-runtime-svc`: planner, decomposer, arbiter, review-router. Consumes E2 Bus + E9 Registry by
    DI; the review-router calls E4 gates by DI (ADR-0020).

Both repos are implemented in **Node/TypeScript** with **zero runtime dependencies**, validated by
`node --test` plus the shared `validate.mjs`. They register in `registry.yaml` and are mirrored as
KG nodes in `kg/index.json`, with dependency/KG edges so neither is orphaned.

## Consequences

- Positive: COA can now plan / decompose / arbitrate / schedule agent work and route reviews through
  E4 gates (once ADR-0020 lands).
- Positive: E1 reuses the E9 registry + E2 bus foundation (no new transport or ownership system).
- Negative: E1 carries its own runtime surface (fleet control plane + orchestrator runtime) before a
  full agent fleet consumes it.
- The ADR-0005 deferral now stands only for E4–E8; this ADR does not lift those except E4 (ADR-0020).

## Alternatives considered

- **Keep E1 deferred (status quo of ADR-0005).** Rejected: the foundation order in WBS §10 names E1
  as the next epic after E9+E2, and later epics fake orchestration otherwise.
- **Build E1 as a single repo.** Rejected: the WBS splits fleet control plane (E1.C1) from
  orchestrator runtime (E1.C2); two focused polyrepos match the WBS structure and keep blast radius
  small.

## References

- adr-0005 (product-first deferral), adr-0017 (E9+E2 foundation), adr-0018 (E3 KG platform),
  adr-0020 (E4 CI gates)
- `docs/WBS.md` (E1, §10 Foundation order)
- `docs/KNOWLEDGE_GRAPH.md`, `docs/ORGANIZATIONAL_BLUEPRINT.md`, `docs/ENGINEERING_BIBLE.md` as relevant
