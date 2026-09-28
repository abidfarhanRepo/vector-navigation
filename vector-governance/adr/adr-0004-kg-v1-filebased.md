# ADR-0004 — Knowledge Graph v1 is file-based

- **Status:** Accepted
- **Date:** 2026-07-11
- **Deciders:** CTO / D7
- **Supersedes:** none
- **Superseded by:** none

## Context
The Knowledge Graph (KG) is central to the org's memory (KG §1). The full E3 hybrid platform
(graph + vector + blob store) is itself a large build (WBS E3) and should not block early progress.

## Decision
Stand up a **file-based v1 KG** (`vector-governance/kg/index.json`) implementing the node/edge schema
from KNOWLEDGE_GRAPH.md §2/§3/§4, keyed by `kg://<type>/<slug>` for content-addressed dedup. It is
intentionally **schema-compatible** with the future E3 platform so it can be ingested later.

## Consequences
+ Memory exists from day one; agents can retrieve before acting (Bible §0/§19 F4).
+ No infrastructure dependency; trivial to validate in CI.
- Not a live query engine; retrieval is by reading the file. Acceptable for bootstrap scale.

## Alternatives considered
- **Build E3 first:** correct end-state but blocks all product work; rejected (see ADR-0005).
- **No KG yet:** violates memory-first principle; rejected.

## References
- KNOWLEDGE_GRAPH.md §5 (keying), §10 (event ingest), WBS E3.
