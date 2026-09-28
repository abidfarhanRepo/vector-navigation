# ADR-0066 — vector-privacy bounded context (learn from aggregates)

- **Status:** Accepted
- **Date:** 2026-08-04
- **Deciders:** D5 Security & Compliance / D3 Architecture
- **Supersedes:** none
- **Superseded by:** none

## Context
The ratified decision (see `vector-governance/adr/adr-0065-privacy-gate.md`) is
binding org-wide. This repo-local ADR records the bounded-context contract that
`vector-privacy` owns, so a reader of this repo does not need to cross the
polyrepo boundary to know what it is for. Per Bible D6 (no duplicate truth) the
thresholds themselves are **not** restated here — adr-0065 is their one
canonical location and this ADR cites it.

## Decision
`vector-privacy` owns the single definition of the privacy rules applied to raw
location data before it enters any store: the accuracy floor, endpoint
truncation, temporal coarsening, coordinate precision, and per-trip pseudonym.
Consumers `vector-web` (S1 ingest gate) and `vector-learning` (S3 aggregate)
vendor it and must not re-implement or relax the rules.

## Consequences
- The rules cannot drift between ingest and aggregation.
- Changing a threshold is an ADR decision against adr-0065, not a constant edit.
- Positive: privacy is structural, not a feature to add later.
- Negative: a hard rule boundary means consumers must be vendored in step.

## Alternatives considered
- **Rules inline in `vector-web`:** rejected — `vector-learning` would need a
  second copy, and the two would drift. That drift is precisely the failure this
  bounded context exists to prevent.
- **Rules as config, not code:** rejected — a threshold that can be lowered by
  editing a config file is not a binding privacy guarantee.

## References
- `vector-governance/adr/adr-0065-privacy-gate.md` (authority — binding thresholds)
- issue `01` (`.scratch/vector-evolve/issues/01-privacy-gate-lib.md`)
