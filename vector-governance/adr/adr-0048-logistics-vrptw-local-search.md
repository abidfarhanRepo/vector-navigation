# ADR-0048 — Logistics VRPTW local-search hardening (Or-opt + cross-route moves)

- **Status:** Accepted
- **Date:** 2026-07-15
- **Deciders:** d2-product (vector-logistics), d3-architecture (governance), d7-docs (governance)
- **Supersedes:** none
- **Superseded by:** none
- **Decided by:** adr-0047 (and adr-0046)

## Context

adr-0046 ("Logistics capacitated VRP (CVRP)") extended `vector-logistics` **in place** from
multi-vehicle VRP-lite (adr-0045) to Capacitated VRP, honoring optional `capacities`/`demands`.
adr-0047 ("Logistics Time-Window VRP (VRPTW)") extended it **in place** again to Time-Window VRP:

- **Wave 33/34 (adr-0047)** shipped VRPTW **evaluate-and-report** — on the already-committed tour it
  computes per-vehicle `arrivals`/`violations` plus the top-level `window_violations`/`start_time` on
  both the single-vehicle and multi-vehicle paths.
- **Wave 35 (still adr-0047)** added a **window-optimizing insertion path**: a cheapest-insertion
  builder (earliest-deadline-first) followed by a window-aware 2-opt (`_two_opt_window`), composed in
  `_window_aware_order`, that re-sequences stops **within each route** to minimize the lexicographic
  `(window_violations, travel)` objective. It kept the vehicle **assignment** fixed — `adr-0047`'s
  multi-vehicle path only re-orders stops inside the routes assigned by the sweep / nearest-depot
  heuristic.

Wave 36 hardens that window-optimizing solver with two additional local-search operators:

1. **Or-opt (intra-route)** — relocates contiguous delivery chains *within* a single route, still only
   re-ordering inside the fixed assignment.
2. **Cross-route (inter-vehicle)** — for the first time, *may reassign a single delivery from one
   vehicle to another* when that strictly reduces the fleet-level objective.

Both operators are optional, additive refinements of the existing `vector-logistics` repo/agent/service
— same agent address, same 5 channels, same envelope, same `/logistics` surface — and are active ONLY
when time windows / service times are present. They honor the hard constraints carried from adr-0025 /
adr-0043 / adr-0044 / adr-0045 / adr-0046 / adr-0047: stdlib-only Python, no external solver, zero
sibling imports, no new repo/service/agent/channel/ADR-contract-shape, no `vector-bus` core change.

## Decision

`vector-logistics`'s window-optimizing VRPTW solver is hardened in place with two local-search
operators. The contract (request/result fields, channels, envelope) is **unchanged** from adr-0047;
this ADR records the new solver behavior. The operators are exactly as implemented:

**Or-opt (intra-route) — `_or_opt_window`.** On the already-built window-aware order, this relocates a
contiguous chain of `L` consecutive deliveries (for `L` in `{1, 2, 3}`) from its current position to a
different insertion position. It accepts a candidate only when it **strictly reduces** the lexicographic
cost `(violations, travel)` (violations first, else travel), holding the depot (local index 0) fixed at
position 0 and never re-inserting a chain at its original location. After each accepted move it
re-scans from the start, mirroring the `_two_opt` / `_two_opt_window` pattern. It is composed **after**
`_cheapest_insertion` + `_two_opt_window` inside `_window_aware_order`, so it runs in both the
single-vehicle and multi-vehicle window paths. It is a pure intra-route move — it never changes which
vehicle serves a stop.

**Cross-route (inter-vehicle) — `_cross_route_optimize`.** In the **multi-vehicle window path only**,
after the sweep / nearest-depot (capacity-aware or not) assignment, this relocates a **single delivery**
`i` from vehicle `a` to a different vehicle `b` when the move strictly reduces the **fleet-level**
lexicographic cost `(sum of per-vehicle window_violations, sum of per-vehicle travel)`. Each vehicle's
cost is evaluated by re-optimizing its own order via `_window_aware_order` on its subset cost matrix
(with a memoization cache keyed by `(depot_key, tuple(group))`). Capacity feasibility is preserved: a
move into `b` is allowed only when `b` has room (`loads[b] + demand[i] <= caps[b]`); when `capacities`
is absent/empty (`caps is None`) the move is unlimited. It uses a **first-improvement, deterministic
scan** (ascending `a`, position order within `a`, ascending `b`), restarts after each accepted move, and
is bounded by a **safety cap** (`max(20, 4 * n)` moves). It is wired only behind
`if windows_present:` in `solve` (the `vehicles > 1` branch), gated by the same `windows_present`
condition that selects `_window_aware_order` per route.

**This ADR explicitly supersedes the "assignment unchanged" scope of adr-0047 for the windows-present
case.** adr-0047 kept the multi-vehicle assignment fixed and only re-ordered stops within each route;
Wave 36's cross-route operator MAY change the vehicle assignment when time windows are present and doing
so strictly improves the fleet-level `(window_violations, travel)` objective. All other adr-0047
guarantees remain: when `time_windows`/`service_times` are absent/empty the solver behaves **byte-for-byte
identically to adr-0046/adr-0047** (no Or-opt, no cross-route, no window keys), and the single-vehicle
path is unchanged except for the inert `windows_present` branch.

**HARD CONSTRAINTS honored (all unchanged from adr-0047):**

- stdlib-only Python solver; **no external solver** (no OR-Tools or similar); **no sibling imports**.
- No new repo / service / agent address / bus channel / ADR contract shape; **no `vector-bus` core change**.
- Result keys are **unchanged** — this wave reuses the `arrivals` / `violations` /
  `window_violations` / `start_time` fields introduced by adr-0047. No new result key is added.
- The multi-vehicle result always has exactly `vehicles` route entries (cross-route only *moves*
  deliveries between existing routes; it never creates or removes a route).
- The no-window and `fixed_order` paths are **byte-for-byte identical** to adr-0046/adr-0047.
- Backward compatibility preserved: viewers/consumers that read `order`/`geojson`/`routes`/`vehicles`
  keep working; the added VRPTW keys remain strictly additive.

## Consequences

- `vector-logistics` is extended **in place** — no new repo, no new service, and no new agent address
  are registered. Because this wave adds a NEW decision record (its own ADR) rather than silently
  extending adr-0047, **`registry.yaml`, `vector-registry/data/registry.json`, and the contracts
  (`vector-contracts/schemas/logistics-*.schema.json`, `dtos/dto.examples.json`) are left UNCHANGED** —
  same repo/service/agent, no new registry node/edge. The ONLY governance-graph change is the addition
  of one KG node `kg://adr/0048` (with 2 outgoing edges) — see item 3 below — so the governance
  validator stays GREEN (0 errors / 0 warnings).
- The Viewer/logistics panel needs **no new route** (and no change this wave): the optimizer is
  server-side and auto-activates when windows are present, exactly as in adr-0047. No viewer edit.
- Contracts/viewer unchanged: the VRPTW result keys already exist (adr-0047); no schema DTO change.
- The solver remains a **self-contained stdlib-only Python** engine (extending adr-0046's
  capacity-aware sweep / nearest-depot assignment + per-vehicle cost-matrix A\* + nearest-neighbor +
  2-opt, adr-0047's cheapest-insertion + window-aware 2-opt, and now Wave 36's Or-opt + cross-route
  local search), preserving the zero-sibling-import, no-external-solver constraint; bounded by the
  cross-route safety cap, suitable for the `sla_ms` budget.
- **Acceptance = the verification gate** (`node run-ci.mjs --repo vector-logistics` and
  `node run-ci.mjs --repo vector-contracts` in the workspace, `node vector-governance/scripts/validate-registry-kg.mjs`,
  and `node vector-infra/scripts/validate.mjs`) is GREEN.
- **Honest environment caveat:** in the current environment Docker + Python are unavailable (no real
  Python interpreter; the MSVC/Store stub cannot run the `vector-logistics` suite), so the authoritative
  Python unit gate (`node run-ci.mjs --repo vector-logistics` executing the containerized
  `unittest discover`) is **DEFERRED to a Docker-up Code-Agent run**. The Node-side
  `vector-contracts` ajv/DTO gate (`node --test` on the schema + DTO-example assertions), the
  `vector-tile-gen` MVT spec-anchor guard, and the governance validator **ARE runnable locally** and are
  the parts of the gate that can be executed here. Unlike Wave 35 (which added no KG change), **this
  wave DOES add one KG node + 2 edges** (`kg://adr/0048` + `IMPLEMENTS`/`DECIDED_BY`); that addition is
  expected to validate **GREEN (0 errors, 0 warnings)** — it introduces a connected node, not an orphan.

## Alternatives considered

- **Reuse adr-0047 with no new record.** Rejected: the cross-route operator *changes the vehicle
  assignment*, which departs from adr-0047's explicit "assignment unchanged" scope for the
  windows-present case. That decision point is worth its own Accepted record rather than an inline edit
  to an already-closed ADR.
- **Full Solomon I1 time-oriented insertion.** Deferred as heavier future work. Solomon-style
  I1 is a substantially larger constructive heuristic (route-seeding by time orientation, regret-2/3
  insertions) than the local-search hardening added here; it is the natural next horizon but was out of
  scope for this wave, which deliberately keeps the low-blast-radius, in-place local-search style.
- **Or-tools / external solver.** Rejected: breaks the self-contained stdlib-only constraint carried
  from adr-0025 / adr-0044 / adr-0046 / adr-0047 (no external runtime dependency, no sibling imports).

## References

- adr-0047-time-window-vrp.md — Logistics Time-Window VRP (VRPTW); the contract this ADR extends and
  hardens (DECIDED_BY adr-0047).
- adr-0046-logistics-cvrp.md — Logistics capacitated VRP (CVRP); the contract this ADR ultimately
  extends (DECIDED_BY adr-0046).
- adr-0045-logistics-vrp-multivehicle.md — Logistics multi-vehicle VRP-lite (DECIDED_BY adr-0045).
- adr-0044-logistics-bus-consumer.md — Logistics bus consumer (the single-vehicle contract; DECIDED_BY
  adr-0044).
- vector-contracts/schemas/logistics-request.schema.json — logistics request envelope schema (UNCHANGED
  this wave).
- vector-contracts/schemas/logistics-result.schema.json — logistics result envelope schema (UNCHANGED
  this wave; reuses `arrivals`/`violations`/`window_violations`/`start_time` from adr-0047).
