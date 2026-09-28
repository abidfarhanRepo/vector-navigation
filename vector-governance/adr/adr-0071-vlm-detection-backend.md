# ADR-0071 — A local VLM detection backend for vector-vision (discharges ADR-0027's heavy-CV deferral)

- **Status:** Proposed
- **Date:** 2026-08-06
- **Deciders:** D3 Architecture / D2 Product / D6 Data / D5 Security & Compliance
- **Supersedes:** none
- **Discharges:** [adr-0027](../../vector-vision/adr/adr-0027-vision-bounded-context.md)
  §Alternatives — "Heavy ML / OpenCV stack: deferred … Revisit when a heavy-CV ADR
  is accepted." This is that ADR. ADR-0027's bounded context is otherwise unchanged.
- **Superseded by:** none

## Context

`vector-vision` today is grayscale → box blur → threshold → connected components →
georeference (`pipeline.py`). That is a **blob detector**. It finds bright patches
and cannot say what any of them is; `Detection.label` is a component index, not a
name. The M3 slice is honest about this — ADR-0027 chose it deliberately for
self-containment and speed of delivery, and deferred anything heavier to a named
future ADR.

The gap that matters is not accuracy, it is **vocabulary**. A map is improved by
"there is a 50 km/h sign here", "this carriageway has three lanes", "this surface
is unpaved". A blob detector cannot produce any of those sentences at any
threshold setting, so no amount of tuning closes the distance. A vision-language
model can, and the open-weight tier is now good enough and small enough to run on
hardware we already own.

Two developments make this decidable now rather than aspirational:

1. **Open-weight VLMs do grounded detection.** The Qwen3-VL family (Apache-2.0,
   2B/4B/8B/32B/235B) supports open-vocabulary detection — objects described in
   plain language, with boxes, without retraining on a fixed class list. Recent
   flagship work adds **visual prompting**: positive and negative example boxes
   instead of a text description, which is the interesting capability for map
   features that are hard to name ("this particular kind of faded lane marking").
2. **They run locally.** GGUF weights plus `llama.cpp`'s multimodal stack
   (`libmtmd`, `--mmproj`) serve a VLM over an OpenAI-compatible HTTP endpoint on a
   consumer GPU. No hosted API, no data leaving the machine.

This ADR decides how such a model enters the stack. It deliberately does **not**
decide anything about collecting imagery from contributors; see Decision 1.

## Decision 1 — public imagery only; this creates no collection tier

The backend operates on **imagery someone else already published under an open
licence** — Mapillary being the obvious corpus, with OSM-compatible terms and
existing street-level coverage. It does not accept, request, store or process
photographs contributed by drivers.

This fence is the entire reason this ADR is cheap. ADR-0065's promise is that raw
data lives in a short-TTL quarantine and only K-gated per-segment aggregates
persist. That promise is expressed over location points and **has never been
extended to imagery** — no ADR in this repository mentions a camera. A street
photograph contains faces, plates and house numbers; the 200 m endpoint truncation
of ADR-0069/`TripTruncator` is meaningless for a photograph taken in a driveway;
and K=5 has no analogue for a single image of a single building. Those are real
problems with real answers, and none of them are answered here.

**Contributor-supplied imagery is a fourth collection tier and requires its own
ADR**, superseding or extending ADR-0065. It must not arrive as a feature flag on
this one. If it is ever built, the shape that stands a chance is on-device
extraction — the pixels never leave the phone, only the symbol does — which is a
different engineering problem from this one and depends on it succeeding first.

## Decision 2 — the model is a process, not a dependency

ADR-0027's central constraint is that `vector-vision` imports no third-party
packages, so the isolated per-repo CI stays green ("past waves broke on third-party
deps"). That constraint survives this ADR intact, because **the model is not
linked into the repo.**

- A `llama.cpp` server process holds the weights and exposes an OpenAI-compatible
  HTTP endpoint on localhost.
- `vector-vision` speaks to it with `urllib` from the standard library, behind the
  same interface the blob pipeline already implements. It gains **zero** pip
  packages.
- Backend selection is **environment-gated**, exactly mirroring the
  `NetworkBusClient` pattern from Wave 13: when `VECTOR_VISION_VLM_URL` is set the
  VLM backend is used; absent it, the stdlib blob pipeline is the backend, which is
  what CI and `run-ci.mjs` continue to exercise. No model weights in CI, no GPU in
  CI, no change to the 52 existing tests.

The existing `/detect` HTTP contract, its `vector_auth` enforcement and the
tile-server's nginx proxy (ADR-0028) are unchanged. A caller cannot tell which
backend answered except by the richness of the labels — which is the point.

**`llama.cpp` rather than Ollama**, because Ollama trails on Qwen3-VL support and
wraps the flags that matter here (`--n-gpu-layers`, context size, `--mmproj`,
batch) at precisely the moment we need to tune them against 4 GB of VRAM. Ollama is
a reasonable convenience layer later; it is the wrong tool for the measurement this
ADR is gated on.

## Decision 3 — the model tier is chosen by VRAM, and resolution beats parameters

The target machine is measured, not assumed: **NVIDIA GTX 1650, 4 GB VRAM**
(Turing TU117 — no tensor cores, no BF16, compute 7.5), Intel i7-10750H 6C/12T,
16 GB system RAM.

That budget is the binding constraint and it excludes the obvious choice:

| Model | Q4 weights | On 4 GB VRAM |
|---|---|---|
| Qwen3-VL-8B | ~6.1 GB | **Does not fit.** Partial CPU offload makes it CPU-bound and too slow for a corpus run |
| Qwen3-VL-4B | ~2.4 GB | Fits, with roughly 1.3 GB left for KV cache, the vision projector and activations |
| Qwen3-VL-2B | ~1.2 GB | Fits comfortably, leaving substantially more room for image tokens |

The non-obvious part, and the reason this is a decision rather than a lookup:
**image tokens and model weights compete for the same 4 GB.** Qwen3-VL uses dynamic
resolution, so a full-resolution street photograph consumes thousands of tokens
before the model has said anything. On a 4 GB card the practical knob is therefore
**input resolution**, not parameter count — and a 2B model that can see a speed
limit sign at legible resolution will beat a 4B model that had to downscale until
the digits are mush. Note also that VLMs degrade under aggressive quantisation more
than text-only models do, so Q4 is a measurement, not a free lunch.

**The decision is therefore procedural: start at 4B/Q4, measure against a held-out
set of real street imagery at several input resolutions, and drop to 2B if — and
only if — the extra resolution it buys wins on the numbers.** Parameter count is
not the figure of merit; correct labels per hour is. Ticket `25` owns this.

## Decision 4 — a closed vocabulary, and the output is evidence, not truth

An open-vocabulary model asked an open question returns open prose, and open prose
is not a map. Three constraints, all testable:

- **A closed label enum**, versioned, small to begin with (speed limit value, lane
  count, surface class, one-way, stop/give-way). Anything outside it is discarded
  and counted, not coerced to the nearest member.
- **An explicit refusal path.** "I cannot tell" must be a first-class, countable
  answer. A VLM asked to find a sign in a photograph containing no sign will
  frequently find one; a prompt with no refusal path guarantees confident fiction.
- **Detections are evidence and enter the existing promotion machinery**
  (ADR-0066), never the map directly. A feature is promoted only on **two
  independent observations from different source images**. This is a *quality*
  floor and must never be described as a privacy floor or conflated with the K=5
  floor of ADR-0065 — a mislabelling this project has already had to correct once,
  in ADR-0069 §Clarification.

Every model output carries the model identity, quantisation, prompt version and
input resolution. A prompt change is a new pass over the corpus, not an in-place
edit — the same reasoning that makes ADR-0069 refuse duplicate uploads applies to
re-deriving facts from the same pixels under different instructions.

## Decision 5 — street-level imagery needs a georeferencer this repo does not have

`georef.GeoReferencer` maps pixels to coordinates by **linear interpolation across
a bounding box**: `x=0 → min_lon`, `y=0 → max_lat`. That is correct for an
orthorectified overhead image and **wrong for a forward-facing street photograph**,
where a sign at pixel `(x, y)` is emphatically not at the bbox-interpolated
position. Wired up naively, every street-level detection would be placed somewhere
between the camera and nowhere, with entirely plausible-looking coordinates.

That failure mode — data that looks legitimate and is not — is the one thing
`.scratch/vector-collect/GOALS.md` lists as non-negotiable. So:

**No street-level detection may be written to the store until a pose-aware
georeferencing path exists.** It needs the camera position and bearing (Mapillary
publishes both), a ray through the detected box, and an intersection with either
the road geometry or a depth estimate. Ticket `26` owns it, and it blocks the
store-writing half of ticket `25`.

Overhead and satellite imagery keep the existing affine path, which is correct for
them. The two must be different code paths with different names, because the
current single path silently accepts both.

## Decision 6 — acceptance requires two measured numbers

This ADR stays **Proposed** until both exist, mirroring ADR-0070 §Decision 6. This
project's convention is that a tier is not accepted on the strength of its
description.

1. **A sustained throughput figure** — images per hour on the GTX 1650, over a run
   of at least 200 real images lasting long enough to reach thermal steady state.
   Sustained, not peak: a laptop under continuous GPU load throttles, and a
   five-minute benchmark will overstate an overnight run.
2. **A precision figure against hand-labelled ground truth** on a held-out set,
   reported per label class, with the refusal rate alongside it. A model that is
   90% right on the 30% of images it will answer for is a different proposition
   from one that is 60% right on all of them, and only the second number tells you
   which you have.

**If throughput is bad, the honest outcome may be to reject this ADR in favour of
the alternative below** rather than to buy a GPU. That is a legitimate result.

## Consequences

- **Positive:** closes the vocabulary gap. `vector-vision` moves from "there is a
  shape at 52.51, 13.39" to "there is a 50 km/h sign at 52.51, 13.39", which is the
  difference between an overlay and a map improvement.
- **Positive:** no privacy surface is created. No new data is collected from
  anyone, ADR-0065 is untouched, and nothing leaves the machine — the corpus is
  already public and the inference is local.
- **Positive:** the stdlib-only isolated CI stays green and hermetic, and the
  existing 52 tests are unaffected, because Decision 2 keeps the model out of
  process.
- **Positive:** it is a genuine, independent use for the M3 slice, which currently
  has a live overlay and no real work to do.
- **Negative:** an operational dependency that CI cannot verify. A `llama.cpp`
  build, a weights file and a running server are now things that can be wrong in
  production and green in CI. The env-gated fallback limits the blast radius to
  "labels get poorer", not "the service fails".
- **Negative:** 4 GB of VRAM is a real ceiling. The model tier this permits is the
  low end of the family, and the quality on offer is correspondingly the low end.
- **Negative:** VLM detections are probabilistic in a codebase whose other engines
  are deterministic. Decision 4's promotion floor mitigates this; it does not
  remove it.
- **Risk, named:** scope creep into contributor imagery. Decision 1 is the fence,
  and it is a privacy fence, not a preference.
- **Risk, named:** the corpus becomes stale relative to the road. Imagery has a
  capture date; a sign changed last month is invisible. Detections must carry the
  source image date and age out, and this interacts with `DECISIONS.md` O3
  (recency weighting), which is still open.

## Alternatives considered

- **Use Mapillary's own extracted data instead of running any model.** The
  strongest alternative and it deserves to be taken seriously: Mapillary already
  publishes machine-extracted traffic signs across 1,500+ classes and 40+ object
  classes, free, with no GPU and no inference. **If the only goal were a sign
  inventory, this ADR would be unnecessary and should be rejected.** It is proposed
  anyway because the fixed class list does not cover what a routing engine most
  wants — lane counts, turn restrictions, surface quality — and because visual
  prompting (Decision 3's motivation) addresses concepts no fixed list contains.
  **Ticket `25` must therefore establish a baseline against Mapillary's extractions
  before the VLM is judged useful**, or this ADR is buying with a GPU what was
  already free.
- **Qwen3.8-Max via the hosted API.** Rejected. It is the model that prompted this
  investigation and it is the wrong one: 2.4T parameters is not self-hostable at any
  quantisation, the weights had not shipped as of this date, and $2/$6 per M tokens
  over a corpus is an unbounded bill. Decisively, sending imagery to a third-party
  API inverts the property that makes Decision 1 safe — that nothing leaves the
  machine. Worth revisiting only for offline evaluation of a *sample*, to establish
  a quality ceiling the local model is measured against.
- **A fixed-class detector (RF-DETR, YOLO-class).** Faster, smaller, and far more
  comfortable on 4 GB. Rejected as the primary path because it reintroduces exactly
  the closed vocabulary this ADR exists to escape, and because adding a class means
  labelling and retraining. Genuinely worth revisiting as a **second stage**: a VLM
  is a reasonable way to bootstrap labels for a small fast detector that then does
  the volume.
- **Rust or a Go engine per ADR-0003.** Not applicable — the inference process is
  `llama.cpp` regardless of what calls it, and the calling code is a few dozen lines
  of HTTP.
- **Do nothing; leave `vector-vision` as a blob detector.** The honest baseline.
  Rejected because the slice then has no path to producing map data, and Wave 11/12
  built it on the premise that it would.

## References

- ADR-0027 — `vector-vision` bounded context; deferred heavy CV to this ADR
- ADR-0028 — vision live overlay; the `/detect` contract this backend sits behind
- ADR-0003 (mixed-by-layer), ADR-0005 (product-first), ADR-0006 (runtime install)
- ADR-0065 — privacy gate. **Untouched by this ADR, and Decision 1 is why**
- ADR-0066 — promotion thresholds; where detections become facts
- ADR-0069 §Clarification — the K=5 wording correction Decision 4 must not repeat
- ADR-0070 §Decision 6 — the "accepted only on a measured number" convention
- `.scratch/vector-vision-vlm/GOALS.md`, tickets `25`, `26`, `27`
- Source of the visual-prompting claim: SkalskiP (Roboflow), 2026-08-04
