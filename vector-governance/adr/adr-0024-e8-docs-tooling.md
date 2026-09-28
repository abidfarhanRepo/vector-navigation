# ADR-0024 — E8 Documentation & Knowledge Tooling: lift the ADR-0005 deferral for E8

- **Status:** Accepted
- **Date:** 2026-07-13
- **Deciders:** CTO / D7-docs
- **Supersedes:** — (partially lifts ADR-0005 for E8 only)
- **Superseded by:** none

## Context
With E9/E2/E3/E1/E4/E5/E6/E7 built, the final deferred epic — E8 (Documentation & Knowledge Tooling) — is unblocked. Docs-as-code and ADR/KG tooling were identified as the last gap in the agent-platform foundation.

## Decision
Lift the ADR-0005 deferral for **E8 (Documentation & Knowledge Tooling)** specifically, following the same pattern as ADR-0017–0023. E8 is implemented as one Node/TS polyrepo `vector-docs` (owner squad `d7-docs`), providing service `docs-svc`:
- **`doc-lint`** — doc staleness checker (Bible §7 D1: docs updated with code in same PR) + missing squad-docs reporter; integrates with the E4 quality gate (E8.C1.S1).
- **`adr-tool`** — enforces ADR format (Bible §11 A2), marks superseded without deleting (A3), indexes ADRs as KG Decision nodes (E8.C2.S1).
- **`kg-portal`** — knowledge portal: queries the E3 KG by type/owner/text with confidence + provenance path (E8.C2.S2).

All consume E2 Bus / E9 Registry / E3 KG by DI; zero cross-repo imports. Register in registry.yaml + mirror as KG node in kg/index.json with non-orphan edges.

## Consequences
- Positive: documentation and ADR hygiene are now first-class, machine-enforced, and queryable through the KG — closing the last agent-platform gap.
- Negative: E8 adds its own runtime surface before a full fleet consumes it.
- The ADR-0005 deferral is now fully lifted (all E1–E9 built).

## Alternatives considered
- **Keep E8 deferred.** Rejected: it is the last epic and unblocks docs-as-code governance.
- **Build E8 as multiple repos.** Rejected: WBS scopes E8 as one `vector/docs` repo; one focused polyrepo matches the Wave 3–8 pattern.

## References
- adr-0005, adr-0017, adr-0018, adr-0019, adr-0020, adr-0021, adr-0022, adr-0023
- `docs/WBS.md` (E8, §10)
- `docs/ENGINEERING_BIBLE.md` (§7 D1, §11 A2/A3)
