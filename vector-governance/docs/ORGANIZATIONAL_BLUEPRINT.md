# PROJECT VECTOR — ORGANIZATIONAL BLUEPRINT
### AI Engineering Organization for Hundreds of Concurrent Agents

---

## 0. Founding Principles (The Vector Constitution)

1. **Agents are workers, not consultants.** Every agent produces artifacts, not advice.
2. **Single source of truth.** One canonical repo graph, one registry, one decision log.
3. **Ownership over coordination.** A thing has exactly one owner; coordination is the exception.
4. **Asynchronous by default.** No agent blocks waiting on another beyond a defined SLA.
5. **Auditability.** Every decision, merge, and escalation is logged and traceable.
6. **Bounded autonomy.** Agents act within their boundary without permission; beyond it, they escalate.
7. **Failure is a first-class event.** Incidents and rejections are captured, not buried.

---

## 1. Department Topology

Nine departments, grouped into three branches. Each department is led by one **Department Manager Agent (DMA)**.

| # | Department | Branch | Core Mandate |
|---|-----------|--------|--------------|
| D1 | **Platform & Infrastructure** | Foundation | Runtime, CI/CD, compute, tooling, agent execution substrate |
| D2 | **Product Engineering** | Delivery | Feature squads building product surfaces & services |
| D3 | **Architecture & Standards** | Foundation | System design, API contracts, tech-stack governance |
| D4 | **Quality & Reliability (Q&R)** | Assurance | Testing, SRE, observability, release gating |
| D5 | **Security & Compliance** | Assurance | Threat modeling, secrets, vuln management, audit |
| D6 | **Data & ML Engineering** | Delivery | Pipelines, models, feature stores, evals |
| D7 | **Documentation & Knowledge** | Enablement | Docs-as-code, runbooks, onboarding, knowledge graph |
| D8 | **Research & Innovation** | Enablement | Prototypes, spikes, frontier exploration |
| D9 | **Operations & Release** | Assurance | Deployments, changelog, incident command |

---

## 2. Agent Hierarchy

```
┌─────────────────────────────────────────────────────────┐
│ TIER 0 — HUMAN GOVERNANCE                               │
│   Board of Directors · CTO (you) · Audit Committee      │
└───────────────────────────┬─────────────────────────────┘
                            │ charters / policy
┌───────────────────────────▼─────────────────────────────┐
│ TIER 1 — ORCHESTRATION (COA)                            │
│   Chief Orchestration Agent                             │
│   · Scheduler · Registry · Health · Capacity            │
└───────────────────────────┬─────────────────────────────┘
                            │ delegates to
        ┌───────────────────┼───────────────────┐
        ▼                   ▼                   ▼
┌───────────────┐  ┌───────────────┐  ┌───────────────────┐
│ TIER 2 — DMAs │  │ TIER 2 — DMAs │  │  ... 9 DMAs total │
│ (9 Managers)  │  │               │  │                   │
└───────┬───────┘  └───────┬───────┘  └─────────┬─────────┘
        │ leads             │ leads              │ leads
┌───────▼───────────────────▼────────────────────▼─────────┐
│ TIER 3 — SQUAD LEAD AGENTS (SLAs)                        │
│   One per squad/domain (e.g., Auth-Lead, Billing-Lead)   │
└───────────────────────────┬──────────────────────────────┘
                            │ assigns tasks to
┌───────────────────────────▼──────────────────────────────┐
│ TIER 4 — WORKER AGENTS (Specialists)                     │
│   Backend · Frontend · Test · Sec · Data · Docs · ...    │
└───────────────────────────────────────────────────────────┘
```

**Agent class definitions:**

- **COA** — 1 instance. Runtime superintendent. Owns scheduling, registry, fleet health, capacity, and cross-department routing.
- **DMA** — 9 instances (one/department). Owns strategy, headcount (agent provisioning), backlog, and inter-department contracts.
- **SLA (Squad Lead Agent)** — N instances. Owns a bounded squad, decomposes DMA objectives into tasks, assigns to workers, validates output.
- **Worker Agent** — hundreds. Single-specialty executors. Stateless between tasks; pull work from queues.

**Scaling rule:** Workers are ephemeral and horizontally scaled per queue depth. SLAs are long-lived per squad. DMAs are fixed. Only worker count fluctuates.

---

## 3. Reporting Structure

| From → To | Cadence | Channel | Content |
|-----------|---------|---------|---------|
| Worker → SLA | per task | Task result event | Diff, tests, status, confidence |
| SLA → DMA | per sprint (or event) | Sprint report | Burndown, blockers, risks |
| DMA → COA | per cycle | Fleet report | Capacity, throughput, escalations |
| COA → CTO | per cycle + on-call | Exec summary | Health, exceptions, resource asks |
| DMA ↔ DMA | on contract change | Contract event | API/interface changes, dependencies |

**No skip-level reporting for routine work.** Escalation (§7) is the only skip-level path, and it is explicit and logged.

---

## 4. Responsibilities (RACI by layer)

| Function | Worker | SLA | DMA | COA | CTO |
|----------|:------:|:---:|:---:|:---:|:---:|
| Write code / artifact | R | A | I | – | – |
| Decompose objective | – | R | A | I | – |
| Own squad backlog | – | R | A | I | – |
| Set department strategy | – | – | R/A | C | I |
| Provision agents | – | – | R | A | I |
| Fleet scheduling | – | – | – | R/A | I |
| Policy & Constitution | – | – | C | C | A/R |
| Incident command | I | R | A | C | I |

R=Responsible, A=Accountable, C=Consulted, I=Informed.

---

## 5. Ownership Boundaries

Ownership is **exclusive and named**. Conflict is resolved by COA using the registry.

- **Repository ownership** — Every repo has a `CODEOWNERS` file mapping paths → squad. Exactly one SLA is the *primary owner*; others are *secondary* (review-only).
- **Service ownership** — Each microservice has one owning squad (on-call, deploy, rollback authority).
- **Data ownership** — D6 owns pipelines; the *producing* squad owns schema; *consumers* own derived views.
- **Interface ownership** — The *producer* of an API contract owns it and must version it. Consumers may not modify it.
- **Boundary rule** — A worker may edit only files within its squad's owned paths. Cross-boundary edits require a **cross-ownership PR** with secondary-owner approval (§9).

---

## 6. Communication Protocol

**Transport:** asynchronous message bus (event log) + request/response over correlation IDs. No synchronous blocking calls between agents.

**Standard Envelope (every message):**
```json
{
  "id": "uuid",
  "correlation_id": "uuid",
  "from": "agent://sla.auth-01",
  "to": "agent://worker.sec-12",
  "intent": "TASK|ASSIGN|REVIEW|ESCALATE|NOTIFY|CONTRACT",
  "priority": "P0|P1|P2|P3",
  "payload": { ... },
  "sla_ms": 3600000,
  "timestamp": "iso8601"
}
```

**Channel taxonomy:**
- `#tasks` — work assignment queue (priority-ordered)
- `#events` — immutable fact stream (merges, deploys, incidents)
- `#contracts` — interface/dependency declarations
- `#escalations` — exceptions routed up
- `#broadcast` — org-wide notices from COA/CTO

**Etiquette:** messages are self-contained (no shared hidden state), idempotent where possible, and every request carries an SLA. Unanswered past SLA auto-escalates.

---

## 7. Escalation Process

Five levels. Each has a defined owner and timeout; silence triggers auto-promotion.

| Level | Trigger | Owner | Timeout | Outcome if unhandled |
|-------|---------|-------|---------|----------------------|
| L0 | Task blocked / needs info | SLA | 30 min | → L1 |
| L1 | Cross-squad dependency conflict | Source DMA | 2 hr | → L2 |
| L2 | Cross-department contract dispute | COA | 4 hr | → L3 |
| L3 | Policy violation / security event | CTO + Audit | 1 day | → L4 |
| L4 | Constitutional conflict / strategic | Board | 3 days | Binding ruling |

**Escalation record** is written to `#escalations` with full context snapshot so the receiving tier needs zero prior state.

---

## 8. Approval Process

| Artifact | Required approvals | Gate |
|----------|-------------------|------|
| Code merge (in-boundary) | 1 reviewer (peer worker or SLA) | CI green + 1 approval |
| Code merge (cross-ownership) | Primary + secondary CODEOWNER | CAB light review |
| New API contract | Producer SLA + Architecture DMA | Contract registry entry |
| Infra change | Platform DMA + Ops DMA | Change Advisory Board (CAB) |
| Security-sensitive change | Security DMA explicit sign-off | Security gate in CI |
| Production deploy | Ops DMA (release manager) | Q&R green + rollback plan |
| New department / policy | CTO | Constitution amendment |

**CAB** = standing virtual board: COA + D1/D4/D5/D9 DMAs. Meets on deploy/infra events, async-vote otherwise.

**Two-person rule:** No agent merges its own work unattended on protected branches; security and infra require human or secondary-agent sign-off.

---

## 9. Documentation Ownership

- **D7 (Documentation & Knowledge)** is the *steward* of doc tooling, taxonomy, and the knowledge graph — not the author of all content.
- **Docs-as-code:** documentation lives next to code in the same repo; the owning squad writes and maintains it. D7 reviews for standards compliance only.
- **Mandatory docs per squad:** README, ADR (architecture decision records), runbook, onboarding guide.
- **Constitution & policy docs** owned by CTO, versioned in a protected `vector/governance` repo.
- **Staleness SLA:** docs must be updated in the same PR as the code they describe; CI fails otherwise.

---

## 10. Security Ownership

- **D5 (Security & Compliance)** owns *policy, scanning, secrets management, and audit* — not every line of secure code.
- **Shared responsibility:** every squad implements security within its boundary; D5 verifies via automated gates.
- **Security gates in CI:** SAST, dependency vuln, secret scanning, IaC scanning — non-negotiable, block merges.
- **Zero-standing-secrets:** agents receive scoped, short-lived credentials via the Platform vault; never hardcoded.
- **Incident authority:** during a security incident, D5 DMA holds command; all other agents defer.
- **Audit trail:** all merges, approvals, escalations, and credential grants are immutable-logged for the Audit Committee.

---

## 11. Repository Ownership

- **Monorepo-or-polyrepo:** polyrepo with a central **registry** (in `vector/registry`) describing every repo, owner, service, and dependency edge.
- **CODEOWNERS** is the legal boundary of edit rights (§5).
- **Branch strategy:** `main` protected; short-lived feature branches; squash-merge; every merge tagged.
- **Registry is source of truth** for: repo → squad mapping, service → repo mapping, dependency graph, on-call roster. COA reconciles fleet state against it continuously.
- **Orphan detection:** any repo/service with no registered owner is auto-flagged to COA within 24h.

---

## 12. Knowledge Sharing Process

1. **Knowledge Graph (D7):** every ADR, runbook, post-mortem, and contract is indexed into a queryable graph. Agents retrieve before acting.
2. **Post-mortem protocol:** every L2+ incident produces a blameless post-mortem within 48h, stored and linked in the graph.
3. **Pattern library:** D3 maintains reusable architectural patterns; workers cite a pattern ID when applying one.
4. **Cross-pollination:** weekly async "learnings" digest from COA summarizing cross-squad insights.
5. **Agent memory boundary:** workers are stateless; *learning* lives in the knowledge graph, not in agent memory — preventing drift and enabling any worker to pick up any task.
6. **Ramp-up SLA:** a newly provisioned worker reads its squad's README + relevant ADRs from the graph before accepting tasks.

---

## 13. Lifecycle & Provisioning

- **Provisioning:** DMA requests N workers of type X from COA via registry; COA spins ephemeral agents bound to a squad queue.
- **Decommission:** idle workers beyond SLA threshold are terminated; state (never in worker) persists in repo + graph.
- **Capacity model:** COA autoscales workers on queue depth with per-type caps set by DMA.

---

## 14. Operating Cadence

| Cycle | Participants | Output |
|-------|-------------|--------|
| Task (continuous) | Worker↔SLA | Merged artifacts |
| Sprint (1 wk) | SLA→DMA | Sprint report, burndown |
| Department cycle (2 wk) | DMA→COA | Fleet report |
| Org review (monthly) | COA→CTO + DMAs | Strategy adjust, reallocation |
| Audit (quarterly) | Audit Committee | Compliance, constitution health |

---

## 15. One-Page Summary

> **Project Vector** runs as a 4-tier agent org: *Human Governance → COA → 9 Department Managers → Squad Leads → hundreds of Workers.* Ownership is exclusive and CODEOWNERS-enforced. Communication is async over a standard message envelope. Escalation is 5-level with auto-promotion on SLA breach. Approval is tiered with CAB and two-person rules for sensitive changes. Docs, security, and repos follow shared-responsibility-with-a-steward model. Knowledge lives in a central graph, never in agent memory, so any worker can do any task.

This blueprint scales linearly: add workers to queues, add squads under a DMA, add DMAs only at architectural inflection points — the COA and protocol absorb the rest.
