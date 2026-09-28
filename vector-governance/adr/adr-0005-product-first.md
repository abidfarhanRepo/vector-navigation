# ADR-0005 — Product-first sequencing (defer E1-E9 agent-platform)

- **Status:** Accepted
- **Date:** 2026-07-11
- **Deciders:** CTO
- **Supersedes:** none
- **Superseded by:** none

## Context
The WBS (E1-E9) describes building a full agent-orchestration platform (COA, bus, KG, CI, security,
observability, agent frameworks, registry) *before* any navigation product. Building that meta-platform
first is a large effort that delays customer value and risks architecture drift if started prematurely.

## Decision
Build the **navigation product first** (M0 research -> M1 map display -> M2 client offline -> ... per
Vector_System_Architecture.md §12). The E1-E9 agent-platform is **deferred** and revisited only when a
real fleet runtime is needed. Governance scaffolding (this repo, contracts, common, playground) is built
now because it is cheap and unblocks product repos.

## Consequences
+ Fastest path to a demonstrable, valuable product.
+ Avoids premature investment in orchestration infrastructure we cannot yet use.
- E1-E9 capabilities (auto-CI gating, real KG platform) arrive later; covered short-term by file-based
  KG + lightweight CI + human/CTO gates.

## Alternatives considered
- **Build E1-E9 first:** "managers before workers"; rejected as cart-before-horse.
- **Parallel full build:** exceeds current single-agent capacity; rejected.

## References
- WBS.md E1-E9, Vector_System_Architecture.md §12, Vector_Master_Roadmap.md.
