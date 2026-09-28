# VECTOR ENGINEERING KNOWLEDGE GRAPH
### The Permanent Memory of the Engineering Organization

> **Status:** Binding design · **Authority:** CTO / Blueprint §12 · **Companion:** ENGINEERING_BIBLE.md, ORGANIZATIONAL_BLUEPRINT.md
> **Purpose:** So that *every future agent learns from all previous work instead of starting from zero.*
> **Core law:** Knowledge lives in the graph, never in agent memory (Blueprint §12.5). Agents are stateless; the graph is the memory.

---

## 1. Design Principles

1. **One graph, many projections.** A single canonical graph; views (search indexes, docs site, dashboards) are derived, never primary.
2. **Everything is a node; everything is connected.** No orphan facts. If it cannot be linked, it is not yet captured.
3. **Provenance is mandatory.** Every node records *who/what created it, when, from what source, and with what confidence*.
4. **Time is first-class.** The graph is append-only at the fact level; nodes are versioned, never silently mutated.
5. **Hybrid storage.** Graph structure + vector embeddings + raw documents coexist; each does what it is best at.
6. **Confidence is computable.** No fact is trusted blindly; trust is scored, decayed, and contested.
7. **Auto-ingest, human-audit.** Most nodes are created automatically from event streams; contested or high-impact nodes require review.

---

## 2. Schema (Logical Model)

The graph is a **labeled property graph** with typed nodes and typed, directed edges. Each node carries:

- `id` — globally unique, stable URI (`kg://<type>/<uuid>`)
- `type` — node type (§3)
- `version` — node schema/instance version (§7)
- `created_at`, `updated_at`
- `provenance` — source event/agent/repo/commit
- `confidence` — 0.0–1.0 score object (§6)
- `status` — `active | deprecated | contested | superseded`
- `payload` — type-specific structured attributes
- `embedding` — vector representation of the node's semantic content (for similarity)

Each edge carries:

- `type` — relationship type (§4)
- `created_at`, `provenance`
- `weight` — strength/relevance (0–1)
- `confidence` — inherited or independent
- `metadata` — e.g., direction of causality, temporal scope

The schema itself is versioned and stored as `kg://schema/SCHEMA` nodes so the graph is self-describing.

---

## 3. Node Types

| Type | URI prefix | Captures | Key attributes |
|------|-----------|----------|----------------|
| **Repository** | `kg://repo/` | A code repository | name, owner_squad, vcs_url, primary_lang, registry_ref |
| **Service** | `kg://svc/` | A deployable unit | name, owner, repo, sla, endpoints, version |
| **Module** | `kg://mod/` | Internal code unit | path, repo, language, responsibilities, loc |
| **Decision (ADR)** | `kg://adr/` | Architecture/design decision | context, decision, consequences, status, alternatives |
| **Benchmark** | `kg://bench/` | Performance/quality measurement | target, metric, value, env, date, result_pass |
| **Paper** | `kg://paper/` | Research paper / external reference | title, authors, url, doi, summary, claims |
| **Conversation** | `kg://conv/` | AI agent dialogue / session | agents, task_id, summary, decisions, artifacts |
| **Lesson** | `kg://lesson/` | Post-mortem / learned insight | incident_ref, root_cause, action, verification |
| **Dependency** | `kg://dep/` | Internal or external dependency | name, version, license, vuln_status, source |
| **Agent** | `kg://agent/` | An agent instance (worker/SLA/DMA/COA) | class, squad, status, lifespan |
| **Task** | `kg://task/` | A unit of work | objective, status, owner, pr_ref, dod_status |
| **PullRequest** | `kg://pr/` | A code change event | repo, diff_ref, approvals, gates, outcome |
| **Incident** | `kg://inc/` | Failure / escalation event | severity, owner, timeline, resolution |
| **Contract** | `kg://contract/` | API/interface agreement | producer, consumers, version, schema_ref |
| **Pattern** | `kg://pattern/` | Reusable design pattern | name, problem, applicability, adr_ref |
| **Document** | `kg://doc/` | Human/agent doc artifact | repo, path, kind (readme/runbook/adr), summary |
| **Concept** | `kg://concept/` | Domain term / glossary entry | term, definition, aliases |
| **Metric** | `kg://metric/` | Live or historical telemetry point | service, signal, value, timestamp, slo_ref |
| **Standard** | `kg://std/` | Bible/Blueprint rule reference | doc_ref, section, obligation (MUST/SHOULD) |

---

## 4. Relationships (Edge Types)

**Structural / ownership**
- `OWNS` — Squad → Repo/Service/Module
- `PART_OF` — Module → Repo; Service → Repo; Metric → Service
- `DEPENDS_ON` — Repo/Service/Module → Dependency/Service (with `scope`: build/runtime/test)
- `CONTAINS` — Repo → Document; Conversation → Decision

**Decision & knowledge**
- `DECIDED_BY` — ADR → Conversation/Agent; Decision → Paper (justification)
- `SUPERSEDES` — ADR → ADR (inverse `SUPERSEDED_BY`); Pattern → Pattern
- `IMPLEMENTS` — Module/Service → ADR; Task → ADR
- `CITES` — Paper → Paper; ADR → Paper; Lesson → ADR
- `REFERENCES` — Document → ADR/Pattern/Concept; Code → ADR (via annotation)

**Temporal / causal**
- `CAUSED` — Incident → RootCause(Lesson); Change(PR) → Incident
- `RESOLVED_BY` — Incident → PR/Lesson; Task → PR
- `PRECEDES` / `FOLLOWS` — Conversation → Conversation; Task → Task
- `MEASURED_BY` — Benchmark → Metric; Service → Benchmark

**Quality / trust**
- `VALIDATES` — Test(PR) → Module; Benchmark → ADR (performance claim)
- `CONTESTS` — Lesson/Agent → Node (disputes a fact; raises `contested`)
- `DREW_FROM` — Agent/Task → Node (provenance: this work used this knowledge)

**Discovery**
- `SIMILAR_TO` — computed edge between embeddings (semantic neighbors, weight = cosine)

Edges are directed; inverse traversal is always supported. Orphan nodes (no edges after ingest grace period) are flagged to COA (Blueprint §11 R7).

---

## 5. Storage Model

**Hybrid, three-tier physical layout:**

1. **Graph store (primary structure).** A property-graph database (e.g., Neo4j/Neptune-style) holds nodes + edges + properties. This is the system of record for relationships and provenance.
2. **Vector store (similarity).** Every node's `embedding` is indexed in a vector database keyed by `kg://id`. Enables semantic neighbor discovery (`SIMILAR_TO`) and natural-language retrieval.
3. **Object/document store (raw artifacts).** Full texts — papers, conversations, PR diffs, runbooks — stored as immutable blobs referenced by node `payload.blob_ref` with checksums. Keeps the graph lean.

**Keying & immutability:**
- Canonical IDs are content-addressed where possible (`kg://<type>/<hash>`), ensuring dedup.
- Fact-level append-only: updates create a new node version (§7), old versions retained.
- Indexes: by type, by owner_squad, by status, by confidence band, by time, by `task_id`/`pr_ref` for traceability.

**Registry linkage:** `vector/registry` (Blueprint §11) is itself mirrored into the graph as Repository/Service/Owner nodes — the graph is the living registry.

**Partitioning for scale:** sharded by `owner_squad` and `type` to support hundreds of agents writing concurrently; writes are idempotent on `(provenance, type)` to prevent duplicates from retries.

---

## 6. Confidence Scoring

Every node carries a **confidence object**, not a single number, computed from:

- `source_tier` — human-validated (1.0) > CAB/AMD-reviewed (0.9) > agent-generated-with-tests (0.7) > agent-generated-inferred (0.4) > unverified (0.2).
- `verification` — number/quality of `VALIDATES`/`MEASURED_BY` edges (tests passing, benchmarks confirming).
- `recency` — freshness decay (see §10) applied to time-sensitive facts.
- `contestation` — each `CONTESTS` edge reduces confidence; `contested` status caps it.
- `citation_degree` — how often the node is `DREW_FROM`/`CITES`/referenced by later validated work (wisdom of use).

**Composite score:** `confidence = w1·source + w2·verification + w3·recency − w4·contestation + w5·citation`, normalized to [0,1].

**Propagation:** edge `weight` and downstream node confidence are dampened by path length (confidence multiplies along a chain, never exceeds source).

**Usage rule:** Retrieval results MUST be ranked by confidence within relevance; agents MUST NOT treat low-confidence (<0.4) nodes as fact without flagging uncertainty. High-impact actions (prod deploy, security) REQUIRE confidence ≥ 0.8 and human/secondary review.

---

## 7. Versioning

- **Schema versioning.** The graph schema is a versioned node; breaking schema changes REQUIRE a migration ADR and dual-write during transition.
- **Node versioning.** Edits produce a new node version with new `id` + `SUPERSEDES` edge to prior; prior marked `superseded` (never deleted). Full history traversable.
- **Graph snapshots.** Periodic immutable snapshots (daily + on major events) tagged with Bible/Blueprint versions active at that time — enables "what did we know then" audits.
- **Provenance chain.** Every version links to the event/agent/commit that created it; rollback = re-activate a prior version, not mutate.
- **Contract coupling.** Service/Contract nodes version per SemVer (Bible §14); graph reflects MAJOR/MINOR/PATCH as node versions + `DEPENDS_ON` version constraints.

---

## 8. Retrieval Model

Agents query via a **unified retrieval API**; three modes compose:

1. **Structured traversal (graph query).** "Find all services owned by squad X that DEPEND_ON a dependency with vuln_status=critical." Precise, used for ownership/impact/blast-radius.
2. **Semantic search (vector).** Natural-language or embedded query → top-k `SIMILAR_TO` nodes by cosine. Used for "have we solved this before?" and lessons.
3. **Hybrid.** Semantic candidate set → graph filter by type/owner/status/confidence → ranked result. **This is the default mode** for agents (Blueprint §12.1).

**Retrieval contract (every agent, every task):**
- Agent emits a retrieval intent (task + keywords + type hints).
- API returns: relevant nodes, their confidence, and the edges explaining *why* they are relevant (provenance trail).
- Agent MUST cite returned `kg://id`s in its PR/conversation (closes the `DREW_FROM` loop).
- If retrieval returns nothing for a known class (e.g., no ADR for a big decision), agent MUST create the node — the graph self-heals.

**Explainability:** results always include the path from query context to node, so agents (and humans) can audit why something was retrieved.

---

## 9. Search Strategy

- **Facets:** type, owner_squad, status, confidence band, time window, standard/obligation. Faceted filtering is the primary precision lever.
- **Ranking:** hybrid score = `α·semantic_similarity + β·graph_relevance + γ·confidence`, tuned per query intent.
- **Blast-radius queries:** for a change, traverse `DEPENDS_ON`/`PART_OF`/`OWNS` to enumerate impacted repos/services/squads (powers CODEOWNERS + CAB routing).
- **Negative search:** "show contested or deprecated nodes in my squad" surfaces debt and risk proactively.
- **Analogical search:** given a failing benchmark or incident, find `SIMILAR_TO` past Lessons/Incidents and their `RESOLVED_BY` fixes.
- **Ambiguity handling:** low-confidence or conflicting results are returned as *alternatives with spread*, never silently collapsed.

---

## 10. Automatic Updating

The graph is **event-sourced**. An ingest pipeline subscribes to the org's event streams (Blueprint §6) and writes nodes/edges:

| Event source | Nodes/edges created |
|--------------|---------------------|
| PR merged | `PullRequest`, `RESOLVED_BY`→Task, `IMPLEMENTS`→ADR, `DREW_FROM` links, code→ADR annotations |
| CI gate result | `Benchmark`/`Metric`, `VALIDATES` edges |
| ADR accepted | `Decision` node, `SUPERSEDES` old, `CITES`→Paper |
| Contract published | `Contract`, `DEPENDS_ON` version edges |
| Dependency scan | `Dependency` update, `CONTESTS` if vuln found |
| Incident opened/closed | `Incident`, `CAUSED`→Lesson, `RESOLVED_BY` |
| Conversation end | `Conversation` summary + extracted `Decision`/`Lesson` |
| Agent provision/decom | `Agent` lifecycle nodes |
| Paper ingested | `Paper` + extracted claims → `Concept` |

**Reconciliation (COA, continuous):** the graph is diffed against `vector/registry` and live repos. Orphans, missing owners, stale dependencies, and unlinked ADRs are auto-flagged (Blueprint §11 R7, Bible §8 R7).

**Contested handling:** auto-detected conflicts (e.g., two ADRs disagree, benchmark contradicts a claim) raise `CONTESTS` and set `contested`; resolution REQUIRES DMA/CTO adjudication and a new node version.

**Idempotency:** ingest keys on `(provenance_event, type)`; retries never duplicate.

---

## 11. Long-Term Memory Strategy

The graph is the org's **collective, durable memory**. Strategy to keep it useful across hundreds of agents and years:

- **Tiered memory.**
  - *Hot:* active tasks, open incidents, recent PRs — full detail, high query volume.
  - *Warm:* current ADRs, owned services, active benchmarks — readily retrieved.
  - *Cold:* closed tasks, resolved incidents, old conversations — distilled, not deleted.
- **Consolidation (distillation).** Periodic agents (D7-led) compress cold `Conversation`/`Incident` nodes into `Lesson` and `Concept` nodes: raw detail moved to blob store, essence retained as high-confidence, well-linked nodes. Prevents bloat while preserving learning.
- **Decay & reinforcement.** Time-sensitive facts (benchmarks, vuln status) decay confidence over time (§6); frequently `DREW_FROM`/validated nodes are reinforced. Decay is reversible by re-validation.
- **Forgetting policy.** Nodes are NEVER hard-deleted. `deprecated`/`superseded` nodes are archived but traversable; only spam/PII/secrets are purged (with audit), and only after redaction, never silently.
- **Concept crystallization.** Repeated patterns across Lessons/ADRs promote into `Pattern` nodes (Bible §12 A5), raising reusable, high-confidence knowledge.
- **Cross-agent transfer.** A new worker, before any task, retrieves its squad's README + relevant ADRs + top Lessons → instant contextual onboarding (Blueprint §12.6). No agent starts from zero.
- **Audit memory.** All confidence, versions, and provenance are retained so the org can replay *what it knew and believed* at any point — essential for post-mortems and compliance.

---

## 12. Governance & Ownership of the Graph

- **Steward:** D7 (Documentation & Knowledge) owns graph tooling, schema evolution, and ingestion health — NOT the content (content is owned by the squads that produce it).
- **Schema changes:** require CTO + D3 (Architecture) approval (Bible §20).
- **Integrity SLA:** ingestion lag MUST stay < 15 min from event to queryable node; COA monitors.
- **Access:** read = all agents; write = ingest pipeline + owning squad (via events); structural edits = D7/COA only.
- **Backup:** graph + blobs + embeddings snapshotted daily, geo-replicated; restore tested quarterly.

---

## 13. Canonical Summary

> The Vector Knowledge Graph is a **versioned, hybrid (graph + vector + blob), event-sourced property graph** where every repo, decision, benchmark, paper, conversation, lesson, dependency, and agent is a node and everything is connected by typed, provenance-bearing edges. Confidence is computed and propagated; retrieval is hybrid and explainable; ingest is automatic from org event streams with continuous reconciliation; and long-term memory is preserved through tiered storage, distillation, and non-destructive versioning. **It is the permanent, shared memory that lets any agent stand on all previous work.**
