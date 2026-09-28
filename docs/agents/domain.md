# Domain Docs (Vector-adapted)

How the engineering skills should consume Vector's domain documentation when
exploring the codebase.

## Before exploring, read these

Vector is a **multi-context polyrepo**, so there is no single `CONTEXT.md` at
the workspace root. Read the following instead:

- **`vector-governance/docs/ENGINEERING_BIBLE.md`** — the binding constitution
  for all agents (RFC 2119 MUST/SHALL). Read first on any non-trivial task.
- **`vector-governance/docs/ORGANIZATIONAL_BLUEPRINT.md`** — the 9-department
  AI agent org design.
- **`vector-governance/adr/`** — architecture decision records (immutable,
  registry-as-truth). Read any ADR touching the area you're about to work in.
- **`vector-governance/registry/registry.yaml`** — the map of repos → squads,
  services → repos, and dependency edges (source of truth).
- **`vector-governance/kg/index.json`** — the lightweight Knowledge Graph
  (nodes + edges, provenance, confidence).

Individual `vector-*` repos MAY carry their own `CONTEXT.md` / `docs/adr/` for
repo-scoped context. If a file doesn't exist, **proceed silently** — don't flag
its absence. The `/domain-modeling` and `/ubiquitous-language` skills create a
repo-level glossary lazily when terms or decisions actually get resolved; for
platform-wide decisions, route through `vector-governance` ADRs.

## Use the glossary's vocabulary

When your output names a domain concept (issue title, refactor proposal,
hypothesis, test name), use the term as defined in the governance docs and
existing ADRs. Don't drift to synonyms the glossary explicitly avoids.

If the concept isn't defined yet, that's a signal — either you're inventing
language the project doesn't use (reconsider) or there's a real gap (note it
for `/domain-modeling`, resolved into a `vector-governance` ADR).

## Flag ADR conflicts

If your output contradicts an existing ADR, surface it explicitly rather than
silently overriding:

> _Contradicts ADR-00XX (…) — but worth reopening because…_
