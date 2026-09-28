# ADR-0007 — vector-common owns all shared domain models

- **Status:** Accepted
- **Date:** 2026-07-11
- **Deciders:** CTO / D3 Architecture
- **Supersedes:** none
- **Superseded by:** none

## Context
Without a shared home, domain concepts (geometry, coordinates, identifiers, errors, units) get
duplicated across repositories, causing drift (Bible §7 D6) and rework.

## Decision
Create **vector-common** as the single owner of shared domain models, geometry primitives, coordinate
types, identifiers, error handling, utilities, and cross-cutting abstractions for the Node/TS tier.
No other repository may duplicate these concepts; they import from vector-common. Cross-language types
are defined as contracts in vector-contracts and may be generated into vector-common.

## Consequences
+ One canonical implementation of core types; fewer bugs at boundaries.
+ Clear ownership (Blueprint §5); registry points here.
- JS-only for now; Rust/Go engines will reference the contract definitions instead.

## Alternatives considered
- **Duplicate per repo:** drift; rejected.
- **Put shared code in vector-contracts:** contracts are specs, not implementations; rejected.

## References
- ADR-0003, Blueprint §5, Bible §7 D6, WBS E7 (frameworks).
