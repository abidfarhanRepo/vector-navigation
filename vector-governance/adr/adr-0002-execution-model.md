# ADR-0002 — Single-agent execution model with internal personas

- **Status:** Accepted
- **Date:** 2026-07-11
- **Deciders:** CTO
- **Supersedes:** none
- **Superseded by:** none

## Context
The org design describes hundreds of concurrent agents, but the current environment is a single
OpenCode CLI agent on one machine. We must still apply the full engineering process.

## Decision
Operate as **one disciplined engineer** that switches internal personas per task using the pipeline:
Architect -> Researcher -> Planner -> Builder -> Reviewer -> Documentation. No role-play or invented
dialogue between imaginary agents. Always stay aware of the whole Vector architecture while building
only the current task.

## Consequences
- The documented multi-agent org (Blueprint) remains the long-term target and is honored in docs.
- Practical execution is coherent and reviewable today.
- Does not literally scale to hundreds of concurrent agents; revisit when a fleet runtime exists.

## Alternatives considered
- **Simulate the full org:** high overhead, no real parallelism, risk of fiction; rejected.
- **Plan-only mode:** no product progress; rejected.

## References
- ORGANIZATIONAL_BLUEPRINT.md, MASTER_ORCHESTRATOR.md.
