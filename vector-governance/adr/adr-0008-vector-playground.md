# ADR-0008 — vector-playground owns all experiments

- **Status:** Accepted
- **Date:** 2026-07-11
- **Deciders:** CTO / D8 Research
- **Supersedes:** none
- **Superseded by:** none

## Context
Prototypes, benchmarks, proof-of-concepts, and algorithm experiments need a home. If they land in
product repos they pollute `main`, complicate reviews, and blur the Definition of Done.

## Decision
Create **vector-playground** as the permanent, explicit home for all prototypes, benchmarks, PoCs,
algorithm experiments, AI model evaluations, and performance investigations. Production repositories
stay clean and focused. Findings worth promoting move to a product repo via a normal PR + ADR.

## Consequences
+ Clean separation of experiment and production (Bible §2 E4, D8 hard boundaries).
+ D8 can iterate freely without gating product CI.
- Risk of forgotten experiments; mitigated by requiring a short README per experiment and a link in
  the KG as a Lesson/Concept when promoted.

## Alternatives considered
- **Experiments inside product repos:** pollution; rejected.
- **No experiments:** kills innovation; rejected.

## References
- AI_RESEARCH_DEPARTMENT.md §5, Bible §2 E4.
