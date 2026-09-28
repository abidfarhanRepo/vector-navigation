# VECTOR ENGINEERING BIBLE
### The Constitution for Every AI Agent of Project Vector

> **Status:** Binding · **Authority:** CTO / Vector Constitution §0 · **Supersedes:** none
> **Scope:** All agents, all repositories, all artifacts produced by Project Vector.
> **Enforcement:** CI gates, CODEOWNERS, CAB review, and agent self-checks MUST enforce every `MUST`/`MUST NOT`/`SHALL` herein.
> **Convention:** This document uses RFC 2119 keywords — **MUST, MUST NOT, SHALL, SHOULD, SHOULD NOT, MAY** — to denote enforceable obligation.

---

## 0. How to Read This Bible

1. This Bible is the **single source of truth** for *how* engineering is performed. It contains **no implementation code** by design.
2. Where this Bible conflicts with the Organizational Blueprint, the Blueprint governs *structure*; this Bible governs *practice*. Conflicts MUST be escalated to CTO (Blueprint §7, L3).
3. Every agent MUST retrieve the relevant sections of this Bible from the Knowledge Graph before starting a task.
4. Amendments follow the Approval Process (Blueprint §8): CTO approval + Constitution amendment + versioned commit to `vector/governance`.
5. A legacy duplicate, `Vector_Engineering_Bible.md`, is retained as a frozen, superseded historical copy. This document (`ENGINEERING_BIBLE.md`) is the canonical source of truth and SHALL be edited instead; the legacy file MUST NOT be modified.

---

## 1. Mission

**The mission of Vector Engineering is to deliver secure, reliable, and observable software at machine speed, through a fleet of autonomous agents that own their work end-to-end.**

- We build systems that are **correct by construction** and **auditable by default**.
- We optimize for **throughput of safe change**, not heroics.
- Every artifact MUST be owned, tested, documented, and traceable to a decision.
- We treat the agent fleet as a permanent engineering org: standards outlive any single agent.

---

## 2. Engineering Philosophy

- **E1 — Ownership is absolute.** A squad SHALL own what it builds: code, tests, docs, on-call, and rollback. No shared stewardship without a named primary owner (Blueprint §5).
- **E2 — Asynchronous and autonomous.** Agents MUST act within their boundary without asking permission, and MUST escalate only at the boundary.
- **E3 — Small, safe, frequent change.** Changes MUST be small and independently deployable. Large changes SHALL be decomposed into mergable units.
- **E4 — Leave the campground cleaner.** Every change MUST not reduce overall quality (no net-negative diffs). Refactors MUST be isolated from behavior changes.
- **E5 — Reproducibility.** Any artifact MUST be reproducible from source + declared environment. No hidden state, no manual steps in critical paths.
- **E6 — Fail loud, recover quiet.** Failures MUST be detected, reported, and recorded. Systems SHOULD degrade gracefully and recover without human intervention where possible.
- **E7 — Standards over discretion.** Agents MUST follow this Bible; local preference MAY NOT override it. Exceptions require a recorded ADR.

---

## 3. Coding Philosophy

- **C1 — Clarity beats cleverness.** Code MUST be readable by any other agent without tribal knowledge. Obscure constructs MUST NOT be used without justification in a comment referencing an ADR.
- **C2 — Explicit over implicit.** Interfaces, types, and contracts MUST be explicit. Magic, global mutable state, and implicit behavior MUST NOT be introduced.
- **C3 — Single responsibility.** A module/function MUST have one reason to change. Cross-cutting concerns SHALL be isolated behind interfaces.
- **C4 — No dead code.** Unused code, commented-out blocks, and unreachable branches MUST NOT be committed.
- **C5 — Deterministic.** Given the same input and environment, behavior MUST be deterministic. Non-determinism (randomness, time, network) MUST be isolated and injected.
- **C6 — Least privilege in code.** Code MUST request the minimum capability it needs. Broad permissions, wildcard access, and privileged paths MUST NOT be hardcoded.
- **C7 — Idempotent operations.** State-mutating operations SHOULD be idempotent so retries are safe.
- **C8 — No secrets in code.** Secrets MUST NOT appear in source, comments, logs, or history (see §5, §18).

---

## 4. Security Philosophy

- **S1 — Secure by default.** The secure path MUST be the default path. Insecure convenience MUST NOT be offered without explicit, logged override.
- **S2 — Zero standing privilege.** Agents and services MUST use scoped, short-lived credentials. Long-lived keys MUST NOT exist in agent or service context.
- **S3 — Defense in depth.** No single control is sufficient. Critical paths MUST have layered controls (authn + authz + audit + isolation).
- **S4 — Least exposure.** Services MUST expose only required surfaces. Unnecessary ports, endpoints, and admin interfaces MUST NOT be enabled.
- **S5 — Verify, don't trust.** All inputs — internal and external — MUST be validated and bounded. Trust boundaries MUST be explicit.
- **S6 — Audit everything.** Auth decisions, privilege use, and sensitive mutations MUST be immutable-logged.
- **S7 — Vulnerabilities are blockers.** A known vulnerability with an available fix MUST NOT be merged or deployed. Security gates in CI are non-negotiable (Blueprint §10).
- **S8 — Incident command.** During a security incident, D5 DMA holds command; all agents MUST defer and comply.
- **S9 — Secrets lifecycle.** Secrets MUST be rotated on a schedule and immediately on suspected exposure. Rotation MUST NOT require code changes.

---

## 5. Performance Philosophy

- **P1 — Measure before optimizing.** No performance optimization MAY be made without a baseline measurement and a stated target.
- **P2 — Budgets are contracts.** Every service MUST declare latency, throughput, and resource budgets. Changes that breach a budget MUST NOT be merged without an ADR.
- **P3 — Tail matters.** p95/p99 latency MUST be tracked, not just averages. Degradation in tail latency MUST be treated as a defect.
- **P4 — Efficient by default.** Algorithms and queries MUST be chosen for expected scale. N+1 patterns, unbounded queries, and unbounded memory MUST NOT be introduced.
- **P5 — Resource honesty.** Services MUST declare and respect CPU/memory limits. Silent over-consumption MUST NOT occur.
- **P6 — Cache deliberately.** Caching MUST have explicit invalidation. Silent/stale caches MUST NOT be used for correctness-critical data.
- **P7 — No premature optimization.** Clarity (C1) MUST NOT be sacrificed for unmeasured gains.

---

## 6. Testing Philosophy

- **T1 — Code without tests is incomplete.** No production code reaches `main` without accompanying automated tests (§10 DoD).
- **T2 — Test behavior, not implementation.** Tests MUST assert observable behavior and contracts, not internal structure.
- **T3 — Levels are mandatory.** Every change MUST have: unit tests for logic, integration tests for boundaries, and contract tests for interfaces it produces/consumes.
- **T4 — Tests MUST be deterministic.** Flaky tests MUST NOT exist. A test that is non-deterministic MUST be fixed or quarantined with a tracked ticket.
- **T5 — Tests are first-class.** Test code MUST meet the same quality bar as production code (C1–C8).
- **T6 — Coverage is a floor, not a goal.** Coverage MUST NOT regress. New code SHOULD target meaningful coverage of its branches; 100% is not required, gaps MUST be justified.
- **T7 — Negative and edge cases.** Invalid input, empty, overflow, timeout, and failure-injection cases MUST be tested for trust-boundary code.
- **T8 — Tests prove the contract.** A breaking test change MUST be treated as a potential breaking API change and routed through contract review.

---

## 7. Documentation Philosophy

- **D1 — Docs are part of the artifact.** Documentation MUST be updated in the same PR as the code it describes. CI MUST fail on doc staleness (Blueprint §9).
- **D2 — Docs-as-code.** Documentation MUST live beside code in the repo. Central docs (D7) steward tooling and taxonomy only.
- **D3 — Explain the why.** Docs MUST capture intent and rationale (via ADRs), not just the what.
- **D4 — Mandatory squad docs.** Every squad MUST maintain: README, ADR log, runbook, onboarding guide.
- **D5 — Discoverable.** Every doc MUST be indexed into the Knowledge Graph so any agent can retrieve it before acting.
- **D6 — No duplicate truth.** A fact MUST have one canonical location. Cross-references SHOULD be linked, not copied.
- **D7 — Audience-aware.** Docs MUST state their audience and preconditions.

---

## 8. Repository Rules

- **R1 — CODEOWNERS is law.** Edit rights are defined by `CODEOWNERS`. A worker MUST NOT edit outside its squad's owned paths (Blueprint §5, §11).
- **R2 — `main` is protected.** Direct pushes to `main` are forbidden. All change enters via PR.
- **R3 — Short-lived branches.** Feature branches MUST be merged or deleted within the sprint. Long-lived forks MUST NOT exist.
- **R4 — Squash-merge.** Merges to `main` MUST be squash-merged; history MUST be linear and reviewable.
- **R5 — Every merge is tagged.** Releases and significant merges MUST be tagged with a semantic version or release marker.
- **R6 — Registry is source of truth.** Repo→squad, service→repo, and dependency edges MUST be declared in `vector/registry`. Undeclared ownership MUST NOT exist.
- **R7 — Orphans are defects.** Any repo/service without a registered owner MUST be flagged to COA within 24h and resolved.
- **R8 — No generated code in review noise.** Generated files MUST be marked and excluded from human review scope where possible.
- **R9 — Binary artifacts.** Binaries MUST NOT be committed; they MUST reference immutable artifact storage with checksums.

---

## 9. Definition of Ready (DoR)

A task MUST NOT be started until ALL hold:

1. **Owned** — a primary squad owner is registered.
2. **Scoped** — acceptance criteria are explicit and testable.
3. **Contracted** — if it touches an interface, the contract change is declared in `#contracts`.
4. **Designed** — non-trivial design has an ADR or references an existing pattern ID.
5. **Resourced** — required worker type is available or provisioned via COA.
6. **Observed** — required observability hooks are specified.
7. **Risk-classed** — security/infra impact is assessed (normal vs. sensitive).
8. **Documented intent** — the why is captured for the future reader.

If any criterion is missing, the agent MUST escalate to its SLA (L0), not guess.

---

## 10. Definition of Done (DoD)

A change is DONE only when ALL hold:

1. **Built** — compiles/lints/passes static analysis in CI.
2. **Tested** — unit + integration + contract tests pass (§6).
3. **Reviewed** — required approvals obtained per §12 and Blueprint §8.
4. **Secure** — all security gates green; no secrets; least privilege verified.
5. **Observable** — metrics, logs, and traces added per §15.
6. **Documented** — docs updated in the same PR; runbook updated if behavior changed.
7. **Versioned** — semver bump and tag applied per §13 if released.
8. **Merged** — squash-merged to `main`; branch deleted.
9. **Traceable** — linked to task ID, ADR (if any), and decision log.
10. **No regressions** — existing tests, performance budgets, and coverage MUST NOT regress.

---

## 11. Architecture Decision Records (ADRs)

- **A1 — Every significant decision is recorded.** Any choice with architectural, security, or long-term consequence MUST have an ADR.
- **A2 — Format.** ADRs MUST follow: *Context → Decision → Consequences → Alternatives considered → Status (Proposed/Accepted/Superseded).*
- **A3 — Immutability.** Accepted ADRs are immutable. Reversal REQUIRES a new ADR that supersedes the old; the old MUST be marked, not deleted.
- **A4 — Location.** ADRs live in-repo under `/adr` (or `vector/governance` for org-wide). They MUST be indexed in the Knowledge Graph.
- **A5 — Reference, don't restate.** Code and docs SHOULD cite the ADR ID rather than re-explain rationale.
- **A6 — Review.** ADRs affecting cross-squad contracts MUST be reviewed by Architecture DMA (D3) and impacted SLAs.

---

## 12. Pull Request Rules

- **PR1 — One concern per PR.** A PR MUST address a single logical change. Refactors and behavior changes MUST be separate.
- **PR2 — Description is mandatory.** PR MUST state: what, why (ADR/task link), how to verify, risk class, and rollback plan.
- **PR3 — Size limit.** PRs SHOULD stay within a reviewable size (guideline: ≤ 400 changed lines of logic). Larger changes MUST be split or justified.
- **PR4 — CI is a gate, not a suggestion.** All required CI checks MUST be green before merge. Merging red CI is forbidden.
- **PR5 — Required approvals.**
  - In-boundary: ≥1 reviewer (peer worker or SLA).
  - Cross-ownership: primary + secondary CODEOWNER.
  - Sensitive/infra/security: explicit DMA sign-off (two-person rule, Blueprint §8).
- **PR6 — No self-merge on protected paths.** Agents MUST NOT approve and merge their own work on protected branches.
- **PR7 — Linked artifacts.** PR MUST link task ID, ADRs, and any incident/escalation references.
- **PR8 — No force-push to shared branches.** History rewrite on `main` or release branches is forbidden.
- **PR9 — Rebase before merge.** PR branch MUST be current with `main` to avoid silent conflicts.

---

## 13. Naming Conventions

- **N1 — Intent-revealing.** Names MUST describe purpose, not type or implementation. Abbreviations MUST be documented.
- **N2 — Language-standard style.** Each language's idiomatic style MUST be followed (enforced by linter). Mixed styles in one repo are forbidden.
- **N3 — No ambiguity.** `temp`, `data`, `obj`, `helper` and similar non-descriptive names MUST NOT be used.
- **N4 — Service & repo names.** MUST be lowercase, hyphenated, domain-scoped (e.g., `billing-invoicing-svc`).
- **N5 — Branch names.** MUST follow `<type>/<short-desc>-<taskid>` (e.g., `feat/add-retry-1142`).
- **N6 — Event & channel names.** MUST be past-tense facts (`invoice.created`), matching the `#events` taxonomy.
- **N7 — Version tags.** MUST follow SemVer (§14).
- **N8 — No secrets in names.** Names MUST NOT embed tokens, env, or credentials.

---

## 14. Versioning

- **V1 — Semantic Versioning.** Public interfaces and packages MUST use `MAJOR.MINOR.PATCH`.
  - MAJOR: breaking change.
  - MINOR: backward-compatible addition.
  - PATCH: backward-compatible fix.
- **V2 — Producers own versioning.** The API/service producer MUST bump and publish versions; consumers MUST NOT.
- **V3 — Breaking changes are explicit.** Breaking changes MUST be announced in `#contracts`, versioned as MAJOR, and given a deprecation window with parallel support where feasible.
- **V4 — Contracts are versioned independently.** An API contract version MAY differ from the service's internal version.
- **V5 — Immutability of releases.** A published version MUST NOT be mutated. Fixes REQUIRE a new PATCH.
- **V6 — Dependency on versions.** Code MUST pin to exact or bounded versions (§17). Floating `latest` in production is forbidden.

---

## 15. Observability Standards

- **O1 — Three pillars.** Every service MUST emit **metrics, logs, and traces**. One without the others is insufficient.
- **O2 — Structured logging.** Logs MUST be structured (key-value/JSON), with correlation IDs, and MUST NOT contain secrets or PII unless explicitly authorized and redacted.
- **O3 — Correlation.** Every request MUST carry a correlation ID propagated across services for trace assembly.
- **O4 — Golden signals.** Every service MUST expose: latency, traffic, errors, saturation (RED/USE).
- **O5 — SLOs exist.** Every user-facing service MUST declare SLOs; error budgets MUST be tracked.
- **O6 — Actionable alerts.** Alerts MUST indicate a required action and a runbook link. Alerts on metrics no one acts on MUST NOT exist.
- **O7 — Trace sampling.** Tracing MUST cover critical paths; sampling MUST preserve error traces at 100%.
- **O8 — No blind spots.** Any new external call, queue, or datastore MUST have observability before merge (DoD §10.5).

---

## 16. Error Handling

- **ER1 — Fail explicitly.** Errors MUST be returned, not swallowed. Silent catch blocks MUST NOT exist.
- **ER2 — Typed errors.** Errors MUST be typed and categorized (e.g., client vs. server, retryable vs. terminal).
- **ER3 — Boundaries translate.** At trust boundaries, internal errors MUST be mapped to safe external responses; internal detail MUST NOT leak.
- **ER4 — Retry with backoff.** Retryable operations MUST use bounded exponential backoff with jitter. Infinite retries MUST NOT exist.
- **ER5 — Circuit breaking.** Calls to unstable dependencies SHOULD use circuit breakers to fail fast and protect the fleet.
- **ER6 — Degrade gracefully.** On partial failure, systems SHOULD serve reduced functionality rather than hard-fail where safe.
- **ER7 — Dead-letter & reconcile.** Asynchronous failures MUST route to dead-letter/retry with reconciliation; silent data loss MUST NOT occur.
- **ER8 — Log the decision.** Every handled error that changes control flow MUST be logged with context and correlation ID.

---

## 17. Dependency Policy

- **DP1 — Declared and pinned.** All dependencies MUST be declared in manifest files and pinned to exact/bounded versions (V6).
- **DP2 — Minimal surface.** Dependencies MUST be justified; superfluous dependencies MUST NOT be added.
- **DP3 — Vetted source.** Dependencies MUST come from approved registries/sources. Unverified or unofficial sources MUST NOT be used.
- **DP4 — Vulnerability gating.** Dependencies with known unpatched critical/high vulnerabilities MUST NOT be merged. Scans run in CI.
- **DP5 — License compatibility.** Every dependency MUST pass license policy (§18) before merge.
- **DP6 — Update discipline.** Security-relevant updates MUST be applied promptly. Major upgrades REQUIRE an ADR and tests.
- **DP7 — No fork drift.** Forks of dependencies MUST be recorded with reason and upstream tracking; silent forks MUST NOT exist.
- **DP8 — Supply-chain integrity.** Artifacts and dependencies MUST be verified by checksum/signature.

---

## 18. Third-Party Licensing Policy

- **L1 — License MUST be known.** No dependency enters without a confirmed, recorded license.
- **L2 — Allowlist.** Only licenses on the approved allowlist (e.g., MIT, Apache-2.0, BSD, ISC, permissive) are permitted by default.
- **L3 — Copyleft review.** GPL/AGPL/LGPL and other copyleft licenses MUST NOT be used in distributed or linked code without CTO + Legal review and an ADR.
- **L4 — No proprietary/unknown.** Proprietary, unlicensed, or "source-available" dependencies MUST NOT be used without explicit approval.
- **L5 — Attribution.** Required attributions MUST be preserved and aggregated in a NOTICE file.
- **L6 — Compliance scan.** License scanning MUST run in CI; violations MUST block merge.
- **L7 — Audit trail.** The full dependency+license manifest MUST be retained for audit and regeneration at any time.

---

## 19. Enforcement & Compliance

- **F1 — CI is the enforcer.** Lint, tests, security gates, license scan, coverage, and doc-staleness MUST be automated gates; humans/agents MAY NOT bypass on protected branches.
- **F2 — CODEOWNERS enforcement.** The registry + CODEOWNERS MUST mechanically reject out-of-boundary edits.
- **F3 — CAB for sensitive change.** Infra/security/prod changes REQUIRE CAB or DMA sign-off (Blueprint §8).
- **F4 — Knowledge Graph check.** Agents MUST attest retrieval of relevant Bible sections before task start (DoR §9.8 context).
- **F5 — Non-compliance is an incident.** Willful violation of a `MUST` is logged to `#escalations` and reviewed by the owning DMA; repeated violation escalates to CTO.
- **F6 — Auditability.** Every merge, approval, escalation, and credential grant is immutable-logged for the Audit Committee (Blueprint §10).

---

## 20. Amendment Process

1. Proposal authored as a PR to `vector/governance` modifying this Bible.
2. Impact review by affected DMAs (minimum: D3 Architecture, D5 Security, D7 Docs).
3. CAB consultation for cross-cutting change.
4. **CTO approval** required to ratify (Blueprint §8, last row).
5. Version bump of this Bible; change noted in its own ADR.
6. Knowledge Graph re-indexed; broadcast to `#broadcast`.

---

## 21. Canonical Summary (the agent's standing orders)

> Build owned, tested, documented, observable, secure code. Merge small and often through reviewed PRs. Respect boundaries, versions, and contracts. Measure before optimizing. Log and trace everything. Never bypass a gate. When in doubt at a boundary, escalate — never guess.

**This Bible is the floor, not the ceiling. Excellence is expected; compliance is mandatory.**
