# ADR-0073 — "Learn and map like this": collective map facts, client-side 3D destination visualization, and the personal-policy fork

- **Status:** Proposed
- **Date:** 2026-08-27
- **Deciders:** D2 Product / D3 Architecture / D5 Security / D6 Data & ML
- **Supersedes:** none
- **Superseded by:** none

## Context

The product thesis — "Vector learns and maps from real driving" — was sharpened
against three external references the team is tracking:

1. **`vector-learning` (ADR-0067, issue 05/06)** — already shipped. It reads the
   SQLite quarantine store (R\*Tree `nearby()`), map-matches tracks onto the
   routable network with a candidate-set search, and emits **k-anonymous
   aggregate evidence only** under the binding K=5 floor (ADR-0065/0067).
   Detectors today emit `missing road`, `geometry correction`, `turn
   restriction`, `speed profile`.
2. **lingbot-map** (Robbyant, 16.7k★) — a feed-forward 3D foundation model for
   *streaming* 3D reconstruction (Geometric Context Transformer: ~20 FPS at
   518×378 over 10 000+ frames via paged KV cache, built on VGGT + DINOv2).
3. **drift-sdk + the r/Comma_ai post it spawned** — one project: a comma-4
   captures camera + CAN telemetry, and a *personal* end-to-end neural net is
   trained that "drives me around Los Angeles." The reference training recipe
   (`examples/drift_train_driving.ipynb`) is **supervised imitation learning**:
   VAE-encoded camera (96×160 → latent) fused with CAN bus telemetry
   (throttle/brake/steering from 0x1C4/0x0BE/0x1E5), control history, and nav →
   three continuous controls, exported to ONNX/tinygrad for **on-device**
   inference. Ego-only, per-driver.

The user's intent, restated precisely:

- **The point of collecting map facts is local intelligence about places**, not
  just geometry/speed: parking in front of shops, business centers, hidden
  parking spots, nearest grocery stores / amenities, and similar. The learned
  store should describe *where things are and how people use them*.
- **lingbot-map is referenced deliberately as a client-side capability, not a
  server learning parity**: if Vector's mapping solution differs from a full 3D
  foundation model, Vector can still give the user a **visual 3D space of where
  they have to drive** — rendered on-device from the live camera plus learned
  facts, with no personal model stored server-side.

These three references resolve into **three distinct capabilities** that must
not be conflated:

| Capability | External exemplar | Vector today | Boundary |
| --- | --- | --- | --- |
| **Collective map facts** (places + geometry + speed) | — | ✅ `vector-learning` | K=5 aggregate (ADR-0065) |
| **Client-side 3D destination visualization** | lingbot-map | ❌ not built | on-device, no personal state persisted |
| **Personal driving policy** ("drives me") | drift-sdk + Reddit | ❌ out of scope | per-driver control; ADR-0065 forbids persisting it |

The "holy-shit" magic of the Reddit demo — *it drives **me*** — is exactly what
ADR-0065/0067 are designed to **never** store. That is not a defect to fix; it
is the architectural fork this ADR names.

## Decision

Adopt a single north-star framing — "Vector learns and maps from real driving"
— decomposed into three capability tiers with explicit, different privacy and
ownership boundaries. Two tiers are authorized for design work now; one is
deferred as a separate, unsolved bounded context.

### Tier 1 — Collective map facts (SHIPPED, EXTEND)

`vector-learning` is the right shape and stays the collective brain. Extend its
detector set beyond the current four to cover **place intelligence**, learned
from dwell/stop/slow events map-matched onto the network (not from any trip
identity):

- `missing road`, `geometry correction`, `turn restriction`, `speed profile`
  (existing)
- **`place` / `dwell`** — recurrent stops near a POI → parking in front of shops,
  business centers, **hidden parking spots** (recurrent dwell where no parking
  is mapped).
- **`amenity proximity`** — aggregate of destination/dwell POIs → nearest
  grocery / amenities per segment, hour-of-week banded (ADR-0067).

These remain subject to the binding K=5 floor and the 72 h quarantine TTL
(ADR-0065). Below-K groups are discarded; raw GPS dies at `vector-learning`
exactly as ADR-0067 specifies. This is the in-bounds, already-shipping path and
needs no new governance — only a detector addition (issue 05 follow-on).

### The destination-precision conflict (BLOCKING — see ADR-0074)

Tier 1's place intelligence **cannot be built on the current pipeline as-is**, and the reason is
architectural, not a missing detector. ADR-0065 truncates ~200 m from the start and end of every
individual track at ingest (`vector_privacy/gate.py:_truncate_endpoints`). `vector-learning`'s
trip-end derivation (`job.py:_trip_ends`) therefore works from points already ≥ 200 m short of the
true destination. Any parking / entrance / arrival / hidden-parking fact emitted today would land
**~200 m from reality** — the wrong lot, the wrong side of the building, or across the street.
That is a green pipeline that is structurally wrong, the exact failure class ADR-0072 warned about.

This is a **precision conflict, not a privacy conflict**: ADR-0065's truncation is a
*per-individual, pre-aggregation* defense, but the aggregate is already protected by the K=5 floor
+ centroid-only persistence. The truncation is doing double duty — protecting the raw individual
(and keeping it) *and* preventing the collective from ever being precise (which it does not need
to do). **ADR-0074 is the decision point that resolves this**: it recovers endpoint precision
*inside* the k-anonymous centroid (fixing ADR-0068's per-batch over-truncation, consuming a precise
endpoint only in the S3 aggregation and destroying it at TTL, grid-snapping the published centroid
to ≥ 10 m) while preserving the principle *no individual precise destination ever persists*.

**Direct dependency:** parking detection depends on ADR-0074. Entrance detection depends on ADR-0074.
Arrival intelligence depends on ADR-0074. Final destination visualization (Tier 2) may depend on
ADR-0074 for precise placement. **No precise destination detector or routing-arrival code may be
written until ADR-0074 is ratified** (see issue `11`). The coarse `poi_candidate` ("a place exists
here") already shipped and remains acceptable at 200 m offset; the new destination facts are
distinct and gated on ADR-0074.

**Privacy-control dependency (added with the ADR-0074 hardening pass):** destination precision now
depends not merely on K = 5 but on the *additional* privacy controls defined by ADR-0074 — a separate
precision DB, ≤ 24 h delete-on-consume lifetime, backup exclusion, S3-only access, destination-specific
spatial/temporal dispersion, concentrated-cluster suppression, a contributor-diversity proxy (because
self-minted tokens ≠ distinct humans, ADR-0068), sensitive-POI suppression, and DP-noise pending a
separate design review. ADR-0074 is **Proposed**, not yet ratified; this note does not alter the
overall Vector vision and adds no new product capability on its own.

### Tier 2 — Client-side 3D destination visualization (AUTHORIZED, PROPOSED)

Treat lingbot-map as a **client capability reference**, not a server parity
target. Vector may render a 3D space of the route/destination the user must
drive to, **on-device**:

- Inputs: live camera (already captured client-side per ADR-0070) + learned
  collective facts (Tier 1) pulled per-request.
- No personal model is trained or persisted. The reconstruction is ephemeral and
  leaves no server-side artifact.
- This is compatible with ADR-0065 by construction: nothing personal is stored;
  it consumes the same k-anonymous aggregates everyone else does.

If a full 3D foundation model proves unnecessary, a lighter client renderer
(learned facts + base geometry + live camera) satisfies the same user need.
Decision deferred to a future `vector-vision` / client-render bounded context;
this ADR authorizes the *direction*, not the implementation.

### Tier 3 — Personal driving policy ("learn like me") (DEFERRED, UNSOLVED)

The drift-sdk recipe is documented here as the **concrete reference** for what
"personal policy" training actually is (VAE vision encoder + CAN telemetry +
control history + nav → continuous throttle/brake/steering, ONNX/tinygrad,
on-device). It is **out of scope for `vector-learning`** for three independent
reasons:

1. **ADR-0065 prohibition** — a personal control policy is per-trip, per-driver
   knowledge; the aggregate store is forbidden from ever holding it.
2. **Different safety/regulatory surface** — a learned *control* output that
   moves a vehicle is a distinct risk class from a *map fact*; it cannot be
   "vector-learning with K=1."
3. **ADR-0068 scope is exactly one trip** — the opt-in pseudonym is a single
   trip, so even the raw data tier cannot assemble a per-driver model today.

A personal-policy tier, if ever pursued, requires a **new bounded context** with
its own consent model, retention bound, and safety review — separate from the
collective aggregate and the trip-scoped raw tier. This ADR does **not**
authorize it; it records the fork so the three are never accidentally merged.

## Consequences

- **Positive:** the product thesis is now decomposable into shippable,
  privacy-compatible work (Tier 1 extension; Tier 2 client render) versus a
  genuinely separate, unsolved problem (Tier 3) — ending the ambiguity of
  "learn and map like this."
- **Positive:** Tier 1's place-intelligence extension gives Vector a concrete,
  differentiated fact set (parking, amenities) rather than only speed/geometry.
- **Positive:** lingbot-map is usefully demoted from "competitor model" to
  "client UX reference," removing a false parity pressure.
- **Negative:** Tier 3's magic (a model that drives *you*) is explicitly
  out-of-bounds; anyone expecting Vector to store a personal driving model is
  corrected at the architecture level.
- **Negative:** Tier 2 needs a home repo (likely `vector-vision` or a new
  client-render context) that does not yet exist; this ADR authorizes direction
  only.

## Alternatives considered

- **Merge personal policy into `vector-learning` (K=1).** Rejected — violates
  ADR-0065's binding floor and the "raw GPS dies here" invariant (ADR-0067);
  conflates a control surface with a map-fact surface.
- **Adopt lingbot-map server-side as Vector's learner.** Rejected — it is
  ego-centric 3D reconstruction, not a collective map-fact store, and would
  duplicate/replace `vector-learning` while breaking the privacy contract. Its
  value is the *client visualization*, captured in Tier 2.
- **Treat "learn and map" as one undifferentiated goal.** Rejected — it
  manufactures the false choice "either we store personal models or we aren't
  learning." The three-tier split dissolves that choice.

## References

- Issue `05` (map-match + aggregate), Issue `06` (KG facts), Issue `07` (ETA
  falsifiable claim) — `.scratch/vector-evolve/`
- ADR-0065 (privacy gate, K=5, 72 h TTL), ADR-0066 (promotion thresholds +
  tile invalidation), ADR-0067 (time bands + learning bounded context),
  ADR-0068 (opt-in collection + trip scope), ADR-0069 (track import tier),
  ADR-0070 (native capture shell), ADR-0071 (VLM detection backend),
  **ADR-0074 (destination-precision learning — resolves the blocking 200 m
  endpoint-truncation conflict for Tier 1 place intelligence)**
- External: Robbyant/lingbot-map (Geometric Context Transformer, 3D streaming
  reconstruction); JordyKieto/drift-sdk + `examples/drift_train_driving.ipynb`
  (supervised imitation-learning driving policy, on-device ONNX/tinygrad)
