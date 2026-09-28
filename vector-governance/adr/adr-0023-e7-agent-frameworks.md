# ADR-0023 — E7 Department Agent Frameworks: lift the ADR-0005 deferral for E7

- **Status:** Accepted
- **Date:** 2026-07-13
- **Deciders:** CTO / D1-platform + D8-research
- **Supersedes:** — (partially lifts ADR-0005 for E7 only)
- **Superseded by:** none

## Context
ADR-0005 (product-first) deferred E1–E9. ADR-0017 lifted E9+E2; ADR-0018 E3; ADR-0019 E1; ADR-0020 E4; ADR-0021 E5; ADR-0022 E6. With E9/E2/E3/E1/E4/E5/E6 built, the next epics in the foundation order — E7 (Department Agent Frameworks) and E8 (Docs Tooling) — are unblocked.

## Decision
Lift the ADR-0005 deferral for **E7 (Department Agent Frameworks)** specifically, following the same pattern as ADR-0017–0022. E7 is implemented as one Node/TS polyrepo `vector-agents` (owner squad `d1-platform`, secondary `d8-research`), providing service `agents-svc`:
- **`worker-fw`** — stateless worker agent framework; pulls tasks from queues, retrieves squad README+ADRs from the KG, emits result envelopes (E7.C1.S3).
- **`dma-fw`** — DMA framework: owns backlog, provisioning requests to COA, enforces RACI, emits fleet report (E7.C1.S1).
- **`sla-fw`** — SLA framework: registers SLAs, detects breaches, escalates (E7.C1.S2).
- **`research-pipeline`** — D8 research intake pipeline (Discover→Fetch→Extract→Analyze→Synthesize→Publish); writes only KG/Report/Recommendation nodes (hard boundary); escalates critical CVEs (E7.C2.S1).

All consume E2 Bus / E9 Registry / E3 KG by DI; zero cross-repo imports. Register in registry.yaml + mirror as KG node in kg/index.json with non-orphan edges.

## Consequences
- Positive: the agent frameworks that turn the COA runtime into an operating fleet now exist (worker statelessness, DMA provisioning/RACI, SLA burn, and the D8 read-only research pipeline).
- Negative: E7 adds its own runtime surface before a full fleet consumes it.
- The ADR-0005 deferral now stands only for E8.

## Alternatives considered
- **Keep E7 deferred.** Rejected: the foundation order names E7 next.
- **Build E7 as multiple repos.** Rejected: WBS scopes E7 as one `vector/agents` repo; one focused polyrepo keeps blast radius small and matches the Wave 3–7 pattern.

## References
- adr-0005, adr-0017, adr-0018, adr-0019, adr-0020, adr-0021, adr-0022
- `docs/WBS.md` (E7, §10)
- `docs/ENGINEERING_BIBLE.md` (Blueprint §2/§4/§12.6)
