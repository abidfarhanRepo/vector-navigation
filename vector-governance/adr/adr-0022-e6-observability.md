# ADR-0022 — E6 Observability & Reliability Platform: lift the ADR-0005 deferral for E6

- **Status:** Accepted
- **Date:** 2026-07-12
- **Deciders:** CTO / D1-platform + D9-ops
- **Supersedes:** — (partially lifts ADR-0005 for E6 only)
- **Superseded by:** none

## Context

ADR-0005 (product-first) deferred E1–E9. ADR-0017 lifted E9+E2; ADR-0018 lifted E3; ADR-0019
lifted E1; ADR-0020 lifted E4; ADR-0021 lifted E5. With E9/E2/E3/E1/E4/E5 built, the next epic in
the foundation order — E6 (Observability & Reliability) — is unblocked.

## Decision

Lift the ADR-0005 deferral for **E6 (Observability & Reliability)** specifically, following the same
pattern as ADR-0017/0018/0019/0020/0021. E6 is implemented as one Node/TS polyrepo
`vector-observability` (owner squad `d1-platform`, secondary `d9-ops`), providing service
`otel-svc`:

- **`metrics`** — golden signals (latency/traffic/errors/saturation); emits `Metric` nodes to the
  KG by DI.
- **`logs`** — structured logs with correlation IDs and secret/PII redaction.
- **`traces`** — distributed correlation propagation with 100% error-trace retention.
- **`alert-mgr`** — SLOs + error budgets, actionable alerts with runbook links; routes to the owning
  squad via E9 Registry by DI.

All consume E2 Bus / E9 Registry / E3 KG by DI; zero cross-repo imports. Register in
`registry.yaml` + mirror as KG node in `kg/index.json` with non-orphan edges.

## Consequences

- Positive: every service now has the three observability pillars (metrics/logs/traces) plus
  SLO/alerting available.
- Negative: E6 adds its own runtime surface before a full fleet consumes it.
- The ADR-0005 deferral now stands only for E7–E8.

## Alternatives considered

- **Keep E6 deferred.** Rejected: the foundation order names E6 next.
- **Build E6 as multiple repos.** Rejected: WBS scopes E6 as one `vector/observability` repo; one
  focused polyrepo keeps blast radius small and matches the Wave 3–6 pattern.

## References

- adr-0005, adr-0017, adr-0018, adr-0019, adr-0020, adr-0021
- `docs/WBS.md` (E6, §10)
- `docs/ENGINEERING_BIBLE.md` §15 O1–O7
- `docs/ORGANIZATIONAL_BLUEPRINT.md`
