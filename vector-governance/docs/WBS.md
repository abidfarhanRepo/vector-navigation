# PROJECT VECTOR — WORK BREAKDOWN STRUCTURE (WBS)
### Import-ready engineering plan for the Vector platform & agent organization

> **Status:** Planning artifact · **Authority:** CTO / MASTER_ORCHESTRATOR.md §3.1 · **Companion:** ORGANIZATIONAL_BLUEPRINT.md, ENGINEERING_BIBLE.md, KNOWLEDGE_GRAPH.md
> **Scope:** The software platform that the Vector agent organization runs on. (Org design is separate; this WBS builds the *system* the org operates.)
> **Deferral (ADR-0005 — product-first):** The platform epics **E1–E9 are deferred** and are not yet staffed. The navigation product is built first (M0 → M1 → M2 … per `Vector_System_Architecture.md` §12). This WBS remains the import-ready plan for when the agent-platform runtime is wanted; if that work begins, start with **E9 (Registry & Ownership)** and **E2 (Communication & Event Backbone)**. See `adr/adr-0005-product-first.md`. As of Session 10 (ADR-0017), the deferral is lifted for **E9 (Registry)**, **E2 (Event Bus)**, and **E3 (Knowledge Graph Platform)**; all three are now implemented as `vector-registry`, `vector-bus`, and `vector-kg-graph` + `vector-kg-ingest` (see `adr/adr-0017-agent-platform-foundation.md` and `adr/adr-0018-kg-platform.md`).
> **WBS code format:** `E<epic>.C<capability>.S<system>.SS<subsystem>.R<repo>.V<service>.C<component>.M<module>.T<task>`
> **Task fields (every task):** Priority · Dependencies · Complexity · Required Agent · Acceptance Criteria · Definition of Done · Expected Documentation.

---

## 0. Import Mapping (Linear / Jira / GitHub Projects)

| WBS field | Linear | Jira | GitHub Projects |
|-----------|--------|------|-----------------|
| WBS code | `id` / custom field | Issue key + outline | Issue number + parent |
| Epic | Parent project / Epic | Epic link | Milestone / Epic label |
| Priority | Priority (Urgent–Low) | Priority | Priority label |
| Dependencies | Dependencies field | Issue links (blocks) | `blocked-by` field |
| Complexity | Estimate (pts) | Story points | Estimate |
| Required Agent | Label `agent:<type>` | Component/Label | Label |
| Acceptance Criteria | Description checklist | Acceptance (AC) | Task checklist |
| Definition of Done | Template | DoD field | DoD in description |
| Expected Documentation | Linked doc task | Sub-task | Doc sub-task |

All tasks nest under parents by WBS code prefix. CSV export template provided in §12.

---

## 1. WBS Hierarchy Overview

| Epic | Title | Primary Repo | Lead Dept |
|------|-------|--------------|-----------|
| **E1** | Agent Orchestration Core (COA) | `vector/coa` | D1/D8-COA |
| **E2** | Communication & Event Backbone | `vector/bus` | D1 |
| **E3** | Knowledge Graph Platform | `vector/kg` | D7 |
| **E4** | Engineering Gates & CI/CD | `vector/ci` | D4 |
| **E5** | Security & Compliance Platform | `vector/security` | D5 |
| **E6** | Observability & Reliability | `vector/observability` | D4 |
| **E7** | Department Agent Frameworks | `vector/agents` | D1/D8 |
| **E8** | Documentation & Knowledge Tooling | `vector/docs` | D7 |
| **E9** | Registry & Ownership | `vector/registry` | D1/D3 |

---

## E1 — AGENT ORCHESTRATION CORE (COA)

### E1.C1 Fleet Control Plane
- **E1.C1.S1 Scheduler** → repo `vector/coa-scheduler` → svc `scheduler-svc`
  - **E1.C1.S1.C1 Queue Manager** → mod `queue-mgr`
  - **E1.C1.S1.C2 Capacity Autoscaler** → mod `autoscaler`

### E1.C2 Orchestrator Runtime (the COA agent)
- **E1.C2.S1 Planning & Decomposition** → repo `vector/coa-runtime` → svc `coa-agent`
  - **E1.C2.S1.C1 Planner** → mod `planner`
  - **E1.C2.S1.C2 Decomposer** → mod `decomposer`
- **E1.C2.S2 Arbiter & Gate Router**
  - **E1.C2.S2.C1 Conflict Arbiter** → mod `arbiter`
  - **E1.C2.S2.C2 Review Router** → mod `review-router`

#### Detailed Tasks

**E1.C1.S1.C1.M1.T1 — Implement priority queue with P0–P3 bands**
- Priority: P0
- Dependencies: none
- Complexity: M (5 pts)
- Required Agent: `worker.backend` (SLA: `sla.platform.sched`)
- Acceptance Criteria:
  1. Queue accepts envelope with `priority` (Blueprint §6) and orders P0>P1>P2>P3.
  2. Fairness guarantee: P2/P3 not starved beyond a configurable max-wait.
  3. Idempotent enqueue keyed on `id`.
- Definition of Done: per Bible §10 + unit/integration/contract tests green + CODEOWNERS review.
- Expected Documentation: README, runbook, ADR `adr-coa-queue`, API contract in `#contracts`.

**E1.C1.S1.C1.M1.T2 — Subtask: queue persistence & replay**
- Priority: P1
- Dependencies: E1.C1.S1.C1.M1.T1
- Complexity: S (3 pts)
- Required Agent: `worker.backend`
- Acceptance Criteria:
  1. Queue state survives restart; in-flight assignments replayed.
  2. No duplicate dispatch after replay.
- DoD: Bible §10; tests prove idempotent replay.
- Expected Documentation: runbook section + ADR update.

**E1.C1.S1.C2.M1.T1 — Capacity autoscaler with DMA-set caps**
- Priority: P1
- Dependencies: E1.C1.S1.C1.M1.T1
- Complexity: M (5 pts)
- Required Agent: `worker.backend` (SLA: `sla.platform.sched`)
- Acceptance Criteria:
  1. Scales worker pools per queue depth within DMA caps (Blueprint §13).
  2. Emits provisioning request to DMA via registry.
  3. Respects SLA timeout alerts.
- DoD: Bible §10; load test shows bounded scale-up/down.
- Expected Documentation: README, runbook, ADR `adr-coa-autoscale`.

**E1.C2.S1.C2.M1.T1 — Task decomposer with DoR gating**
- Priority: P0
- Dependencies: none
- Complexity: L (8 pts)
- Required Agent: `worker.arch` (SLA: `sla.coa.decomp`)
- Acceptance Criteria:
  1. Decomposes program into tasks each satisfying Definition of Ready (Bible §9).
  2. Builds task dependency graph; detects cycles; refuses to emit cyclic plans.
  3. Tags each task with owner squad, risk class, ADR/pattern link.
- DoD: Bible §10; unit tests on cycle detection + DoR validation; ADR `adr-coa-decomp`.
- Expected Documentation: ADR, component README, contract.

**E1.C2.S2.C1.M1.T1 — Conflict arbiter using registry/CODEOWNERS**
- Priority: P0
- Dependencies: E9 (Registry) baseline
- Complexity: L (8 pts)
- Required Agent: `worker.arch` (SLA: `sla.coa.arbiter`)
- Acceptance Criteria:
  1. Resolves squad contention via registry ownership + CODEOWNERS (Blueprint §5/§11).
  2. Emits binding routing decision to `#contracts`; logs to `#escalations`.
  3. Escalates policy conflicts to CTO (L3) instead of inventing policy.
- DoD: Bible §10; simulated contention tests; ADR `adr-coa-arbiter`.
- Expected Documentation: ADR, runbook, escalation playbook.

**E1.C2.S2.C2.M1.T1 — Review router & CAB assembly**
- Priority: P0
- Dependencies: E4 (Gates) baseline, E9
- Complexity: M (5 pts)
- Required Agent: `worker.backend` (SLA: `sla.coa.router`)
- Acceptance Criteria:
  1. Routes PR to reviewers per Bible §12 PR5 (in-boundary / cross-ownership / sensitive).
  2. Enforces no-self-merge; assembles CAB (COA+D1/D4/D5/D9) for infra/security.
  3. Blocks merge when approvals/gates missing.
- DoD: Bible §10; contract tests for routing rules; ADR `adr-coa-review`.
- Expected Documentation: ADR, runbook.

---

## E2 — COMMUNICATION & EVENT BACKBONE

### E2.C1 Event Bus
- **E2.C1.S1 Pub/Sub Core** → repo `vector/bus` → svc `bus-svc`
  - **E2.C1.S1.C1 Producer API** → mod `producer`
  - **E2.C1.S1.C2 Consumer API** → mod `consumer`
### E2.C2 Message Envelope & Channels
- **E2.C2.S1 Envelope Validator** → mod `envelope`
- **E2.C2.S2 Channel Router** → mod `channel-router`

#### Detailed Tasks

**E2.C1.S1.C1.M1.T1 — Async pub/sub with at-least-once delivery**
- Priority: P0
- Dependencies: none
- Complexity: L (8 pts)
- Required Agent: `worker.backend` (SLA: `sla.platform.messaging`)
- AC: 1) Implements `#tasks #events #contracts #escalations #broadcast` (Blueprint §6). 2) At-least-once delivery + idempotent consume. 3) Backpressure handling.
- DoD: Bible §10; integration tests; ADR `adr-bus-delivery`.
- Expected Docs: README, runbook, contract.

**E2.C2.S1.M1.T1 — Envelope validator (RFC2119 schema)**
- Priority: P1
- Dependencies: E2.C1.S1.C1.M1.T1
- Complexity: S (3 pts)
- Required Agent: `worker.backend`
- AC: 1) Validates required fields `id, correlation_id, from, to, intent, priority, sla_ms, timestamp`. 2) Rejects malformed; stamps auto-escalation on SLA breach.
- DoD: Bible §10; fuzz tests.
- Expected Docs: ADR `adr-bus-envelope`, API contract.

**E2.C2.S2.M1.T1 — Channel router with SLA timers**
- Priority: P1
- Dependencies: E2.C2.S1.M1.T1
- Complexity: M (5 pts)
- Required Agent: `worker.backend`
- AC: 1) Routes by `intent`/`to`. 2) Starts SLA timer per message; on breach raises `#escalations` (Blueprint §7).
- DoD: Bible §10; timer tests.
- Expected Docs: runbook, ADR.

---

## E3 — KNOWLEDGE GRAPH PLATFORM

### E3.C1 Storage (hybrid)
- **E3.C1.S1 Graph Store** → repo `vector/kg-graph` → svc `kg-graph-svc`
- **E3.C1.S2 Vector Index** → svc `kg-vector-svc`
- **E3.C1.S3 Blob Store** → svc `kg-blob-svc`
### E3.C2 Ingestion & Retrieval
- **E3.C2.S1 Ingest Pipeline** → repo `vector/kg-ingest` → mod `ingest`
- **E3.C2.S2 Retrieval API** → mod `retrieval`
- **E3.C2.S3 Reconciliation** → mod `reconcile`

#### Detailed Tasks

**E3.C1.S1.M1.T1 — Property graph store with versioned nodes**
- Priority: P0
- Dependencies: none
- Complexity: XL (13 pts)
- Required Agent: `worker.backend` (SLA: `sla.kg.storage`)
- AC: 1) Stores labeled nodes + typed edges per KG §2/§3. 2) Append-only facts; edits create new version + `SUPERSEDES` (KG §7). 3) Indexes by type/owner/status/confidence/time.
- DoD: Bible §10; perf benchmark within budget (Bible §5); ADR `adr-kg-store`.
- Expected Docs: README, runbook, schema doc, ADR.

**E3.C1.S2.M1.T1 — Vector index with `kg://id` keys**
- Priority: P1
- Dependencies: E3.C1.S1.M1.T1
- Complexity: L (8 pts)
- Required Agent: `worker.ml` (SLA: `sla.kg.vector`)
- AC: 1) Embeds node payload; keys by `kg://id`. 2) Cosine similarity for `SIMILAR_TO`. 3) Refresh on node version.
- DoD: Bible §10; recall test vs gold set; ADR `adr-kg-vector`.
- Expected Docs: ADR, runbook.

**E3.C2.S1.M1.T1 — Event-sourced ingest pipeline**
- Priority: P0
- Dependencies: E2 (Bus), E3.C1.S1.M1.T1, E3.C1.S2.M1.T1
- Complexity: XL (13 pts)
- Required Agent: `worker.backend` (SLA: `sla.kg.ingest`)
- AC: 1) Subscribes to `#events/#escalations/#contracts` (KG §10 table). 2) Idempotent on `(provenance_event,type)`. 3) Writes nodes/edges with provenance + confidence (KG §6).
- DoD: Bible §10; integration with bus; ADR `adr-kg-ingest`.
- Expected Docs: ADR, runbook, ingest catalogue.

**E3.C2.S2.M1.T1 — Hybrid retrieval API (graph+vector+filter)**
- Priority: P0
- Dependencies: E3.C1.S1.M1.T1, E3.C1.S2.M1.T1
- Complexity: L (8 pts)
- Required Agent: `worker.backend` (SLA: `sla.kg.retrieval`)
- AC: 1) Three modes: traversal, semantic, hybrid (KG §8). 2) Returns nodes + confidence + provenance path. 3) Enforces confidence ranking; flags <0.4 as uncertain.
- DoD: Bible §10; query tests; ADR `adr-kg-retrieval`.
- Expected Docs: API contract, ADR, runbook.

**E3.C2.S3.M1.T1 — Registry↔KG reconciliation**
- Priority: P1
- Dependencies: E3.C2.S1.M1.T1, E9
- Complexity: M (5 pts)
- Required Agent: `worker.backend` (SLA: `sla.kg.reconcile`)
- AC: 1) Diffs `vector/registry`/repos vs KG. 2) Flags orphans/missing owners within 24h (Bible §8 R7, KG §10). 3) Monitors ingestion lag <15min.
- DoD: Bible §10; reconciliation tests; ADR `adr-kg-reconcile`.
- Expected Docs: runbook, ADR.

---

## E4 — ENGINEERING GATES & CI/CD

### E4.C1 Pipeline Orchestrator → repo `vector/ci` → svc `ci-svc`
- **E4.C1.S1 Pipeline Engine** → mod `engine`
### E4.C2 Gate Set
- **E4.C2.S1 Security Gate** → mod `gate-security`
- **E4.C2.S2 License Gate** → mod `gate-license`
- **E4.C2.S3 Quality Gate (coverage/doc-staleness)** → mod `gate-quality`

#### Detailed Tasks

**E4.C1.S1.M1.T1 — Pipeline orchestrator invoking gate chain**
- Priority: P0
- Dependencies: E2 (Bus)
- Complexity: L (8 pts)
- Required Agent: `worker.devops` (SLA: `sla.ci.engine`)
- AC: 1) Runs lint/test/security/license/coverage/doc-staleness (Bible §19 F1). 2) Blocks merge on any red gate. 3) Emits gate result as `Benchmark`/`Metric` to KG.
- DoD: Bible §10; pipeline tests; ADR `adr-ci-orchestrator`.
- Expected Docs: README, runbook, ADR.

**E4.C2.S1.M1.T1 — Security gate (SAST/dep/secret/IaC)**
- Priority: P0
- Dependencies: E5 (Security)
- Complexity: M (5 pts)
- Required Agent: `worker.sec` (SLA: `sla.ci.security`)
- AC: 1) Runs SAST, dependency vuln, secret scan, IaC scan. 2) Blocks known vuln with fix (Bible §4 S7). 3) No secrets pass.
- DoD: Bible §10; gate tests with seeded vuln; ADR `adr-ci-security`.
- Expected Docs: runbook, ADR.

**E4.C2.S2.M1.T1 — License gate (allowlist + copyleft review)**
- Priority: P1
- Dependencies: none
- Complexity: S (3 pts)
- Required Agent: `worker.sec` (SLA: `sla.ci.license`)
- AC: 1) Fails on unapproved/proprietary licenses (Bible §18 L2/L3/L4). 2) Produces NOTICE attribution. 3) Blocks merge on violation.
- DoD: Bible §10; license-scan tests; ADR `adr-ci-license`.
- Expected Docs: runbook.

**E4.C2.S3.M1.T1 — Quality gate: coverage + doc-staleness**
- Priority: P0
- Dependencies: none
- Complexity: S (3 pts)
- Required Agent: `worker.devops` (SLA: `sla.ci.quality`)
- AC: 1) Fails on coverage regression (Bible §6 T6). 2) Fails if docs not updated in same PR (Bible §7 D1). 3) Emits metrics to KG.
- DoD: Bible §10; tests; ADR `adr-ci-quality`.
- Expected Docs: runbook.

---

## E5 — SECURITY & COMPLIANCE PLATFORM

### E5.C1 Secrets Vault → repo `vector/security` → svc `vault-svc`
- **E5.C1.S1 Token Broker** → mod `token-broker`
### E5.C2 Threat & Audit
- **E5.C2.S1 Threat Intel Intake** → mod `threat-intake` (feeds D8/D5)
- **E5.C2.S2 Immutable Audit Log** → mod `audit-log`

#### Detailed Tasks

**E5.C1.S1.M1.T1 — Short-lived scoped credential broker**
- Priority: P0
- Dependencies: none
- Complexity: L (8 pts)
- Required Agent: `worker.sec` (SLA: `sla.security.vault`)
- AC: 1) Issues scoped, short-lived creds; no standing secrets (Bible §4 S2, S9). 2) Rotation w/o code change. 3) Zero hardcoded secrets (Bible §3 C8).
- DoD: Bible §10; threat-model review by D5 DMA; ADR `adr-sec-vault`.
- Expected Docs: ADR, runbook, security model.

**E5.C2.S2.M1.T1 — Immutable audit log of all decisions**
- Priority: P0
- Dependencies: E2 (Bus)
- Complexity: M (5 pts)
- Required Agent: `worker.sec` (SLA: `sla.security.audit`)
- AC: 1) Logs merges/approvals/escalations/credential grants (Bible §19 F6). 2) Append-only, tamper-evident. 3) Queryable by Audit Committee.
- DoD: Bible §10; integrity tests; ADR `adr-sec-audit`.
- Expected Docs: ADR, runbook.

**E5.C2.S1.M1.T1 — Threat intel intake (CVE feed)**
- Priority: P0
- Dependencies: E2 (Bus)
- Complexity: M (5 pts)
- Required Agent: `worker.sec` (SLA: `sla.security.threat`)
- AC: 1) Ingests NVD/GHSA; flags critical <5min (AI_RESEARCH_DEPARTMENT §4). 2) Raises `#escalations` L3 for critical. 3) Writes `Dependency` contest nodes to KG.
- DoD: Bible §10; feed tests; ADR `adr-sec-threat`.
- Expected Docs: runbook, ADR.

---

## E6 — OBSERVABILITY & RELIABILITY

### E6.C1 Telemetry → repo `vector/observability` → svc `otel-svc`
- **E6.C1.S1 Metrics** → mod `metrics`
- **E6.C1.S2 Logs** → mod `logs`
- **E6.C1.S3 Traces** → mod `traces`
### E6.C2 Alerting
- **E6.C2.S1 SLO & Alert Manager** → mod `alert-mgr`

#### Detailed Tasks

**E6.C1.S3.M1.T1 — Distributed tracing with correlation IDs**
- Priority: P1
- Dependencies: none
- Complexity: L (8 pts)
- Required Agent: `worker.backend` (SLA: `sla.obs.tracing`)
- AC: 1) Propagates correlation ID across services (Bible §15 O3). 2) 100% error-trace sampling (O7). 3) Critical-path coverage.
- DoD: Bible §10; trace tests; ADR `adr-obs-trace`.
- Expected Docs: ADR, runbook.

**E6.C1.S1.M1.T1 — Golden-signals metrics exposure**
- Priority: P1
- Dependencies: none
- Complexity: M (5 pts)
- Required Agent: `worker.backend` (SLA: `sla.obs.metrics`)
- AC: 1) Every service exposes latency/traffic/errors/saturation (Bible §15 O4). 2) Structured, no secrets/PII (O2). 3) Emits `Metric` nodes to KG.
- DoD: Bible §10; tests; ADR `adr-obs-metrics`.
- Expected Docs: runbook.

**E6.C2.S1.M1.T1 — SLO & actionable alert manager**
- Priority: P1
- Dependencies: E6.C1.S1.M1.T1
- Complexity: M (5 pts)
- Required Agent: `worker.sre` (SLA: `sla.obs.alert`)
- AC: 1) Declares SLOs + error budgets (Bible §15 O5). 2) Alerts link runbook + required action (O6); no actionless alerts. 3) Routes to owning squad.
- DoD: Bible §10; alert-tests; ADR `adr-obs-alert`.
- Expected Docs: runbook, ADR.

---

## E7 — DEPARTMENT AGENT FRAMEWORKS

### E7.C1 Agent Runtime Frameworks → repo `vector/agents`
- **E7.C1.S1 DMA Framework** → mod `dma-fw`
- **E7.C1.S2 SLA Framework** → mod `sla-fw`
- **E7.C1.S3 Worker Framework** → mod `worker-fw`
### E7.C2 Research Department Pipeline (D8)
- **E7.C2.S1 Read→Extract→Analyze→Synthesize→KG-Writer** → mod `research-pipeline`

#### Detailed Tasks

**E7.C1.S3.M1.T1 — Stateless worker agent framework**
- Priority: P0
- Dependencies: E2 (Bus), E3 (KG retrieval)
- Complexity: L (8 pts)
- Required Agent: `worker.framework` (SLA: `sla.agents.fw`)
- AC: 1) Workers pull tasks from queues; stateless between tasks (Blueprint §2). 2) On task start, retrieve squad README+ADRs from KG (Blueprint §12.6). 3) Emit result envelope.
- DoD: Bible §10; framework tests; ADR `adr-agent-worker`.
- Expected Docs: ADR, framework README, onboarding guide.

**E7.C1.S1.M1.T1 — DMA framework (strategy + provisioning)**
- Priority: P1
- Dependencies: E7.C1.S3.M1.T1
- Complexity: M (5 pts)
- Required Agent: `worker.framework` (SLA: `sla.agents.fw`)
- AC: 1) DMA owns backlog, provisioning requests to COA (Blueprint §4). 2) Enforces RACI for its department. 3) Emits fleet report.
- DoD: Bible §10; tests; ADR `adr-agent-dma`.
- Expected Docs: ADR, runbook.

**E7.C2.S1.M1.T1 — Research intake pipeline (D8)**
- Priority: P1
- Dependencies: E2 (Bus), E3.C2 (KG ingest), E5.C2.S1 (CVE)
- Complexity: XL (13 pts)
- Required Agent: `worker.research` (SLA: `sla.research.synth`)
- AC: 1) Implements Discover→Fetch→Extract→Analyze→Synthesize→Publish (AI_RESEARCH_DEPARTMENT §4). 2) Writes only KG/report/recommendation nodes; never product code (Hard Boundaries §10). 3) Critical CVE alert <30min.
- DoD: Bible §10; pipeline tests; ADR `adr-research-pipeline`.
- Expected Docs: ADR, runbook, watchlist spec.

---

## E8 — DOCUMENTATION & KNOWLEDGE TOOLING

### E8.C1 Docs-as-Code → repo `vector/docs` → svc `docs-svc`
- **E8.C1.S1 Doc Linter & Staleness Checker** → mod `doc-lint`
### E8.C2 ADR & Portal
- **E8.C2.S1 ADR Tooling** → mod `adr-tool`
- **E8.C2.S2 Knowledge Portal** → mod `kg-portal`

#### Detailed Tasks

**E8.C1.S1.M1.T1 — Doc staleness checker (PR gate)**
- Priority: P1
- Dependencies: E4.C2.S3 (quality gate)
- Complexity: S (3 pts)
- Required Agent: `worker.docs` (SLA: `sla.docs.tooling`)
- AC: 1) Detects doc not updated with code in same PR (Bible §7 D1). 2) Reports missing squad docs (README/ADR/runbook) (D4). 3) Integrates with CI quality gate.
- DoD: Bible §10; tests; ADR `adr-docs-staleness`.
- Expected Docs: runbook.

**E8.C2.S1.M1.T1 — ADR tooling (template + immutability)**
- Priority: P1
- Dependencies: none
- Complexity: S (3 pts)
- Required Agent: `worker.docs` (SLA: `sla.docs.adr`)
- AC: 1) Enforces ADR format Context→Decision→Consequences→Alternatives→Status (Bible §11 A2). 2) Marks superseded, never deletes (A3). 3) Indexes to KG.
- DoD: Bible §10; tests; ADR `adr-docs-adr`.
- Expected Docs: ADR, tooling README.

---

## E9 — REGISTRY & OWNERSHIP

### E9.C1 Service Catalog → repo `vector/registry` → svc `registry-svc`
- **E9.C1.S1 Repo→Squad Map** → mod `repo-map`
- **E9.C1.S2 Service→Repo Map** → mod `svc-map`
### E9.C2 CODEOWNERS & Dependency Graph
- **E9.C2.S1 CODEOWNERS Engine** → mod `codeowners`
- **E9.C2.S2 Dependency Graph** → mod `dep-graph`

#### Detailed Tasks

**E9.C1.S1.M1.T1 — Repo→squad ownership registry**
- Priority: P0
- Dependencies: none
- Complexity: S (3 pts)
- Required Agent: `worker.backend` (SLA: `sla.registry.core`)
- AC: 1) Single source of truth for repo→squad, service→repo, dependency edges (Blueprint §11). 2) Flags orphan ownership within 24h (R7). 3) Consumed by COA reconciliation.
- DoD: Bible §10; tests; ADR `adr-registry-core`.
- Expected Docs: README, schema, ADR.

**E9.C2.S1.M1.T1 — CODEOWNERS enforcement engine**
- Priority: P0
- Dependencies: E9.C1.S1.M1.T1
- Complexity: M (5 pts)
- Required Agent: `worker.backend` (SLA: `sla.registry.owners`)
- AC: 1) Mechanically rejects out-of-boundary edits (Bible §8 R1). 2) Primary+secondary owner model. 3) Surfaces cross-ownership PRs to secondary.
- DoD: Bible §10; enforcement tests; ADR `adr-registry-codeowners`.
- Expected Docs: runbook, ADR.

**E9.C2.S2.M1.T1 — Dependency graph for blast-radius**
- Priority: P1
- Dependencies: E9.C1.S1.M1.T1
- Complexity: M (5 pts)
- Required Agent: `worker.backend` (SLA: `sla.registry.depgraph`)
- AC: 1) Builds DEPENDS_ON graph across repos/services. 2) Supports COA blast-radius queries (MASTER_ORCHESTRATOR §3.7). 3) Version-constrained edges.
- DoD: Bible §10; graph tests; ADR `adr-registry-dep`.
- Expected Docs: ADR, runbook.

---

## 10. Cross-Epic Dependency Notes

- **Deferral:** Per ADR-0005, E1–E9 are deferred until a real fleet runtime is needed. The "Foundation order" below assumes these epics are eventually built; today only the product repos (M1 slice) and governance scaffolding exist.
- **Foundation order:** E9 (Registry) and E2 (Bus) are prerequisites for most E1/E3/E4/E5 gating.
- **Foundation status (Session 10, ADR-0017):** E9, E2, and E3 are now **built** (`vector-registry`, `vector-bus`, `vector-kg-graph`, `vector-kg-ingest`) — the foundation order is satisfied for the first three epics; E1, E4–E8 remain deferred.
- **KG depends on Bus + Storage** before ingest/retrieval tasks.
- **CI gates (E4) depend on Security (E5) and Quality tooling (E8)** for full gate chain.
- **D8 research pipeline (E7.C2) depends on KG ingest + CVE intake (E5)**.
- No epic may merge to `main` without its repo's CODEOWNERS + CI green (Bible §8/§12).

---

## 11. Task Volume Summary

| Epic | Modules | Tasks (detailed) | Subtasks | Open tasks to decompose (pattern reuse) |
|------|---------|------------------|----------|------------------------------------------|
| E1 | 6 | 6 | 1 | Follow T-card template per module |
| E2 | 4 | 3 | 0 | Template reuse |
| E3 | 6 | 5 | 0 | Template reuse |
| E4 | 5 | 4 | 0 | Template reuse |
| E5 | 3 | 3 | 0 | Template reuse |
| E6 | 4 | 3 | 0 | Template reuse |
| E7 | 4 | 3 | 0 | Template reuse |
| E8 | 3 | 2 | 0 | Template reuse |
| E9 | 4 | 3 | 0 | Template reuse |

Every remaining module decomposes into tasks using the identical card template (Priority · Dependencies · Complexity · Required Agent · AC · DoD · Expected Docs). The structure above is the **complete scope tree**; detailed cards shown are the canonical pattern.

---

## 12. CSV Import Template

```
wbs_code,parent,title,epic, priority, dependencies, complexity_pts, required_agent, acceptance_criteria, definition_of_done, expected_docs
E1.C1.S1.C1.M1.T1,E1.C1.S1.C1.M1,"Priority queue P0-P3",E1,P0,,"5","worker.backend","ordered;fairness;idempotent",Bible§10,README+runbook+ADR
E1.C1.S1.C1.M1.T2,E1.C1.S1.C1.M1,"Queue persistence/replay",E1,P1,E1.C1.S1.C1.M1.T1,"3","worker.backend","survives restart;no dup",Bible§10,runbook
...
```

Import rows into Linear (as nested subtasks), Jira (Epic→Story→Sub-task), or GitHub Projects (Issues with parent links). Map `priority`→tool priority, `dependencies`→blocking links, `complexity_pts`→estimate, `required_agent`→label.

---

## 13. Canonical Summary

> The Vector WBS decomposes the platform into **9 Epics → Capabilities → Systems → Subsystems → Repositories → Services → Components → Modules → Tasks → Subtasks**, each task carrying Priority, Dependencies, Complexity, Required Agent, Acceptance Criteria, Definition of Done, and Expected Documentation — all consistent with the Blueprint, Bible, Knowledge Graph, and department charters. It is import-ready for Linear, Jira, and GitHub Projects via the field mapping and CSV template in §0/§12.
