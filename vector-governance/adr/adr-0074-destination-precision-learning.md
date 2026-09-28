# ADR-0074 — Destination-precision learning: recovering endpoint precision inside the k-anonymous aggregate

- **Status:** **Proposed** (NOT Accepted — see §Ratification gate)
- **Date:** 2026-08-27 (hardened pass 2, 2026-08-27)
- **Deciders:** D3 Architecture / D5 Security & Compliance / D6 Data & ML / D2 Product
- **Supersedes:** none
- **Modifies:** the endpoint-truncation mechanism of ADR-0065 (narrow amendment recorded in ADR-0065 addendum, not a revocation)
- **Security basis:** the final pre-ratification security/privacy review (verdict APPROVE WITH CONDITIONS) is incorporated here as mandatory changes.

> **This ADR does NOT implement code.** It is an architecture/ratification gate. The parking detector, entrance detector, precise destination facts, and arrival-routing hints remain **explicitly blocked** until this ADR is ratified (see §Blocked work).

---

## 0. One-line principle this ADR must satisfy

> **Learn the world precisely enough to be useful, but never learn a person precisely enough to expose them.**

ADR-0074 recovers *collective* destination precision. It does **not** grant permission for any individual precise destination to exist beyond the tightly-scoped, delete-on-consume, backup-excluded precision store defined below.

---

## 1. Context

### 1.1 The product need (unchanged from pass 1)

ADR-0073 (north-star) extends `vector-learning` toward **place intelligence**: parking in front of shops, entrances, hidden parking, business-center access, final-arrival understanding. The repository-wide audit (2026-08-27) found that **every one of those facts is blocked by ADR-0065's endpoint truncation before a single line of detector code could be correct.**

ADR-0065 truncates ~200 m from the start and end of every individual track at ingest (`vector_privacy/gate.py:_truncate_endpoints`). Consequences:

1. The quarantine store never holds a precise endpoint, so **homes and workplaces are not derivable from the store** — the intended protection.
2. `vector-learning/.../job.py:_trip_ends` derives each trip's stop from the *last stored point*, ≥ 200 m short of the true destination. Any `parking_candidate` / `entrance_candidate` / arrival fact would land **~200 m from reality** — the wrong lot, the wrong side of the building, across the street. A green pipeline that is structurally wrong (the failure class ADR-0072 warned about).

200 m is **fundamentally insufficient** for destination-level facts: correct lot/area needs 10–25 m; correct side/door needs 10–25 m; final arrival ("stop here") needs ~10 m.

### 1.2 Critical correction from the security review — destination endpoints are a HIGHER sensitivity class

**The pass-1 draft claimed that a precise endpoint in the (TTL'd) quarantine is "the same exposure class as any mid-trip point already in the quarantine." This claim is false and is the central flaw the security review identified. It is retracted here.**

ADR-0065 **deliberately** gave endpoints special treatment (200 m trim) *precisely because a destination is more sensitive than a point on a road*:

- A mid-trip point on a road reveals "someone drove along this road" — low inference value.
- A precise destination endpoint reveals **where a person ended their journey** — home, workplace, clinic, residence of a specific individual, a sensitive facility. This is the single most re-identifying coordinate in a trace.

ADR-0065 originally truncated endpoints **for this reason**: to keep homes/workplaces/sensitive destinations non-derivable from the store. ADR-0074 narrows that specific guarantee *only* inside a narrowly-scoped aggregation process, and **replaces** it with stronger, explicit controls (separate precision DB, ≤ 24 h TTL, delete-on-consume, backup exclusion, S3-only access, dispersion-gated K, no individual publication). This narrowing is recorded as an explicit **amendment to ADR-0065** (see ADR-0065 addendum), not a silent weakening, and it must be ratified as such.

**In multi-user deployments (which ADR-0068 explicitly contemplates), the precision store holds other people's precise destinations.** This is a materially higher exposure than a single-operator self-host. Therefore: in multi-user mode the precision store is **off by default** and requires explicit heightened consent per the ADR-0065 addendum; it cannot be enabled merely by the general opt-in from ADR-0068 Decision 1.

---

## 2. Candidate solutions evaluated

### Option A — Privacy-preserving dual-signal endpoint (CHOSEN, refined to A'')

Keep a precise endpoint signal in a **separate precision database** (not a column in the normal observation table), readable **only by the S3 aggregation job**, **deleted on consume**, hard-TTL'd at **≤ 24 h**, **excluded from all backups/snapshots/dumps**, and **never written to, returned by, or logged from any per-trip-accessible path**. The published artifact is the **centroid of ≥ K sufficiently-dispersed distinct trip endpoints**, never any individual endpoint. The centroid is **grid-snapped (≥ 10 m) AND, pending the DP design review (§5), may additionally carry DP noise**.

Lifecycle proving the boundary:

| Stage | Where precise endpoint exists | Who can read it | Destroyed when |
| --- | --- | --- | --- |
| Capture → precision DB | precise endpoint row, **separate DB file** (`precision_endpoints.db`) | **only** the S3 batch worker (server-side, post-gate) | **delete-on-consume**; hard max **≤ 24 h**; `VACUUM`; **never in any backup/snapshot/dump** |
| Aggregation (S3) | in-memory, per-trip, only to compute centroid + count distinct trips | `aggregate.py` / `detectors.py` in-process | end of batch process (rows already deleted) |
| Emission (S4) | **never** — only `centroid_snapped_to_grid`, `distinct_trips`, `confidence`, `associated_poi` (OSM id), `band`, `fact_type` | KG / fact store | N/A (non-personal by construction) |

### Option B — Keep the 200 m truncation, learn coarse areas (REJECTED for destination precision)

With 200 m offset, a parking fact lands in a 200 m-radius circle — wrong as often as right for business centers, wrong side of building for entrances, impossible for hidden parking. Acceptable *only* for the existing coarse `poi_candidate` ("a place exists here," already shipped), not for parking/entrance/arrival.

### Option C — Fix ADR-0068 per-batch over-truncation (REQUIRED HYGIENE, not sufficient alone)

Truncation runs per upload batch, not per true trip, so a trip in 5 batches loses ~400 m per boundary — over-truncation, privacy-safe but data-lossy. Fixing it (truncate once, per true trip end) is correct and must be done, but does **not** recover precision on its own. It is a prerequisite that makes whatever precision decision we take *correct*.

---

## 3. Privacy boundary (MANDATORY — exact statement)

What MUST hold before this ADR is ratifiable:

- **Individual precise endpoint** exists *only* in the separate precision DB, reachable *only* by the S3 aggregation worker in-process, for a bounded TTL (≤ 24 h, delete-on-consume), and **never** in any API response, log, metric, error, admin view, backup, snapshot, dump, diagnostic bundle, or per-trip query.
- **Published artifact** = a centroid of ≥ K **sufficiently-dispersed** distinct trip endpoints + `distinct_trips`, `fact_type`, `associated_poi` (OSM id, non-personal), `band` (0–7, never a timestamp), `confidence`, `category`. **Never**: an individual coordinate, a trajectory, a per-trip pseudonym, a fine timestamp, or `approach_heading` (see §9).
- **Boundary line:** the instant `fact_store.record()` is called, the precision endpoint must be unreachable from the published fact, and the precision row it came from must already be deleted.

---

## 4. The K gate — strengthened for destinations (MANDATORY)

**K = 5 distinct-trip floor remains mandatory and is NOT weakened.** `aggregate.K_ANONYMITY_FLOOR = 5` and `fact_schema.validate()` (evidence_count < 5 → ValueError) both still apply. **But K = 5 alone is NOT sufficient for destination data.** A precise destination is a higher sensitivity class (§1.2), and the aggregation gate must additionally require:

### 4.1 K ≥ 5 distinct observations/trips

- **Why:** baseline anonymity floor; prevents a rare destination from becoming a personal fact.
- **Attack mitigated:** singleton/rare-destination publication.
- **Utility impact:** rare destinations (fewer than 5 independent trips) simply get no precise fact — identical to today.
- **Configurable?** No. Floor is non-configurable below 5 (global, from ADR-0065).
- **Globally fixed or category-dependent?** Globally fixed floor.

### 4.2 "Distinct trip token" is NOT proof of distinct human — contributor-diversity proxy

ADR-0068 already concedes a client can mint a new token per batch. For *speed* facts that is self-harming; for *endpoints* it lets **one actor** submit 5 batches with 5 tokens to the same destination and clear K = 5, publishing *their own* (or a targeted) precise location.

**Therefore 5 tokens ≠ 5 humans.** The destination-learning system must NOT treat token uniqueness as proof of contributor uniqueness. A **contributor-diversity proxy** is required:

- **Mechanism (conceptual):** require evidence that the K endpoints plausibly originate from distinct clients, using signals *other* than the self-asserted token — e.g. distinct client-instance salts / attestation where available, distinct upload-edge fingerprints, or (where unavailable) a residual risk accepted only when combined with 4.3/4.4 dispersion so a single actor cannot satisfy the gate alone.
- **Why:** stops one actor from manufacturing K = 5 and publishing a targeted precise destination.
- **Attack mitigated:** self-minted-token K-satisfaction / targeted-location poisoning.
- **Utility impact:** in single-operator self-hosts one actor genuinely supplies all 5 trips; dispersion (4.3/4.4) is then the compensating control, and the published centroid is a *region*, not a *spot*.
- **Configurable?** The proxy *method* may be deployment-dependent; the requirement that some contributor-diversity control exists is not.
- **Globally fixed or category-dependent?** Requirement is global; method may vary by deployment trust model.

### 4.3 Spatial dispersion requirement

- **Threshold (proposed, REQUIRES RATIFICATION OF VALUE):** the K endpoints must span at least **D = 50 m** (max pairwise / bounding radius) — i.e. they cannot all fall within a 50 m spot. *Justification:* one actor's 5 submissions to the same parking space would collapse inside 50 m; requiring spread forces either genuinely distinct arrivals or suppresses the cluster. For sparse/low-density regions a *larger* D may be needed.
- **Why:** prevents a concentrated cluster (one actor, or five cars in one tight row) from masquerading as a dispersed crowd and pinpointing a single space.
- **Attack mitigated:** concentrated-cluster / single-actor pinpointing.
- **Utility impact:** a genuinely popular single-entrance lot where everyone parks in one ~30 m row may be suppressed until more spread exists — acceptable; precision there is low-value.
- **Configurable?** Yes — D is a parameter, but with a non-configurable **minimum** (cannot be set to 0).
- **Globally fixed or category-dependent?** **Category-dependent** — sensitive POIs (§6) use a larger D.

### 4.4 Temporal dispersion requirement

- **Threshold (proposed, REQUIRES RATIFICATION OF VALUE):** the K trips must span at least **N = 3 distinct calendar days** (and, for endpoint facts, should additionally show **band diversity** across the 8 ADR-0067 bands — not all in one band). *Justification:* "5 arrivals in one afternoon" is far easier for one actor (or one car) to satisfy than "5 arrivals across 3 different days"; day-spread plus band diversity raises the bar for manufactured K and limits mobility-pattern inference.
- **Why:** (a) stops same-session token farming from one actor; (b) limits recurrence/band inference that a stable small group visits a sensitive location.
- **Attack mitigated:** same-session poisoning; group-discovery at sensitive POI (with §6).
- **Utility impact:** a destination visited only on consecutive identical commutes may need more days to clear — modest delay, no privacy loss.
- **Configurable?** Yes — N is a parameter with a non-configurable minimum (≥ 2; 3 recommended).
- **Globally fixed or category-dependent?** Globally fixed floor, with band-diversity required for endpoint facts specifically.

### 4.5 Concentrated-cluster suppression

- **Rule:** if the K endpoints form an unreasonably tight cluster (fails 4.3, or a concentration metric below threshold), **suppress** the fact rather than publish a pinpointing centroid. No "publish a slightly-noised spot."
- **Why:** a tight cluster is either one actor or one car row — both pinpoint, neither anonymous.
- **Attack mitigated:** cluster-pinpointing.
- **Utility impact:** genuinely tight single-space lots wait for more spread — acceptable.
- **Configurable?** The suppression *trigger* is parameterized; suppression itself is mandatory.
- **Globally fixed or category-dependent:** global.

### 4.6 Sensitive-POI suppression (see §6)

- **Rule:** learned endpoint relationships near sensitive POI categories are subject to **higher K and/or stronger dispersion and/or non-publication** per §6.
- **Configurable / category-dependent:** **category-dependent** by definition.

### 4.7 Summary table

| Control | Mandatory? | Value status | Category-dependent? |
| --- | --- | --- | --- |
| K ≥ 5 distinct trips | **Yes** | Fixed floor (ADR-0065) | No |
| Contributor-diversity proxy | **Yes** | Method TBD (§4.2) | Method varies |
| Spatial dispersion D ≥ 50 m | **Yes** | Value requires ratification | Yes (sensitive ↑) |
| Temporal dispersion N ≥ 3 days + band diversity | **Yes** | Value requires ratification | Endpoint facts only |
| Concentrated-cluster suppression | **Yes** | Trigger parameterized | No |
| Sensitive-POI handling | **Yes** | Per §6 | Yes |

---

## 5. Privacy vs precision — explicit distinction (MANDATORY)

**10 m / 25 m grid snapping is NOT a privacy mechanism.** It is a *representation-precision* control: it bounds how finely a location is expressed. It does **not** provide plausible deniability, and it does **not** make an individual non-identifiable.

- A 10 m centroid at a clinic in a small town **is** the clinic; at a house **is** the house. `associated_poi` makes reverse-geocoding trivial.
- Snapping *fuzzes by ≤ 10 m* but an adversary who knows the grid origin can snap the published value back to its cell and, combined with POI association + band, re-identify.
- **Therefore grid snapping must never be presented as sufficient privacy protection.** It is necessary for precision control; it is not sufficient for privacy. The privacy guarantee comes from K + dispersion + delete-on-consume + (pending) DP noise + no individual publication.

---

## 6. Differential privacy — DO NOT HAND-WAVE (split: MANDATORY NOW vs REQUIRES DP DESIGN REVIEW)

The security review recommends DP-style noise on the published centroid to provide plausible deniability (which snapping cannot). Before making DP a mandatory implementation requirement, the parameters must be specified and defensible. **They are not yet established.** This section records what must be decided and splits the ADR accordingly.

### MANDATORY NOW

- The precision store, aggregation, lifetime, access-control, dispersion gate, leak testing, and backup exclusion in §3/§4/§7/§8 are mandatory and ratifiable now.
- The published artifact carries **no individual coordinate, no trajectory, no per-trip pseudonym, no fine timestamp, no `approach_heading`** (§9). This is mandatory now.
- A **DP design review is a precondition** for enabling precise-centroid publication in *sparse/low-population* regions and for *sensitive POI* categories.

### REQUIRES DP DESIGN REVIEW (explicit unresolved security/design decision — NOT invented here)

The following must be established by a separate design decision before DP becomes a mandatory implementation requirement. **No parameter is asserted as final:**

- **DP mechanism:** candidate is **Gaussian mechanism** (or Laplace) applied to the published centroid coordinates. (Centroid aggregation is a good fit for DP because the query is a bounded function over a bounded set.)
- **Quantity receiving noise:** the **published centroid** (x, y), *after* grid-snap, so the released point is plausibly ±R m from the true collective centroid. (Alternative considered: noise on individual coordinates pre-centroid — rejected as it perturbs the aggregate and still needs centroid noise for membership hiding.)
- **Epsilon (ε):** **UNRESOLVED.** Needs a value that bounds per-release privacy loss. Candidate range to evaluate: ε ∈ [0.1, 1.0] per release; final value requires the composition analysis below.
- **Delta (δ):** **UNRESOLVED.** For Gaussian mechanism δ ≪ 1/n_contributors; candidate δ ≈ 1e-6 to 1e-9.
- **Sensitivity:** centroid sensitivity under removal of one contributor = (bounding radius of the cluster) / K. With dispersion (§4.3) bounding the cluster and K ≥ 5, sensitivity is small but **not zero**; exact bound depends on the agreed D and on whether the centroid is clipped to the cluster bounding box (recommended: clip, then add noise scaled to clipped sensitivity).
- **Composition across repeated releases:** destinations are re-aggregated on each cycle (facts.db is durable, ADR-0067). Repeated releases of the *same* destination accumulate ε (basic composition: total ε ≈ m·ε per release; or advanced composition with a privacy budget). **UNRESOLVED:** what is the per-destination privacy budget and how is it accounted across the durable fact's refresh cycles? This is the single most important open DP question.
- **Centroid vs individual:** noise on the **centroid** (chosen), not on surviving individual rows (which are deleted anyway).
- **Interaction with grid snapping:** snap-then-noise vs noise-then-snap. Recommendation: **snap first to the ≥ 10 m grid, then add DP noise**, so the released point is plausibly deniable *within and across* cells (snapping alone leaves the cell deterministic). Needs confirmation in the design review.
- **Expected location error:** scales with ε and sensitivity; for ε ≈ 0.5 and a ~100 m cluster / K=5, expected centroid error is on the order of tens of meters. **UNRESOLVED** — must be quantified against the 10–25 m product need.
- **Utility impact for parking/entrance:** noise that keeps error ≤ ~25 m preserves "correct lot / correct side of building" utility; error > ~50 m degrades to near-200 m behaviour. The design review must confirm a noise scale that keeps utility ≥ the product minimum while delivering deniability. **UNRESOLVED.**
- **Whether DP is actually necessary at the proposed release granularity:** for **dense, well-dispersed, non-sensitive** destinations with K ≥ 5 + dispersion + delete-on-consume, the residual re-identification risk may be low enough that DP is *advisory*; for **sparse regions and sensitive POIs** DP is likely **mandatory**. **UNRESOLVED** — this is the core question the design review answers.

**Decision:** DP is **NOT marked mandatory now**. It is **REQUIRES DP DESIGN REVIEW** and is a precondition for sensitive-POI and sparse-region publication. Until that review lands, sensitive POIs follow §6 suppression/non-publication and sparse regions follow the conservative handling below.

---

## 7. Membership inference — centroid movement (MANDATORY analysis)

**Attack:** an individual contributes one endpoint. They (or any observer with read access to published facts) watch the published centroid before and after their contribution. If the centroid moves measurably and the move is consistent with their endpoint, they can confirm membership ("I was included"). This is a known vulnerability of centroid aggregation **without** DP noise.

- **Why it matters here:** delete-on-consume + dispersion reduce *individual* exposure, but the *published fact* still leaks membership because the aggregate changes when one contributor is added/removed.
- **Mitigation:** **DP noise on the centroid (§6, REQUIRES DP DESIGN REVIEW)** is the control that bounds membership-inference advantage to ≤ ε. Without DP, the mitigation is partial: dispersion + K ≥ 5 + band coarsening reduce *how much* moves, but do not make membership non-inferable.
- **Therefore:** for any destination where membership inference is a material concern (sparse regions, sensitive POIs, small stable groups), **DP is required**; its absence is recorded as a named residual risk, not hidden.
- **Mandatory now:** the aggregation MUST log/observe centroid movement and the design review MUST size DP to bound it. Publication of small-K-derived centroids without DP is prohibited.

---

## 8. Sensitive locations — smallest defensible solution (MANDATORY)

Not all POIs are equal. A learned parking location near a **supermarket / restaurant / fuel station** is materially different from one near a **potentially sensitive facility** (e.g. residence, clinic/health facility, place of worship, shelter, school, government/security site, domestic-abuse refuge, protest/site-of-political-sensitivity). A giant sensitive-location taxonomy is explicitly **not** required now.

**Smallest defensible solution (initial posture):**

- **(A) Suppress certain categories** from precise publication: a *small, enumerated* initial sensitive-set (residence, health facility, place of worship, shelter, school, government/security site) is **suppressed** from precise endpoint publication — i.e. no precise learned endpoint relationship is published for those POIs (option D for the enumerated set).
- **(B)/(C) Higher K + stronger dispersion** for a *second, broader* sensitive tier (e.g. fuel station adjacent to a residence, or any POI inside a low-population region) — these use a larger D (§4.3) and a higher K floor (proposed K ≥ 8, value requires ratification) rather than full suppression.
- **(D) Avoid publishing learned endpoint relationships for the most sensitive POIs entirely** — this is the default for the enumerated set in (A) until a DP design review establishes a safe parameterization.
- **No automatic taxonomy crawling:** the sensitive set is a short explicit list owned by D5, not an OSM-tag scrape. Expansion requires a new ADR.

**Why this and not more:** it covers the clearly-sensitive cases with near-zero utility cost (those destinations are rare/precision-low-value for a routing product) while leaving ordinary place intelligence intact, and it avoids the maintenance and false-positive burden of a large taxonomy.

---

## 9. Approach heading (MANDATORY — keep OUT initially)

- `approach_heading` is **excluded from the published learned fact** in the initial design.
- **Why:** `precise centroid + POI + exact heading + time pattern` is an unnecessary and strong inference vector (it can reveal *which entrance/driveway* and *when*, narrowing re-identification far below K).
- **If needed later:** investigate **coarse directional buckets** (e.g. 8 compass octants, never exact degrees) rather than exact heading, and only after the DP design review. Exact heading is prohibited from publication.
- **Mandatory now:** the fact schema MUST reject `approach_heading` (forbidden-key class, like `pseudonym`).

---

## 10. Precision endpoint lifetime & secure deletion (MANDATORY)

- **delete-on-consume:** precision rows are removed the moment the aggregation batch that reads them completes. They need not outlive the batch.
- **hard maximum TTL ≤ 24 h** — *shorter* than ADR-0065's 72 h general TTL, because the sensitivity class is higher (§1.2).
- **excluded from backups:** see §11.
- **VACUUM / secure deletion:** the precision DB is `VACUUM`ed (or uses secure-delete / WAL-trim) so deleted rows are not recoverable from free pages. Strategy documented in the implementation ADR/spec (post-ratification).
- **no snapshots containing the precision DB:** the precision DB file is excluded from any volume/snapshot mechanism (see §11).

---

## 11. Backup & snapshot security (MANDATORY)

**A TTL that exists only in the live database is insufficient if the same data exists indefinitely in backups.** The precision DB must be explicitly excluded from:

- automated backups
- volume / filesystem snapshots
- database dumps (`pg_dump` / `sqlite3 .dump` / export tooling)
- diagnostic bundles

**Operational controls (mandatory, proven by tests):**

1. The precision DB lives at a configurable path (`VECTOR_PRECISION_DB_PATH`) that backup tooling is wired to **skip** via an explicit exclude list (not an include list — fail closed).
2. A startup self-check asserts the precision DB is **not** inside any backed-up volume mount; if it is, the aggregation worker refuses to start (fail-closed).
3. A test asserts that a simulated backup run of the standard backup command **does not contain** any precision-endpoint row (synthetic data only).
4. A test asserts no snapshot manifest references the precision DB path.

These are added to the leak-test plan (§12).

---

## 12. Leak testing — security-test plan (MANDATORY)

Verify that **synthetic** precision endpoints cannot appear through any of:

- `SELECT *` on any table the normal pipeline reads
- normal trace / observation APIs (`nearby()`, `recent()`, `count()`)
- debug logging (assert no precision field is ever formatted into a log line)
- exception messages / stack traces
- metrics emitters
- admin endpoints
- test fixtures (fixtures MUST use synthetic precision values; **never real**)
- exported datasets
- backups / snapshots / dumps (§11)
- published fact payloads (assert schema contains no precision field)

**Rules:**

- All precision-endpoint test data is **synthetic** (generated, not from real traces). Real precision endpoints are **never** placed in fixtures.
- A dedicated test suite `tests/test_precision_leak.py` asserts the precision store is never returned by any per-trip/per-user query and never appears in any of the above surfaces.
- Column lists are explicit everywhere; no `SELECT *` reaches the precision DB; the precision DB has no ORM/query path outside the S3 worker.

---

## 13. Access control (MANDATORY — exact actors)

- **Allowed to read precision endpoints:** the **S3 aggregation worker only** (`vector-learning` batch job, in-process: `job.py` / `aggregate.py` / `detectors.py`).
- **Explicitly forbidden:**
  - `vector-web` (any request handler)
  - `nearby()` / `recent()` / `count()`
  - any client API
  - admin tools
  - debug tooling
  - metrics
  - `vector-geocoder`
  - `vector-routing`
  - published fact APIs
  - `vector-observability` (it may report *counts*, never *values*)
- The precision DB has **no network endpoint, no query path, and no schema exposure** outside the S3 worker process.

---

## 14. Precision requirements (minimum useful, not maximum)

| Fact | Minimum useful precision | 200 m sufficient? |
| --- | --- | --- |
| Parking (front-of-shop / business-center / hidden) | **10–25 m** | No |
| Entrance / access opening | **10–25 m** | No |
| Access road | **10–25 m** | No |
| Building footprint | OSM authoritative (5–10 m) | N/A — use OSM |
| Business center (region) | 25–50 m | Marginal |
| Grocery / amenity | OSM authoritative; Vector learns *relationship* | N/A |
| Final arrival | **~10 m** | No |

**Target: published centroid snapped to ≥ 10 m grid** (25 m for regional clusters). With DP noise (§6) the *effective* released precision is grid-cell ± noise — deniable, not just moved.

---

## 15. Decision (consolidated)

Adopt **Option A'' = Option C (fix trip-boundary truncation) + Option A (separate precision DB, precise endpoint consumed only inside the dispersion-gated K centroid, deleted on consume, ≤ 24 h, backup-excluded) + grid-snap (≥ 10 m) + DP-design-review-gated noise + sensitive-POI suppression + no `approach_heading`**.

1. Fix ADR-0068's per-batch over-truncation (hygiene).
2. Retain a precise endpoint in a **separate precision DB**, readable only by the S3 aggregation job, **delete-on-consume**, hard TTL **≤ 24 h**, **excluded from backups/snapshots/dumps**, never exposed to any per-trip consumer and never to S4.
3. Aggregate the centroid **only** when K ≥ 5 **AND** the endpoints show contributor-diversity (§4.2), spatial dispersion (§4.3), temporal dispersion + band diversity (§4.4), and are not a suppressed cluster (§4.5) / sensitive POI (§6).
4. Emit a grid-snapped (≥ 10 m) centroid + `distinct_trips` + `confidence` + `associated_poi` (OSM id) + `band` — **never** an individual endpoint, trajectory, per-trip pseudonym, fine timestamp, or `approach_heading`.
5. K = 5, forbidden-key validation, 8-band temporal coarsening, accuracy floor, dispersion gate — all enforced for the new fact types.
6. DP noise is **REQUIRES DP DESIGN REVIEW** (§6), mandatory only for sensitive POIs and sparse regions pending that review.

This **modifies ADR-0065's endpoint-truncation mechanism** (endpoints in the precision DB are no longer trimmed to 200 m) but **preserves its principle** (no individual precise destination persists, and individual-level endpoint protection remains in force per the ADR-0065 addendum). The change is an explicit **narrow amendment** to ADR-0065 — not a revocation and not a silent config change.

---

## 16. Consequence / migration (summary)

- **Positive:** parking / entrance / arrival facts become correct (10–25 m) instead of structurally 200 m wrong.
- **Positive:** persistence boundary strictly safer (centroid + grid-snap + K + dispersion + delete-on-consume + backup exclusion).
- **Positive:** endpoint exposure is *explicitly* a higher class now, with controls matched to it.
- **Negative:** precision DB is a new, carefully-isolated surface; backup-exclusion and leak tests are mandatory operational debt.
- **Negative (accepted):** ADR-0065's "endpoints are 200 m short in the store" guarantee is narrowed *only* inside the scoped aggregation process; recorded as the ADR-0065 addendum.
- **Risk named:** if the precision DB is ever exposed to a per-trip read path or included in a backup, the endpoint protection is lost. Mitigated by §11/§12 fail-closed controls + tests.

---

## 17. Blocked work (explicit — do NOT implement)

The following remain **blocked until ADR-0074 is ratified**:

- parking detector
- entrance detector
- precise destination facts
- arrival routing hints

Safe groundwork that MAY proceed now (no precise destination fact emitted): PoiIndex, OSM POI extraction, dwell-derivation research/tests, false-positive test corpus, conceptual confidence model, detector *interface* design, schema design. Detector *implementations* stay blocked.

---

## 18. Ratification gate (Status: Proposed)

This ADR is **NOT Accepted**. It becomes ratifiable only after:

1. ADR-0065 addendum (narrow amendment) is written and itself Proposed.
2. The §4.3 / §4.4 threshold values (D, N) are ratified by D5/D6.
3. The DP design review (§6) is scheduled with an owner; sensitive-POI and sparse-region publication are gated on its outcome.
4. The §11/§12 operational controls + leak tests are accepted as mandatory implementation acceptance criteria.

Until then the verdict stands at **APPROVE WITH CONDITIONS**; non-adoption ⇒ REJECT / REDESIGN.

---

## 19. Unresolved questions

- Exact D (spatial dispersion) and N (temporal dispersion) values — set at ratification.
- DP mechanism parameters (ε, δ, sensitivity bound, composition budget) — separate design decision (§6).
- Contributor-diversity proxy method per deployment trust model (§4.2).
- Grid size for regional vs point facts (10 m vs 25 m) — prototype.
- Whether deterministic jitter beyond grid-snap is worth it — defer to prototype (subsumed by DP question).

---

## References

- ADR-0065 (privacy gate — endpoint truncation amended here, via addendum), ADR-0067 (8 time bands), ADR-0068 (per-trip pseudonym + per-batch over-truncation + token-minting), ADR-0071 (imagery fence — Tier 2 client-side), ADR-0073 (north-star; this ADR resolves its blocking conflict)
- Security review: final pre-ratification privacy review, verdict APPROVE WITH CONDITIONS (§G changes incorporated)
- Issue `05` (map-match + aggregate), Issue `09` (learned POI), Issue `11` (living-world intelligence)
- Code: `vector_privacy/gate.py:_truncate_endpoints` / `apply_gate`; `vector-learning/.../job.py:_trip_ends`; `vector-learning/.../aggregate.py:K_ANONYMITY_FLOOR`; `vector-learning/.../fact_schema.py:validate`; `vector-kg-graph/src/learned-facts.js:assertNoIdentity`
