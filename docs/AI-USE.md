# AI use disclosure

Vector was built with substantial assistance from **Claude (Anthropic)**, used
as a pair-programmer through Claude Code. This file states plainly what that
covered, because a reviewer is entitled to know.

## What AI assistance was used for

- **Implementation.** Large parts of the Android client, the Python services,
  the tile bake and the publisher were written with AI assistance, always under
  review, and always landed with tests.
- **Tests.** Many of the 1,790 Android and 625 routing tests were drafted with
  assistance. Where a test pins a defect, the test was verified to **fail**
  against the pre-fix code before being accepted — a test that cannot fail
  proves nothing, and that rule was applied rather than assumed.
- **Investigation.** Measuring the pedestrian graph's fragmentation, decoding
  production tiles, probing the live API, and auditing what production was
  actually serving.
- **Documentation.** The ADRs, the milestone roadmap, this file and the README.

## What AI assistance did not do

- **It did not decide what is true.** Every quantitative claim in this
  repository traces to a measurement that was run, and the measurements are
  committed alongside the claims under `.scratch/vector-product/`.
- **It did not run the device sessions.** Real-hardware validation on the Galaxy
  S24 Ultra required a physical phone and a human holding it.
- **It did not invent data.** Where Qatar's OSM data cannot support a feature —
  signal phase timing, entrance-level arrival, pedestrian connectivity — the
  feature is refused rather than approximated. That constraint is enforced in
  code (sealed types that cannot express an unfounded claim) and in tests.

## The working rule

The project's engineering constitution requires that a claim name its evidence.
That rule was applied to AI-authored work exactly as to hand-written work: the
tests are real, they run, and the numbers in the README come from output that
was captured rather than remembered.

Where evidence was insufficient, the claim was removed. The README's
Limitations section is the visible result of that — including, specifically,
that 3D building extrusion is **not** claimed as confirmed on real GPU hardware,
because a pitched camera over West Bay has not yet been photographed.
