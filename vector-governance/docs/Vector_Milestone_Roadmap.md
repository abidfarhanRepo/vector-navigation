# Vector — milestone roadmap (delivery state)

**As of 2026-09-20.** Companion to `Vector_Master_Roadmap.md`, which states the
vision and the nine-stage product arc. This document states *where delivery
actually is* against that arc, milestone by milestone, with the evidence for
each claim named.

This is the first milestone-level roadmap committed to the repository. Until
now the V7.x milestone statuses existed only in session context, which is why
they drifted; the reconciliation that produced this document found several of
those remembered statuses to be wrong in both directions.

---

## How to read the state column

These are not synonyms and they are not a progress bar. A milestone may be
live in production and still not be accepted.

| state | means |
|---|---|
| **COMPLETE** | built, evidenced, accepted; nothing outstanding |
| **PUBLISHED** | live in production and verified as live; acceptance still outstanding — see the milestone's own gap list |
| **BUILT / ACCEPTANCE OUTSTANDING** | implemented and exercised, but named acceptance criteria have not been run |
| **BLOCKED** | cannot honestly proceed until a named external precondition exists |
| **NOT STARTED** | no substantive implementation |
| **PARTIAL** | some workstreams complete, others not; the row names which |

"In progress" is not a state in this document. Where evidence supports a more
precise word, the more precise word is used.

**Next workstream:** V7.6 / V7.7-A production acceptance —
`.scratch/vector-product/V7.6-ACCEPTANCE-PLAN.md`. The reasoning is in §5 of
that document; in one line, a release went live today over a rollback path that
has never run on this root, and nobody has yet seen it drawn on a handset.

---

## 1. The table

| milestone | state | evidence | what is outstanding |
|---|---|---|---|
| **V7.5** — 3D spatial reconnaissance | **COMPLETE** | `V7.5-3D-SPATIAL-RECON.md`, commit `451fd85`, `V7.5-3D-RECON-EVIDENCE/` | nothing |
| **V7.6** — national 3D bake | **PUBLISHED** | `V7.6-PUBLICATION-RECORD.md`; live since **2026-09-20T16:31:34Z** | handset acceptance, production visual verification, L1 cap decision, L3 z14/z15 determinism, L5 manifest input, L7 docstring — see §2 |
| **V7.7-A** — release manifest, gate, publisher, migration | **BUILT / ACCEPTANCE OUTSTANDING** | commits `4d627cd`, `9757c54`, `db9e190`, `87b2101`, `f9173ba`, `f85aa4c`, `95132c4`; `V7.7-TILE-PUBLISHING.md`; 66/66 publisher tests green | AC-13 and AC-23 hold **on local synthetic roots only**; AC-15, AC-17, AC-18 unexercised — **while a production activation has already happened through this publisher**. See §5. |
| **V7.7-B** — spatial camera & navigation-first 3D | **NOT STARTED** | reconnaissance only (`V7.5-3D-SPATIAL-RECON.md`) | junction-aware framing, ramps/bridges as 3D form, cinematic approach/arrival |
| **V7.8** — driving context | **PARTIAL** | see §3 | signal visualization and speed cameras are **done**; traffic timing is **BLOCKED**; road landmarks and 3D driving visualization **NOT STARTED** |
| **V7.9** — walking | **PARTIAL** | `V7.4-STAGE-4C-FINAL.md`, `V7.4-SHADE.md` | walking navigation **shipped**; graph connectivity remains the core gap; shade-*aware routing* **NOT STARTED** |
| **V8.0 / V8.1** | **NOT STARTED** | `V8-LANE-TRUE-CARTOGRAPHY.md` is a design document, not an implementation | do not open until the V7 acceptance gaps close |

---

## 2. V7.6 — PUBLISHED, not accepted

The national 3D bake is live. `https://your-host.example.com/tiles/version` returns
`vector-tiles-2026-09-20T1429Z-2a9e0dc`, the host's `current` points at it, and
30 of 30 tiles sampled across z6–z15 are byte-identical to the bake manifest. A
production tile decodes to 303 buildings with heights from 10 m to 247 m.

Measured, against the previous release: 18,314 tiles, 72.2 MB, **+15.98%**,
237 s bake. **228,872 building instances over 152,029 distinct footprints**
where there were none. Five of six existing kinds reproduce feature-for-feature
— road, water, label, park and coastline all delta 0; POI +2.

**Open, and not closed by publication:**

| id | gap | note |
|---|---|---|
| L1 | 928 boundary discontinuities in dense Doha | 519 at z14, 409 at z15. Cause established: the neighbour tile was at the 1,500-feature cap in 95.18% of z14 and 100% of z15 cases. 95 tiles of 17,060 sit at the cap. **Needs a decision on the numbers, not more measurement.** |
| L2 | S24 Ultra handset acceptance | never run; L4 passed on an emulator with GPU passthrough |
| L3 | z14/z15 determinism | z6–13 re-bakes bit-identical; the other 17,060 tiles have never been re-baked for comparison |
| L5 | Overture places file not hashed into `RELEASE.json` | the release cannot fully name its own inputs |
| L6 | production visual verification | bytes and content verified; nobody has looked at it drawn |
| L7 | `decode_tile` docstring claims `[lon, lat]`, returns tile-local y-DOWN | `encode.py:284`; already caused one incorrect measurement; trivial fix |

**Honesty constraint carried forward:** only 1,929 of 228,872 building instances
(0.84%) carry an explicit `height_m`. The rest derive from `building_levels` or
a default. This is a 3D basemap, not a height survey, and must not be described
as one.

---

## 3. V7.8 — what is done, and what is blocked

The previous roadmap under-credited this milestone. Three workstreams are
closed and should not be reopened absent a regression:

- **Traffic-signal visualization** — commit `238155a`. Structurally verified
  that the available data **cannot** establish a live signal phase, and the
  implementation does not claim one.
- **Speed cameras** — end to end through the V7.3 sequence: `a9bef4e`,
  `e483267`, `d3768e6`, `6d53698`, `1478af2`, `7e9352e`.
- **Signal-aware driving navigation** — Stage 5 closed
  (`V7-STAGE5-SIGNAL.md`).

**Traffic timing is BLOCKED, not pending.** Verified on production
2026-09-20T17:16Z:

```
GET /learned → {"has_learned_overlay": false, "enabled": true,
                "edge_count": 0, "edge_band_count": 0, "compiled": null}
```

The engine has consumed zero probes. No timing system can honestly claim
learned signal timing until a real probe/observation pipeline exists. The
blocker is data acquisition, not algorithm work, and no amount of engineering
on the timing model changes that.

**Not started:** driving-focused traffic-light context, road landmarks, 3D
driving visualization.

---

## 4. V7.9 — walking ships; the graph is the gap

Walking is a real navigation mode. `V7.4-STAGE-4C-FINAL.md` covers entering
walking mode, following, voice, off-route handling, rerouting, honest refusal
and arrival. Shade landed as functionality and was device-validated
(`V7.4-SHADE-EVIDENCE/`).

**The graph is not healthy enough to call walking complete.** Verified on
production 2026-09-20T17:16Z:

```
GET /footz → {"available": true, "nodes": 1108269, "edges": 2377926, ...}
```

Those counts match the V7.4 measurement exactly
(`V7.4-EVIDENCE/out/components.out`), so the deployed graph is the measured
graph: **2,811 components, largest holding 32.00% of nodes**, 753,577 nodes
outside the largest component.

With honest barrier handling the count rises to **4,687**. That increase is
correct, not a regression — a locked gate genuinely splits a pedestrian
network and the census is allowed to say so. Mitigation today is
component-aware snapping.

Two precision notes:

- `/footz` **does not report component counts**. It returns nodes, edges and
  speeds only. `V7.4-STAGE-4A-SNAPPING.md:78` states that `/footz` live reports
  `components: 2811, largest_component_share: 0.32`; it does not, and that line
  is inaccurate. The 2,811 figure is real but comes from offline measurement and
  from source comments (`serve.py:213`, `serve.py:574`), not from the endpoint.
- **Shade data/visualization and shade-aware route selection are separate
  things.** The first shipped. The second is NOT STARTED. Shade-aware routing
  must not be reported as landed because shade rendering landed.

---

## 5. V7.7-A — where rollback actually stands

This milestone was carried as "rollback and crash recovery unexercised". That
is too pessimistic, and the correction matters because it changes what the next
workstream has to do.

**Verified 2026-09-20T17:19Z:** `tests/test_publish_release.py` runs **66 tests,
all passing**. They cover rollback returning to the intended release, rollback
being itself reversible, rollback deleting nothing, rollback correctly refused
when there is no target or the target was pruned, and **all eight injected
failure points** — `after_stage`, `during_transfer`, `after_install`,
`after_verify`, `before_swap`, `after_swap`, `during_rollback`,
`rollback_after_swap` — each asserted to leave a recoverable root, plus orphan
reporting and recovery idempotence.

So AC-13 and AC-23 are **proven against temporary local roots holding synthetic
six-tile releases**. What they are *not* proven against:

- a real release — 18,314 files, 113 MB, versus six tiles
- the actual production root, which still carries the pre-migration flat zoom
  directories and a stale `VERSION.json` beside `releases/`
- the real serving container, with its read-only mount and `TILE_DIR`
  indirection

AC-15, AC-17 and AC-18 are client-side criteria and have not been run against
production at all.

The remaining work is therefore **a drill on the real host, not more code and
not more unit tests**. The publisher is most likely correct; what is missing is
the demonstration.

---

## 6. V7.7-B — not started

The known gaps are junction-aware camera framing, ramps and bridges represented
as meaningful 3D form, and cinematic approach/arrival behaviour.

Existing camera work — speed-banded zoom, maneuver complexity pins, the 60°
pitch cap, `CameraGate` — is V7.1/V7.3 work and **must not be counted toward
V7.7-B**.

---

## 7. Non-negotiables

Vector is self-hosted, privacy-first, Qatar/MENA-useful, OSM/MapLibre-based and
independent of external navigation APIs. It is honest about what its data can
and cannot support.

Never claim:

- live traffic timing without observations — today `edge_count: 0`
- a signal phase the data cannot establish
- pedestrian connectivity while the graph is 2,811 components
- production acceptance on emulator evidence
- visual correctness without having looked at the result
- rollback or crash recovery because the code exists

---

## 8. Maintaining this document

Update it when a milestone changes state, and change the state word only when
the evidence named in the row changes. If production and this document
disagree, **production is the fact** — correct the document, and record the
reconciliation rather than silently editing the claim.
