# ADR-0017: Agent-platform foundation — E9 Registry + E2 Event Bus

- **Status:** Accepted
- **Date:** 2026-07-12
- **Deciders:** CTO / D1 Platform DMA
- **Supersedes:** none
- **Superseded by:** none

## Context

ADR-0005 (product-first sequencing) deferred the E1–E9 agent-platform epics so the
navigation product (M0 → M1 …) could be built first. The WBS "Foundation order"
(`docs/WBS.md` §10) states that **E9 (Registry & Ownership)** and **E2 (Communication &
Event Backbone)** are prerequisites for most E1/E3/E4/E5 gating. With the M1 vertical
slice live and verified, and the governance gate green, the agent-runtime foundation is
now wanted. The two prerequisite epics can therefore be lifted out of the ADR-0005
deferral without disturbing the still-deferred remainder.

## Decision

Build **E9 (Registry & Ownership)** and **E2 (Communication & Event Backbone)** now, as
two real, tested polyrepos — `vector-registry` and `vector-bus` — implemented in
Node/TS (per ADR-0006 scaffold runtime), with zero runtime dependencies, validated by
`node --test` plus the shared `validate.mjs`. This lifts the ADR-0005 deferral **for E9
and E2 specifically**; E1 and E3–E8 remain deferred until needed.

## Consequences

- COA (E1), the KG platform (E3), CI gates (E4), and Security (E5) can later depend on
  `registry-svc` (repo→squad / service→repo maps, CODEOWNERS enforcement, blast-radius
  dependency graph) and `bus-svc` (5-channel async pub/sub, envelope validation,
  SLA-aware routing).
- Two repos are added to the registry (`registry/registry.yaml`) and mirrored as KG
  nodes (`kg/index.json`), each with `registry-svc` / `bus-svc` service entries and
  dependency/KG edges so neither is orphaned.
- The org-wide ADR record (this ADR) indexes the authoritative repo-local ADRs that
  document each component in detail.

## Alternatives

- **(a) Build E1 (COA) first** — rejected: COA needs both Registry (ownership /
  CODEOWNERS / blast-radius) and Bus (envelope routing / escalations) as prerequisites.
- **(b) Keep deferring everything (ADR-0005 unchanged)** — rejected: it blocks all
  downstream platform epics; the foundation order requires E9 + E2 before the rest.

## References

- `adr/adr-0005-product-first.md`, `adr/adr-0006-runtime-node.md`
- `docs/WBS.md` (E9 / E2, §10 Foundation order)
- `docs/ORGANIZATIONAL_BLUEPRINT.md` §6 / §7 / §11
- `docs/ENGINEERING_BIBLE.md` §8 R1/R6/R7, §10
- Repo-local ADRs — `vector-registry/adr/`: `adr-registry-core.md`,
  `adr-registry-codeowners.md`, `adr-registry-dep.md`;
  `vector-bus/adr/`: `adr-bus-delivery.md`, `adr-bus-envelope.md`, `adr-bus-channel.md`
