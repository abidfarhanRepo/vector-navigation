# ADR-0020 — E4 Engineering Gates & CI/CD: lift the ADR-0005 deferral for E4

- **Status:** Accepted
- **Date:** 2026-07-12
- **Deciders:** CTO / D1-platform
- **Supersedes:** — (partially lifts ADR-0005 for E4 only)
- **Superseded by:** none

## Context

ADR-0005 (product-first sequencing) defers the agent-platform epics E1–E9. ADR-0017 lifted E9+E2
and ADR-0019 lifts E1; the E1 Review Router depends on E4 gates, so E4 must be lifted in the same
wave.

The scripted gate chain already exists as governance — `run-ci.mjs` + `validate-registry-kg.mjs` +
per-repo `validate.mjs`. What was not built is E4's runtime `ci-svc`. The security gate depends on
E5 (Security, deferred); the quality and license gates have no prerequisites and can ship now.

## Decision

Lift the ADR-0005 deferral for **E4 (Engineering Gates & CI/CD)** specifically, following the same
pattern as ADR-0017/0018/0019. E4 is implemented as a real, tested polyrepo:

- **`vector-ci`** — runtime Engineering Gates.
  - `ci-svc`: engine + quality / license / security gates, implemented in Node/TypeScript with zero
    runtime dependencies, validated by `node --test` plus `validate.mjs`.
  - Consumes E2 Bus by DI. `engine.runGates(repoId)` is the contract the E1 Review Router
    (`coa-runtime-svc`) calls by DI.
  - The security gate is a safe heuristic scanner with an E5 extension point (`setE5Scanner`) left for
    E5; full security depth stays deferred.

`vector-ci` registers in `registry.yaml` and is mirrored as a KG node in `kg/index.json`, with a
dependency/KG edge (`vector-ci` → `vector-bus`) so it is not orphaned.

## Consequences

- Positive: gates are now enforceable in-process and reusable by COA (E1 Review Router); the scripted
  gate chain becomes a thin caller over a real engine.
- Positive: E4 reuses the E2 bus foundation (no new transport).
- Negative: full gate chain still deferred — E5 security depth and E8 doc-staleness gates remain out
  of scope; the security gate is heuristic-only until E5 lands.
- The ADR-0005 deferral now stands only for E5–E8; this ADR does not lift those.

## Alternatives considered

- **Keep E4 deferred (status quo of ADR-0005).** Rejected: the quality of E1/E3 needs enforced gates.
- **Build only the scripts (no runtime ci-svc).** Rejected: E1 needs a callable gate-runner
  (`engine.runGates`) that scripts alone cannot provide.

## References

- adr-0005 (product-first deferral), adr-0017 (E9+E2 foundation), adr-0018 (E3 KG platform),
  adr-0019 (E1 COA)
- `docs/WBS.md` (E4, §10 Foundation order)
- `docs/ORGANIZATIONAL_BLUEPRINT.md`, `docs/ENGINEERING_BIBLE.md`, `docs/KNOWLEDGE_GRAPH.md` as relevant
