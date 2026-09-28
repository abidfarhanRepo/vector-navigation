# ADR-0018 — E3 Knowledge Graph Platform: lift the ADR-0005 deferral for E3

- **Status:** Accepted
- **Date:** 2026-07-12
- **Deciders:** CTO / D7 Docs & Knowledge + D3 Architecture
- **Supersedes:** — (partially lifts ADR-0005 for E3 only)
- **Related:** ADR-0005 (product-first deferral), ADR-0006 (Node/TS scaffold runtime), ADR-0017 (E9+E2 foundation), WBS §10, KNOWLEDGE_GRAPH.md §2/§3/§6/§7/§8/§10/§12, ORGANIZATIONAL_BLUEPRINT.md §6/§11, ENGINEERING_BIBLE.md §8 R7 / §5 / §10

## Context

ADR-0005 (product-first sequencing) defers the agent-platform epics E1–E9 so the navigation
product is built first. ADR-0017 lifted that deferral for **E9 (Registry)** and **E2 (Event Bus)**,
establishing the foundation order (E9 + E2 precede E3/E4/E5).

With E9 (`vector-registry`) and E2 (`vector-bus`) now built and tested (Session 10 / Wave 3), the
next epic in the foundation order — **E3 (Knowledge Graph Platform)** — is unblocked. The Knowledge
Graph is the permanent engineering memory the whole agent fleet reads from (KG §2/§3); building it
early de-risks every later epic (E1 COA, E4 gates, E5 security, E7 frameworks) that depends on
traversal/semantic retrieval and registry↔KG reconciliation.

E1, E4–E8 remain deferred.

## Decision

Lift the ADR-0005 deferral for **E3 (Knowledge Graph Platform)** specifically, following the same
pattern as ADR-0017 (E9 + E2). E3 is implemented as two real, tested polyrepos:

- **`vector-kg-graph`** — graph store + vector index.
  - `kg-graph-svc`: versioned labeled property graph, append-only `SUPERSEDES` node versioning,
    indexed by type/owner/status/confidence/time (KG §2/§3/§7).
  - `kg-vector-svc`: `kg://`-keyed embeddings, cosine `SIMILAR_TO` similarity, refresh on node
    version (KG §3).
- **`vector-kg-ingest`** — event-sourced ingestion / retrieval / reconciliation.
  - `ingest`: subscribes to `vector-bus` channels (`#events`/`#escalations`/`#contracts`), writes
    nodes/edges with provenance + confidence, idempotent on `(provenance_event,type)` (KG §6/§10).
  - `retrieval`: hybrid API — traversal / semantic / hybrid — returning confidence + provenance
    path; flags `confidence < 0.4` as uncertain (KG §8).
  - `reconcile`: diffs `vector-registry`/repos against the KG, flags orphans/missing owners within
    24h, monitors ingestion lag `< 15min` (Bible §8 R7, KG §10).

Both repos are implemented in **Node/TypeScript** with **zero runtime dependencies**, validated by
`node --test` plus the shared `validate.mjs`. As-built test counts (to be confirmed by the gate):
`vector-kg-graph` = 30 (graph-store 19 + vector-index 11); `vector-kg-ingest` = 35 (ingest 15 +
retrieval 11 + reconcile 9).

Governance wiring completed this session:
- `registry.yaml` gains repos `vector-kg-graph` + `vector-kg-ingest`, services `kg-graph-svc`,
  `kg-vector-svc`, `kg-ingest-svc`, `kg-retrieval-svc`, `kg-reconcile-svc`, and four dependency
  edges (ingest→bus, ingest→kg-graph, ingest→registry, kg-graph→bus); all `from`/`to` reference
  registered repo ids.
- `kg/index.json` gains the two `Repository` nodes and six `DEPENDS_ON`/`REFERENCES` edges
  (REFERENCES target `kg://doc/wbs`).

## Consequences

- Positive: the permanent engineering memory exists and is retrievable before the agent fleet that
  consumes it is built; registry↔KG reconciliation can enforce ownership R7 early.
- Positive: E3 reuses the E9 registry + E2 bus foundation (no new transport or ownership system).
- Negative: E3 carries its own maintenance surface (graph store + vector index + 3 ingest modules)
  before a single agent consumes it.
- The ADR-0005 deferral now stands only for E1 and E4–E8; this ADR does not lift those.

## Alternatives considered

- **Keep E3 deferred (status quo of ADR-0005).** Rejected: the foundation order in WBS §10 names E3
  as the next epic after E9+E2, and every later epic depends on KG retrieval/reconciliation; delaying
  E3 would force those epics to fake the KG.
- **Build E3 as a single repo.** Rejected: the KG v1 file-based index and WBS E3.C1/E3.C2 separate
  storage from ingestion; two focused polyrepos match the WBS structure and keep blast radius small.
- **Add a third `vector-kg-blob` repo (E3.C1.S3).** Deferred: blob store not required for the
  versioned labeled property graph + cosine index; added later if needed.

## Status / References

- **Status:** Accepted (2026-07-12).
- References: adr-0017 (E9+E2 foundation), adr-0005 (product-first deferral), adr-0006 (Node/TS
  scaffold runtime), WBS §10, KNOWLEDGE_GRAPH.md §2/§3/§6/§7/§8/§10/§12, ORGANIZATIONAL_BLUEPRINT.md
  §6/§11, ENGINEERING_BIBLE.md §8 R7 / §5 / §10.
- Repo-local ADRs in `vector-kg-graph`/`vector-kg-ingest` are the authoritative component records;
  this org-wide ADR indexes them.
