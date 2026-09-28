# ADR-0021 — E5 Security & Compliance Platform: lift the ADR-0005 deferral for E5

- **Status:** Accepted
- **Date:** 2026-07-12
- **Deciders:** CTO / D5-security + D1-platform
- **Supersedes:** — (partially lifts ADR-0005 for E5 only)
- **Superseded by:** none

## Context

ADR-0005 (product-first) deferred E1–E9. ADR-0017 lifted E9+E2; ADR-0018 lifted E3; ADR-0019
lifted E1; ADR-0020 lifted E4. With E9/E2/E3/E1/E4 built and tested, the next epic in the
foundation order — E5 (Security & Compliance) — is unblocked. Note E4's SecurityGate currently
runs a heuristic stub with a wired-but-unused `setE5Scanner` extension point (WBS E4.C2.S1 depends
on E5).

## Decision

Lift the ADR-0005 deferral for **E5 (Security & Compliance)** specifically, following the same
pattern as ADR-0017/0018/0019/0020. E5 is implemented as one real, tested Node/TS polyrepo
`vector-security` (owner squad `d5-security`), providing:

- **`vault-svc`** — token-broker: short-lived scoped credential broker (short-lived scoped creds,
  rotation without code change, zero hardcoded secrets).
- **`audit-log`** — append-only tamper-evident audit log.
- **`threat-intel`** — CVE/NVD/GHSA intake, L3 escalation, writes `Dependency` nodes to KG by DI.
- **`createE5SecurityScanner()`** — satisfies E4's `SecurityGate.setE5Scanner(fn)` contract to give
  E4's security gate real depth (SAST/dep-vuln/secret/IaC).

All consume E2 Bus / E9 Registry / E4 gates by DI; zero cross-repo imports. Register in
`registry.yaml` + mirror as KG node in `kg/index.json` with non-orphan edges.

## Consequences

- Positive: E4 security gate now has real depth via the wired `setE5Scanner`; credential grants are
  short-lived and auditable; CVEs flow to escalations + KG.
- Negative: E5 adds its own runtime surface before a full fleet consumes it.
- The ADR-0005 deferral now stands only for E6–E8.

## Alternatives considered

- **Keep E5 deferred.** Rejected: the foundation order names E5 next; E4's security gate is only
  heuristic without it.
- **Build E5 as multiple repos.** Rejected: WBS scopes E5 as one `vector/security` repo; one
  focused polyrepo keeps blast radius small and matches the Wave 3–5 pattern.

## References

- adr-0005, adr-0017, adr-0018, adr-0019, adr-0020
- `docs/WBS.md` (E5, §10)
- `docs/ENGINEERING_BIBLE.md` §4 S2/S7/S9, §19 F6
- `docs/ORGANIZATIONAL_BLUEPRINT.md` (D5)
