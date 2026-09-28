# ADR-0065 — Privacy gate: learn from aggregates over road segments, never from individual traces

- **Status:** Accepted
- **Date:** 2026-08-04
- **Deciders:** D5 Security & Compliance / D3 Architecture / D6 Data
- **Supersedes:** none
- **Superseded by:** none

## Context

Vector's stated goals are to make its map **self-evolving**, **privacy-friendly**
and **easily hostable**, with collected data *properly sorted* and coverage that
*grows* from use. These goals are usually in tension: the more data the learning
loop keeps, the worse the privacy posture. This ADR dissolves the tension with a
single binding rule and records the exact thresholds that follow from it.

Until now `vector-web/src/vector_web/trace_store.py` appends raw GPS breadcrumbs
to a plaintext JSONL file with no pseudonymization, no endpoint truncation, no
k-anonymity, no retention policy, and no TTL — its own docstring concedes the
processor that would consume it "does not exist". That is a raw-GPS dataset
that cannot be retroactively anonymized and would have to be destroyed. The
privacy layer must land **before** any real traces accumulate.

This ADR closes the `U3 — Regulatory on location data` open risk.

## Decision

### The rule

> **Learn from aggregates over road segments. Never from individual traces.**

Raw location data lives in a short-TTL quarantine and is **never** what
persists. What persists is per-segment evidence that has already cleared a
k-anonymity floor — which is not personal data at all.

### Binding thresholds (the authority for all later code)

| Parameter | Value | Where enforced |
|---|---|---|
| K (distinct trip pseudonyms per segment) | **5** | `vector-learning` aggregation (S3) — not configurable below 5 |
| Quarantine TTL | **72 hours** | `vector-web` quarantine store (S2), enforced by a vacuum job |
| Endpoint truncation | **200 m** at each end of every trip | client edge (S0) **and** server gate (S1) |
| Accuracy floor | **25 m** — points with `a > 25` are dropped | S0 + S1 |
| Temporal coarsening | timestamps rounded to **5 s** | S0 + S1 |
| Coordinate precision | capped at **5 decimal places** (~1 m) | S1 |

### Enforcement posture

1. **The client is never trusted.** The browser edge (S0) applies all rules, but
   the server (S1) re-applies every rule at the ingest boundary before the sink.
2. **One shared definition.** All rules live in the `vector-privacy` library,
   vendored into both `vector-web` and `vector-learning`, so ingest and
   aggregation cannot drift apart.
3. **The pseudonym is per-*trip*, never per-device.** A fresh opaque random ID
   is minted on trip start and rotates on trip end. No device identifier, IP,
   or user ID is ever stored alongside a point.
4. **The gate is total.** `apply_gate` never raises on malformed input and
   returns the reasons points were dropped, so `vector-observability` can emit
   privacy counters (issue `10`) that prove the layer is doing work.

## Consequences

- **Positive:** self-learning and privacy cease to be a trade-off. The persisted
  data is aggregate evidence past the k-anonymity floor — non-personal.
- **Positive:** homes and workplaces cannot be derived from the (TTL'd) store —
  every track is short by 200 m at each end.
- **Positive:** a frequent single-trip destination cannot become a candidate,
  because no candidate exists without ≥ K distinct trips.
- **Negative:** sub-400 m tracks are dropped entirely by truncation (privacy win,
  but a small coverage cost at very short trips).
- **Negative:** thresholds are fixed until a **new** ADR supersedes this one.
- **Regulatory:** closes the `U3` open risk for location data.

## Addendum — narrow amendment for destination-precision learning (Status: Proposed)

> **This addendum is a NARROW AMENDMENT, not a revocation of the endpoint privacy principle.**
> It is introduced by ADR-0074 (destination-precision learning). It is **Proposed** and
> becomes live only when ADR-0074 is ratified. Until then, the original ADR-0065 endpoint
> protection below stands unchanged.

**ADR-0065's endpoint protection remains in force for individual-level data.** No individual
user's precise destination may become a persistent personal fact. The 200 m endpoint truncation
at ingest (`_truncate_endpoints`) continues to protect every ordinary trace exactly as written.

A **narrowly scoped** precise endpoint may temporarily exist **only** for a privacy-controlled
aggregation process, and only subject to ALL of the following binding constraints:

- **Separate precision DB** — the precise endpoint lives in its own database file, never as a
  column in the normal observation table, and is never reachable through normal trace APIs.
- **≤ 24 h maximum lifetime** — a hard TTL shorter than the 72 h general quarantine TTL, because
  a destination endpoint is a higher-sensitivity location class than a mid-trip point.
- **Delete-on-consume** — the row is removed the moment the aggregation batch that reads it
  completes; it need not outlive the batch.
- **No backups / no snapshots / no dumps / no diagnostic bundles** — the precision DB is
  explicitly excluded from every retention and export path (a TTL in the live DB is worthless if
  the same data lives forever in a backup).
- **S3-only access** — only the `vector-learning` S3 aggregation worker may read it; `vector-web`,
  `nearby()`/`recent()`/`count()`, client APIs, admin tools, debug tooling, metrics,
  `vector-geocoder`, `vector-routing`, and published fact APIs are all forbidden.
- **K ≥ 5** distinct trips, preserved unchanged.
- **Destination-specific dispersion requirements** — spatial dispersion, temporal dispersion +
  band diversity, concentrated-cluster suppression, and a contributor-diversity proxy (because
  self-minted tokens do not prove distinct humans — see ADR-0068 amendment).
- **No individual endpoint publication**, **no trajectory publication**, **no fine timestamp
  publication** — only a grid-snapped (≥ 10 m) centroid of the dispersed K set is ever released.
- **Multi-user heightened consent** — in multi-user deployments the precision store is **off by
  default** and requires explicit heightened consent beyond the general ADR-0068 opt-in, because
  it would otherwise hold *other people's* precise destinations.

**Language that this addendum does NOT grant:** individual precise destinations are NOT generally
permitted. Nothing here authorizes persisting, returning, logging, backing up, or publishing any
single user's precise destination. The amendment exists solely to let a *collective* centroid
emerge under the controls above, after which the underlying precise rows are gone.

## Alternatives considered

- **Collect-first, anonymize-later** — rejected. A raw GPS dataset cannot be
  retroactively anonymized; it would have to be destroyed. Explicitly
  non-negotiable (collecting first inherits an unlawful dataset).
- **Differential privacy on raw data** — the correct tool for publishing
  *statistics* over a population, but heavier than needed for a single-user
  self-hosted map and harder to reason about; k-anonymity over road segments
  satisfies the same goal with a directly checkable floor.
- **Per-device pseudonym** — rejected: weaker than per-trip because a device's
  motion pattern links its trips, defeating endpoint truncation.

## References

- GovGOALS: `.scratch/vector-evolve/GOALS.md`
- Issue `01`: `.scratch/vector-evolve/issues/01-privacy-gate-lib.md`
- Bible §3, §16 («explicit validation at boundaries»)
- Risk register: `U3 Regulatory on location data`
