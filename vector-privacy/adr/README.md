# Architecture Decision Records

This folder holds the repository's ADRs. Format follows the Vector Engineering Bible §11 A2:
**Context -> Decision -> Consequences -> Alternatives considered -> Status**.

- ADRs are immutable once Accepted. Reversal requires a NEW ADR that SUPERSEDES the old; the old is marked, never deleted.
- Index new ADRs in this file.

## Index
| ADR | Title | Status |
|-----|-------|--------|
| [template](template.md) | Template | Reference |
| [adr-0066-privacy-bounded-context](adr-0066-privacy-bounded-context.md) | vector-privacy bounded context — learn from aggregates, never individual traces | Accepted |

Binding thresholds are ratified org-wide in
`vector-governance/adr/adr-0065-privacy-gate.md`, not here (Bible D6).