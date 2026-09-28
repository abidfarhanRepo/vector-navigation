# VECTOR — AI RESEARCH DEPARTMENT CHARTER
### Department D8 · Research & Intelligence · "The Eyes of the Org"

> **Status:** Binding department charter · **Authority:** CTO / Blueprint §1 (D8) · **Companion:** ORGANIZATIONAL_BLUEPRINT.md, ENGINEERING_BIBLE.md, KNOWLEDGE_GRAPH.md
> **One-line mandate:** *Continuously scan the world and the org, distill signal into engineering intelligence, and feed the Knowledge Graph and Architecture pipeline — without ever touching production code.*

---

## 0. Core Doctrine

1. **Read-only to production.** This department SHALL NOT write, modify, or merge code in any product repository. Its only write targets are: the Knowledge Graph, the report store, and recommendation records (which become ADR proposals for D3).
2. **Signal over noise.** The department's value is *curation and synthesis*, not raw collection. A thousand papers ingested is worthless without a ranked, actionable brief.
3. **Everything cited.** Every claim in every report MUST trace to a source node in the Knowledge Graph (`CITES` edge). No assertion without provenance (KG §3, Bible §11).
4. **Default to the graph.** All structured findings are written as KG nodes first; reports are human-readable projections of those nodes.
5. **Recommend, don't decide.** Architecture recommendations are proposals routed to D3 (Architecture DMA). They become ADRs only through the Bible §11 process.
6. **Proactive warning.** CVEs, breaking upstream changes, and regressing benchmarks MUST be surfaced to the owning squad and D5/D3 within SLA — this is a safety function, not a research luxury.

---

## 1. Mission

The AI Research Department is Vector's standing intelligence function. Modeled on a elite industrial research organization, it operates as a *perpetual sensing and reasoning layer* that:

- Monitors the external technical world (GitHub, RFCs, papers, blogs, conferences, CVEs, release notes, changelogs, benchmarks).
- Monitors the internal technical state (our repos, our benchmarks, our incidents, our ADRs).
- Produces **engineering intelligence reports** that tell the org *what changed, what matters, and what to do*.
- Keeps the **Knowledge Graph** current with vetted external and internal knowledge.
- Generates **architectural improvement recommendations** with evidence and confidence.
- Protects the org from surprise — supply-chain, security, and capability gaps.

---

## 2. Position in the Organization

```
TIER 2:  D8 DMA (Research Director Agent)
            │ leads
TIER 3:  SLAs — one per intelligence domain
            │ assigns to
TIER 4:  Reader / Analyst / Synthesizer Worker Agents (ephemeral, scaled per queue)
```

- **Reports to:** COA (per Blueprint §2/§3), chartered by CTO.
- **Primary partners:**
  - **D3 Architecture** — receives architecture recommendations; co-authors ADRs.
  - **D5 Security** — receives CVE/exploit intelligence; feeds threat model.
  - **D7 Knowledge** — owns the KG ingestion schema the department writes into.
  - **D6 Data/ML** — consumes benchmark/paper intelligence for model choices.
  - **D1 Platform** — consumes dependency/release intelligence for tooling upgrades.
- **Write scope:** KG nodes (`Paper`, `Benchmark`, `Concept`, `Lesson`, `Pattern`, `Contract` proposals, `Standard` deltas), report blobs, recommendation records. **Never** product code, never `main` of any product repo.

---

## 3. Agent Hierarchy & Roles

### 3.1 D8 DMA — Research Director
- Owns department strategy, source prioritization, headcount (worker provisioning via COA), and the research backlog.
- Sets the **watchlist**: which repos, RFC streams, venues, vendors, and internal services are monitored.
- Adjudicates contested findings and signs off department reports.
- Routes architecture recommendations to D3; CVE alerts to D5.

### 3.2 Squad Lead Agents (SLAs) — one per intelligence domain
| SLA | Domain | Watch responsibility |
|-----|--------|----------------------|
| `sla.research.oss` | Open-source intelligence | GitHub repos, releases, changelogs, dependency ecosystems |
| `sla.research.std` | Standards & protocols | RFCs, specs, W3C/IETF/ISO, language/tool version roadmaps |
| `sla.research.sci` | Scientific literature | arXiv, peer-reviewed venues, conferences (NeurIPS/OSDI/SOSP/etc.) |
| `sla.research.sec` | Threat intelligence | CVEs, advisories (NVD, GHSA), exploit blogs, vendor sec notes |
| `sla.research.ind` | Industry & internal | Engineering blogs, post-mortems, our benchmarks, our incidents |
| `sla.research.synth` | Synthesis & reporting | Turns raw intake into reports + recommendations + KG writes |

### 3.3 Worker Agent Classes (Tier 4, ephemeral, horizontally scaled)
- **Reader Agent** — fetches and normalizes a source (HTML/PDF/RSS/API) into a canonical ingest record. Stateless; one source batch per run.
- **Extractor Agent** — pulls structured claims, versions, vuln IDs, benchmark numbers, and citations from a normalized record.
- **Analyzer Agent** — compares new intel against the KG: novelty, contradiction (`CONTESTS`), relevance to our stack, severity.
- **Synthesizer Agent** — drafts report sections and recommendation records from analyzed nodes.
- **KG-Writer Agent** — writes/versions KG nodes per schema (KG §3/§7), creating `CITES`/provenance edges. The only agent allowed to mutate graph content for this department.
- **Alert Agent** — raises time-sensitive signals (`#escalations` / direct to D5/D3) for CVEs and breaking changes.

---

## 4. Intake Pipeline (The Reading Engine)

The department ingests from the mandated sources on a continuous, prioritized cadence. Each source has an **owner SLA** and an **SLA for freshness**.

| Source class | Example feeds | Owner SLA | Freshness SLA |
|--------------|---------------|-----------|---------------|
| GitHub | Watched repos, releases, PRs, issues | `oss` | 15 min (releases), 1 h (activity) |
| RFCs / specs | IETF, W3C, language/tool RFC repos | `std` | 1 h |
| Documentation | Upstream docs of our dependencies | `oss`/`std` | 24 h |
| Research papers | arXiv, venue proceedings | `sci` | 6 h (new), daily sweep |
| Changelogs | Dependency changelogs | `oss` | 24 h |
| Benchmarks | Published & internal benchmarks | `sci`/`ind` | on publication / per CI |
| Release notes | Vendor/library releases | `oss` | 1 h |
| CVEs | NVD, GHSA, vendor advisories | `sec` | 5 min (critical), 1 h (else) |
| Blogs | Engineering blogs, vendor blogs | `ind` | 24 h |
| Conferences | Proceedings, recorded talks, summits | `sci`/`ind` | per event + weekly digest |

**Pipeline stages (each is a queue + worker pool):**
1. **Discover** — watchlist scheduler emits fetch tasks.
2. **Fetch** — Reader Agent normalizes to canonical record (dedup by content hash → `kg://` id, KG §5).
3. **Extract** — Extractor Agent emits structured claims with source spans.
4. **Analyze** — Analyzer Agent scores novelty/relevance/severity; flags contradictions vs KG.
5. **Synthesize** — Synthesizer + KG-Writer persist nodes and edges; Alert Agent fires if SLA-critical.
6. **Publish** — report sections compiled; recommendations queued to D3/D5.

---

## 5. Outputs

### 5.1 Engineering Intelligence Reports
Generated on cadence and on-event. Tiers:
- **Flash alert** (event-driven, < 1 h): critical CVE, breaking upstream release, active exploit. Routed to D5 + owning squad.
- **Weekly Brief** (`synth` SLA): ranked digest of notable external + internal changes, with "so what" and "what to do."
- **Quarterly State-of-the-Art Report** (`sci`/`std`): survey of the field relevant to our stack, with candidate adoptions.
- **Incident-Adjacent Report** (`ind`): when we have an incident, the department supplies comparable external failures and prior art.
- **Adoption Dossier**: deep analysis of a specific technology with cost/benefit, risks, and a staged rollout proposal.

Every report MUST: cite KG source nodes, state confidence (KG §6), and end with **recommended actions** or explicitly state "no action."

### 5.2 Knowledge Graph Updates
- New `Paper`, `Benchmark`, `Concept`, `Contract`(proposal), `Standard`(delta) nodes.
- `CITES` edges to prior work; `CONTESTS` edges where new intel disputes existing nodes.
- Confidence assigned per KG §6; critical/validated items reinforced.

### 5.3 Architectural Improvement Recommendations
- Stored as **recommendation records** (not ADRs). Each contains: problem, evidence (KG citations), proposed change, alternatives, risk, confidence, suggested owner (squad).
- Routed to **D3 Architecture DMA** via `#contracts`/contract event. D3 may convert to an ADR (Bible §11) and assign implementation to a product squad.
- The department MAY NOT implement; it MAY attach a proof-of-concept *only* in an isolated, non-product sandbox repo explicitly outside production scope, and only with CTO/D3 permission.

---

## 6. Confidence, Quality & Contested Findings

- All findings carry a KG confidence object (KG §6): source tier, validation, recency, citation degree, contestation.
- **Contradiction handling:** when new intel conflicts with a KG node, the Analyzer raises `CONTESTS`; node becomes `contested`; DMA adjudicates; resolution creates a new node version (KG §7). The department never silently overwrites.
- **Hallucination guard:** Reader/Extractor agents MUST preserve source spans; claims without a verifiable source span are tagged `unverified` and excluded from recommendations.
- **Bias control:** the watchlist is reviewed quarterly to avoid vendor/author concentration; the DMA logs coverage gaps.

---

## 7. Cadence & Operating Rhythm

| Cadence | Activity | Owner |
|---------|----------|-------|
| Continuous | Fetch/alert pipelines, CVE monitoring | `sec` + Readers |
| Daily | Triage of new intel, KG writes, flash alerts | all SLAs |
| Weekly | Weekly Brief compilation + publish | `synth` |
| Monthly | Watchlist review, coverage gap analysis | DMA |
| Quarterly | State-of-the-Art Report, architectural recommendation batch | `sci`/`std` + DMA |
| Event-driven | Flash alerts, conference sweep, incident support | `alert` + `ind` |

---

## 8. Relationship Protocols (cross-department)

- **To D3 (Architecture):** recommendations → contract event; co-develop ADRs; never bypass Bible §11.
- **To D5 (Security):** CVE/exploit intel → `#escalations` (L3 for critical) + direct alert; feeds threat model; receives security priorities for the watchlist.
- **To D7 (Knowledge):** the department is a *primary writer* of KG external-intel nodes; must conform to D7's schema and ingestion health SLA (KG §12).
- **To D1 (Platform) / D6 (Data):** dependency/release/benchmark intel informs upgrade and model-selection roadmaps.
- **To product squads:** flash alerts + targeted briefs; never instructs code changes — only advises.

---

## 9. Metrics (Department KPIs)

- **Coverage:** % of watchlist sources ingested within freshness SLA.
- **Latency:** mean time from source publication → KG node → alert (critical CVE target < 30 min).
- **Signal precision:** % of recommendations that led to an accepted ADR or action.
- **False-alarm rate:** retracted/contested alerts per period.
- **KG contribution:** nodes/edges written, `CITES` density, contested-resolution time.
- **Adoption yield:** recommendations converted to ADRs by D3 (quarterly).

---

## 10. Hard Boundaries (Non-Negotiable)

1. The department SHALL NOT commit to, review-approve, or merge code in any product repository.
2. It SHALL NOT modify `main`, release branches, or production infrastructure.
3. Its only persistent writes are: Knowledge Graph nodes/edges, report blobs, and recommendation records.
4. It SHALL NOT make binding architecture decisions — only recommendations to D3.
5. It SHALL NOT suppress or soft-pedal a critical CVE or breaking-change alert for any reason.
6. Every output SHALL be traced to sources; unsourced assertions are forbidden (Bible §7 D3, KG §1).

---

## 11. Canonical Summary

> The AI Research Department is Vector's **perpetual, read-only-to-production intelligence org**: a fleet of Reader → Extractor → Analyzer → Synthesizer → KG-Writer agents that continuously monitor GitHub, RFCs, docs, papers, changelogs, benchmarks, release notes, CVEs, blogs, and conferences. It distills this firehose into cited, confidence-scored **engineering reports** and **architectural recommendations**, keeps the **Knowledge Graph** alive with vetted external and internal knowledge, and warns the org of threats and opportunities — all without writing a single line of production code.
