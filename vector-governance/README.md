# vector-governance

> **Role:** Constitution & single source of truth for Project Vector (Bible §20, Blueprint §11).
> **Owner squad:** D7 (Documentation & Knowledge) / D3 (Architecture & Standards).
> **Write scope:** governance docs, ADRs, the org registry, and the lightweight Knowledge Graph. NEVER product code.

This repository holds the permanent standards and memory-index of Project Vector. Every other
repository is bound by the documents here and MUST be registered in `registry/registry.yaml`.

## Contents

### Standards (canonical)
- `docs/ENGINEERING_BIBLE.md` — the binding constitution for all agents (RFC 2119 MUST/SHALL).
- `docs/ORGANIZATIONAL_BLUEPRINT.md` — the 9-department AI agent org design.
- `docs/MASTER_ORCHESTRATOR.md` — the Tier-1 COA agent specification.
- `docs/KNOWLEDGE_GRAPH.md` — design of the permanent engineering memory.
- `docs/AI_RESEARCH_DEPARTMENT.md` — D8 read-only intelligence charter.
- `docs/WBS.md` — work breakdown structure (Epics E1–E9 platform + product scope tree).
- `docs/Vector_Master_Roadmap.md` — product vision & open-source strategy.
- `docs/Vector_System_Architecture.md` — foundation system architecture (M0–M9).
- `docs/Vector_Engineering_Bible.md` — **LEGACY** short bible; superseded by `ENGINEERING_BIBLE.md`. Kept for history only.

### Operational
- `registry/registry.yaml` + `registry/schema.json` — repos→squads, services→repos, dependency edges. Source of truth (Blueprint §11).
- `kg/index.json` + `kg/schema.json` — lightweight v1 Knowledge Graph (nodes + edges, provenance, confidence).
- `adr/` — architecture decision records.
- `scripts/` — lightweight CI validators.

## How to use
1. Before any task, retrieve the relevant Bible/Blueprint sections and any ADR from this repo (Bible §0, §19 F4).
2. Register any new repo in `registry/registry.yaml` and add its nodes to `kg/index.json`.
3. Keep CODEOWNERS and the registry in sync.
4. Run `npm run validate` for structure/parse checks.

## Repository map (foundational)
| Repo | Purpose |
|------|---------|
| `vector-governance` | this repo — constitution, registry, KG index |
| `vector-contracts` | language-neutral shared contracts (OpenAPI/Protobuf/JSON Schema/events/DTOs/error codes) |
| `vector-common` | shared domain models, geometry, coordinates, identifiers, errors, utilities (Node/TS tier) |
| `vector-playground` | prototypes, benchmarks, PoCs, experiments (never production) |
