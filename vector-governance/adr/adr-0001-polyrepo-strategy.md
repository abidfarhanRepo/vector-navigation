# ADR-0001 — Polyrepo with a shared contracts monorepo

- **Status:** Accepted
- **Date:** 2026-07-11
- **Deciders:** CTO / D3 Architecture
- **Supersedes:** none
- **Superseded by:** none

## Context
Project Vector will be built by many agents across bounded contexts. We need to avoid edit
collisions and keep ownership explicit (Blueprint §5/§11) while still sharing cross-cutting types.

## Decision
Use a **polyrepo** layout with a central `vector/registry` (in vector-governance) as the single
source of truth for ownership and dependencies, plus a dedicated `vector-contracts` repo holding all
language-neutral shared contracts (OpenAPI/Protobuf/JSON Schema/events/DTOs/error codes).

## Consequences
- Explicit CODEOWNERS boundaries per repo; collisions minimized.
- Contracts evolve independently and are versioned per Bible §14.
- Slightly more cross-repo coordination than a monorepo, mitigated by the registry + contract repo.

## Alternatives considered
- **Monorepo:** simpler shared code, but weaker ownership boundaries and noisier reviews at scale.
- **No contracts repo:** types duplicated per repo -> drift; rejected per Bible §7 D6.

## References
- Blueprint §11, WBS §13, Bible §8 R6/R7.
