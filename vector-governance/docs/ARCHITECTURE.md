# Architecture Overview — vector-governance

This repository is **not a runtime service**. It is the governance & memory substrate of Project
Vector. It owns the documents every other repo is measured against, the authoritative registry of
ownership/dependency, and the v1 Knowledge Graph index.

## Bounded context
- **In scope:** standards (Bible, Blueprint, COA spec, KG design, research charter), the org WBS &
  roadmap, the machine-readable `registry/`, and the `kg/` index.
- **Out of scope:** any product code, any deployed service, any agent runtime. This repo holds
  *specs and memory*, not implementations (see MASTER_ORCHESTRATOR.md §3.9 / §8 write scope).

## Relationship to other repos
- `vector-contracts` — definitions referenced by product repos; governance ratifies contract ADRs.
- `vector-common` — implements shared types for the Node/TS tier; cross-language truth stays in contracts.
- `vector-playground` — may read the KG but never writes to `main` of any product repo.

## Evolution
- The KG here is **v1 file-based** (see `adr/adr-0004`). It is intentionally schema-compatible with
  the future E3 hybrid platform so today's memory is ingestible later (KG §5 keying `kg://id`).
- CI is intentionally lightweight (markdown/YAML/JSON/structure). Gates expand as repos mature
  (Bible §19 F1), driven from the standards in this repo.
